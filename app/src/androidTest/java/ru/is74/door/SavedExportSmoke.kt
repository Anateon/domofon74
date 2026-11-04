package ru.is74.door

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.ImageReader
import android.graphics.ImageFormat
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Optional proof for an actual worker-exported file; no footage ships in tests. */
@RunWith(AndroidJUnit4::class)
class SavedExportSmoke {
    @Test fun actualExportDecodesAndPlaysInNativeAndroidPlayer() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "remux-smoke")
        val file = File(directory, "export.mp4")
        assumeTrue("No optional actual exported file supplied", file.exists())
        val proof = JSONObject().put("bytes", file.length())
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val types = mutableMapOf<Int, String>()
            val counts = mutableMapOf<String, Long>()
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                types[i] = mime
                counts[mime] = 0
                extractor.selectTrack(i)
            }
            while (extractor.sampleTrackIndex >= 0) {
                val mime = types.getValue(extractor.sampleTrackIndex)
                counts[mime] = counts.getValue(mime) + 1
                assertTrue(extractor.sampleTime >= 0)
                extractor.advance()
            }
            assertTrue(counts.entries.any { it.key.startsWith("video/") && it.value > 0 })
            proof.put("samples", JSONObject(counts))
        } finally { extractor.release() }

        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(file.absolutePath)
            val durationMs = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            proof.put("durationMs", durationMs)
            for (timeUs in listOf(0L, durationMs * 500, (durationMs - 500) * 1_000)) {
                val frame = metadata.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                assertNotNull("Could not decode actual export at $timeUs", frame)
                frame!!.recycle()
            }
            proof.put("decodedBeginningMiddleEnd", true)
        } finally { metadata.release() }

        val nativeProof = proveNativePlayback(file)
        nativeProof.keys().forEach { key -> proof.put(key, nativeProof.get(key)) }
        File(directory, "actual-export-proof.json").writeText(proof.toString(2))
    }
}

internal fun proveNativePlayback(file: File): JSONObject {
        val proof = JSONObject()
        val rendered = AtomicInteger()
        val frameThread=HandlerThread("mp4-test-frames").apply {start()}
        val frames=ImageReader.newInstance(1280,720,ImageFormat.PRIVATE,3).apply {
            setOnImageAvailableListener({reader->reader.acquireLatestImage()?.let { image ->
                rendered.incrementAndGet(); image.close()
            }},Handler(frameThread.looper))
        }
        val player = MediaPlayer()
        try {
            player.setSurface(frames.surface)
            player.setDataSource(file.absolutePath)
            player.setVolume(0f, 0f)
            player.prepare()
            proof.put("nativePrepared", true)
            player.start()
            val deadline = android.os.SystemClock.elapsedRealtime() + 8_000
            while ((player.currentPosition <= 100 || rendered.get() == 0) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            val firstPosition = player.currentPosition
            assertTrue("Native player did not advance", firstPosition > 100)
            assertTrue(player.isPlaying)
            assertTrue("Native player did not render a frame", rendered.get() > 0)
            proof.put("nativePositionMs", firstPosition)
            val midpoint = player.duration / 2
            player.seekTo(midpoint)
            Thread.sleep(1_000)
            assertTrue("Native player did not seek", kotlin.math.abs(player.currentPosition - midpoint) < 5_000)
            proof.put("nativePositionAfterSeekMs", player.currentPosition)
            proof.put("nativeRenderedFrames", rendered.get())
        } finally { player.release();frames.close();frameThread.quitSafely() }
        return proof
}
