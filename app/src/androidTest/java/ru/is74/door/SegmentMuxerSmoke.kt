package ru.is74.door

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.is74.door.data.DownloadedSegment
import ru.is74.door.data.HlsSegment
import ru.is74.door.data.SegmentMuxException
import ru.is74.door.data.muxSegments
import ru.is74.door.data.adtsPayloadOffset
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant

/**
 * Optional private-device fixture test. No camera footage, URLs or credentials
 * are bundled in the APK. Seed filesDir/remux-smoke with .ts files and a JSON
 * array fixture-manifest.json: [{"file":"segment-000000.ts","durationUs":...}].
 * joined.mp4 and mux-proof.json are retained privately for external verification.
 */
@RunWith(AndroidJUnit4::class)
class SegmentMuxerSmoke {
    private val directory get() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "remux-smoke")

    private fun fixtures(): List<DownloadedSegment> {
        val manifest = File(directory, "fixture-manifest.json")
        assumeTrue("No optional private TS fixtures supplied", manifest.exists())
        val json = JSONArray(manifest.readText())
        return (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            val name = item.getString("file")
            require(name.matches(Regex("segment-[0-9]+\\.ts")))
            DownloadedSegment(File(directory, name), HlsSegment(
                url = "https://cdn.cams.is74.ru/private-test-placeholder.ts",
                durationUs = item.getLong("durationUs"),
                start = Instant.EPOCH,
                discontinuity = index > 0,
            ))
        }.also { assumeTrue("Need at least two source segments", it.size >= 2) }
    }

    @Test fun wholeSegmentsRemuxToPlayableMp4WithoutDroppingSamples() = runBlocking {
        val inputs = fixtures()
        val sourceCounts = mutableMapOf<String, Long>()
        inputs.forEach { input -> sampleCounts(input.file).forEach { (mime, count) -> sourceCounts[mime] = sourceCounts.getOrDefault(mime, 0) + count } }
        val output = File(directory, "joined.mp4").apply { delete() }
        val progress = mutableListOf<Int>()
        val result = muxSegments(inputs, output) { done, total ->
            assertEquals(inputs.size, total)
            progress.add(done)
        }
        assertEquals((0..inputs.size).toList(), progress)
        assertEquals(sourceCounts, sampleCounts(output))
        assertEquals(sourceCounts.values.sum(), result.sampleCount)
        assertTrue(result.durationUs > 0)
        assertTrue(output.length() > 0)
        assertEquals("Encoded AAC payload changed", aacPayloadHash(inputs.map { it.file }), aacPayloadHash(listOf(output)))
        val nativeProof = proveNativePlayback(output)

        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(output.absolutePath)
            val durationMs = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            assertTrue("MP4 duration disagrees with sample timestamps", kotlin.math.abs(durationMs * 1_000L - result.durationUs) < 100_000L)
            listOf(0L, result.durationUs / 2, (result.durationUs - 500_000L).coerceAtLeast(0)).forEach { timeUs ->
                val frame = metadata.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                assertNotNull("Could not decode an MP4 frame at $timeUs", frame)
                frame!!.recycle()
            }
            File(directory, "mux-proof.json").writeText(JSONObject().apply {
                put("segments", result.segments)
                put("sourceSamples", JSONObject(sourceCounts))
                put("outputSamples", JSONObject(sampleCounts(output)))
                put("durationUs", result.durationUs)
                put("outputBytes", output.length())
                put("decodedBeginningMiddleEnd", true)
                put("encodedAacPayloadUnchanged", true)
                put("nativePlayback", nativeProof)
            }.toString(2))
        } finally {
            metadata.release()
        }
    }

    @Test fun cancellationDeletesIncompleteOutputAndPreservesInputs() = runBlocking {
        val inputs = fixtures()
        val output = File(directory, "cancelled.mp4").apply { delete() }
        val job = launch {
            muxSegments(inputs, output) { done, _ -> if (done == 1) cancel() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(output.exists())
        assertTrue(inputs.all { it.file.isFile })
    }

    @Test fun corruptLaterSegmentDeletesIncompleteOutput() = runBlocking {
        val inputs = fixtures()
        val corrupt = File(directory, "corrupt.ts").apply { writeText("not a video") }
        val output = File(directory, "failed.mp4").apply { delete() }
        try {
            var failed = false
            try {
                muxSegments(listOf(inputs.first(), inputs[1].copy(file = corrupt)), output)
            } catch (_: SegmentMuxException) { failed = true }
            assertTrue("Corrupt media must fail clearly", failed)
            assertFalse(output.exists())
            assertTrue(inputs.all { it.file.isFile })
        } finally {
            corrupt.delete()
        }
    }

    private fun sampleCounts(file: File): Map<String, Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val types = mutableMapOf<Int, String>()
            val counts = mutableMapOf<String, Long>()
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    types[index] = mime
                    counts[mime] = 0L
                    extractor.selectTrack(index)
                }
            }
            while (extractor.sampleTrackIndex >= 0) {
                val mime = types.getValue(extractor.sampleTrackIndex)
                counts[mime] = counts.getValue(mime) + 1
                extractor.advance()
            }
            return counts
        } finally {
            extractor.release()
        }
    }

    private fun aacPayloadHash(files: List<File>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(1_048_576)
        files.forEach { file ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val index = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
                } ?: return@forEach
                val format = extractor.getTrackFormat(index)
                val adts = format.containsKey(MediaFormat.KEY_IS_ADTS) && format.getInteger(MediaFormat.KEY_IS_ADTS) == 1
                extractor.selectTrack(index)
                while (extractor.sampleTrackIndex >= 0) {
                    buffer.clear()
                    val count = extractor.readSampleData(buffer, 0)
                    if (count < 0) break
                    val offset = adtsPayloadOffset(buffer, count, adts)
                    buffer.position(offset); buffer.limit(count)
                    digest.update(buffer)
                    extractor.advance()
                }
            } finally { extractor.release() }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
