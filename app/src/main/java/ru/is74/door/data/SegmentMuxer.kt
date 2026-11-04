package ru.is74.door.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.max

data class MuxResult(val durationUs: Long, val segments: Int, val sampleCount: Long, val hasAudio: Boolean)

class SegmentMuxException(message: String) : Exception(message)

/**
 * Copies complete encoded TS samples into MP4 without decoding, seeking or trimming.
 * Missing wall-clock intervals are joined directly. One timestamp offset per
 * segment preserves A/V timing and handles discontinuities / reset PTS.
 * Output must not exist. Failure/cancellation deletes it, never the source files.
 * Progress runs on the IO dispatcher once per fully written segment.
 */
suspend fun muxSegments(
    segments: List<DownloadedSegment>,
    output: File,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
): MuxResult = withContext(Dispatchers.IO) {
    if (segments.isEmpty()) throw SegmentMuxException("Нет фрагментов для сохранения.")
    if (output.exists()) throw SegmentMuxException("Файл назначения уже существует.")
    if (segments.any { it.file.canonicalFile == output.canonicalFile }) throw SegmentMuxException("Исходное видео нельзя перезаписать.")
    output.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw SegmentMuxException("Не удалось создать папку для видео.") }
    var muxer: MediaMuxer? = null
    var started = false
    var completed = false
    try {
        val first = inspectSegment(segments.first())
        val referenceTracks = first.tracks
        val activeMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = activeMuxer
        val outputTracks = referenceTracks.mapValues { (_, track) -> activeMuxer.addTrack(mp4TrackFormat(track.format)) }
        activeMuxer.start()
        started = true
        val buffer = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES)
        val info = MediaCodec.BufferInfo()
        var cursorUs = 0L
        var samplesWritten = 0L
        val finalEnds = mutableMapOf<String, Long>()
        onProgress(0, segments.size)

        segments.forEachIndexed { ordinal, downloaded ->
            coroutineContext.ensureActive()
            val inspected = if (ordinal == 0) first else inspectSegment(downloaded)
            if (inspected.tracks.keys != referenceTracks.keys || inspected.tracks.any { (kind, track) ->
                    !compatibleTracks(referenceTracks.getValue(kind).format, track.format)
                }
            ) throw SegmentMuxException("Параметры камеры изменились внутри периода. Сохраните части по отдельности.")
            val extractor = openExtractor(downloaded.file)
            try {
                val indexToKind = inspected.tracks.map { (kind, track) -> track.index to kind }.toMap()
                inspected.tracks.values.forEach { extractor.selectTrack(it.index) }
                var writtenHere = 0L
                while (extractor.sampleTrackIndex >= 0) {
                    coroutineContext.ensureActive()
                    val kind = indexToKind[extractor.sampleTrackIndex]
                        ?: throw SegmentMuxException("Не удалось определить дорожку видео.")
                    val flags = extractor.sampleFlags
                    validateSampleFlags(flags)
                    buffer.clear()
                    val size = try { extractor.readSampleData(buffer, 0) } catch (_: RuntimeException) {
                        throw SegmentMuxException("Кадр слишком большой или фрагмент повреждён.")
                    }
                    if (size < 0) break
                    if (size > buffer.capacity()) throw SegmentMuxException("Слишком большой кадр для сохранения.")
                    val format = inspected.tracks.getValue(kind).format
                    // Android's TS extractor preserves ADTS on AAC samples, while
                    // MP4 requires the raw AAC access unit. Remove transport
                    // framing only; encoded AAC payload and sample time stay intact.
                    val payloadOffset = if (kind == "audio") adtsPayloadOffset(
                        buffer, size, format.intOrNull(MediaFormat.KEY_IS_ADTS) == 1,
                        format.intOrNull(MediaFormat.KEY_SAMPLE_RATE), format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                    ) else 0
                    val ptsUs = extractor.sampleTime - inspected.baseUs + cursorUs
                    if (ptsUs < 0) throw SegmentMuxException("Во фрагменте некорректная временная шкала.")
                    val sampleFlags = if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(payloadOffset, size - payloadOffset, ptsUs, sampleFlags)
                    buffer.position(0)
                    buffer.limit(size)
                    // Preserve extraction order, including B-frame PTS reordering.
                    // Android MediaMuxer supports B-frames on API 25+.
                    activeMuxer.writeSampleData(outputTracks.getValue(kind), buffer, info)
                    samplesWritten++
                    writtenHere++
                    extractor.advance()
                }
                if (writtenHere != inspected.sampleCount) throw SegmentMuxException("Фрагмент прочитан не полностью. Попробуйте сохранить снова.")
                inspected.tracks.forEach { (kind, track) ->
                    finalEnds[kind] = cursorUs + track.maxPtsUs - inspected.baseUs + track.estimatedSampleDurationUs()
                }
                cursorUs += max(downloaded.segment.durationUs, inspected.durationUs)
            } finally {
                extractor.release()
            }
            onProgress(ordinal + 1, segments.size)
        }

        coroutineContext.ensureActive()
        // Explicit EOS sets final sample duration, without duplicating any frame.
        val empty = ByteBuffer.allocate(0)
        finalEnds.forEach { (kind, endUs) ->
            info.set(0, 0, endUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            activeMuxer.writeSampleData(outputTracks.getValue(kind), empty, info)
        }
        activeMuxer.stop()
        started = false
        activeMuxer.release()
        muxer = null
        coroutineContext.ensureActive()
        if (output.length() == 0L) throw SegmentMuxException("Не удалось собрать видеофайл.")
        completed = true
        MuxResult(finalEnds.values.maxOrNull() ?: cursorUs, segments.size, samplesWritten, "audio" in referenceTracks)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (known: SegmentMuxException) {
        throw known
    } catch (_: Exception) {
        throw SegmentMuxException("Android не смог объединить фрагменты без преобразования. Попробуйте меньший период.")
    } finally {
        muxer?.let { active ->
            if (started) runCatching { active.stop() }
            runCatching { active.release() }
        }
        if (!completed) output.delete()
    }
}

private data class SegmentTrack(
    val index: Int,
    val format: MediaFormat,
    var minPtsUs: Long = Long.MAX_VALUE,
    var maxPtsUs: Long = Long.MIN_VALUE,
    var secondMaxPtsUs: Long = Long.MIN_VALUE,
    var samples: Long = 0,
) {
    fun addTimestamp(ptsUs: Long) {
        minPtsUs = minOf(minPtsUs, ptsUs)
        if (ptsUs > maxPtsUs) {
            secondMaxPtsUs = maxPtsUs
            maxPtsUs = ptsUs
        } else if (ptsUs < maxPtsUs) secondMaxPtsUs = maxOf(secondMaxPtsUs, ptsUs)
        samples++
    }

    fun estimatedSampleDurationUs(): Long {
        if (secondMaxPtsUs != Long.MIN_VALUE && maxPtsUs > secondMaxPtsUs) {
            return (maxPtsUs - secondMaxPtsUs).coerceAtMost(1_000_000L)
        }
        if (format.stringOrNull(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC) {
            val rate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0
            if (rate > 0) return 1_024_000_000L / rate
        }
        val frameRate = format.intOrNull(MediaFormat.KEY_FRAME_RATE) ?: 25
        return 1_000_000L / frameRate.coerceIn(1, 240)
    }
}

private data class InspectedSegment(val tracks: Map<String, SegmentTrack>, val baseUs: Long, val durationUs: Long, val sampleCount: Long)

private suspend fun inspectSegment(downloaded: DownloadedSegment): InspectedSegment {
    coroutineContext.ensureActive()
    if (downloaded.segment.durationUs <= 0) throw SegmentMuxException("У фрагмента неизвестна длительность.")
    val extractor = openExtractor(downloaded.file)
    try {
        val tracks = linkedMapOf<String, SegmentTrack>()
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.stringOrNull(MediaFormat.KEY_MIME).orEmpty()
            val kind = when {
                mime.startsWith("video/") -> "video"
                mime.startsWith("audio/") -> "audio"
                else -> continue
            }
            val allowed = if (kind == "video") mime in setOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
            else mime == MediaFormat.MIMETYPE_AUDIO_AAC
            if (!allowed) throw SegmentMuxException("Этот кодек камеры пока нельзя сохранить без преобразования.")
            if (kind in tracks) throw SegmentMuxException("Сохранение нескольких дорожек одного типа пока не поддерживается.")
            if (format.getByteBuffer("csd-0") == null) throw SegmentMuxException("Во фрагменте нет описания кодека.")
            tracks[kind] = SegmentTrack(index, format)
            extractor.selectTrack(index)
        }
        if ("video" !in tracks) throw SegmentMuxException("Во фрагменте не найдена видеодорожка.")
        val byIndex = tracks.values.associateBy { it.index }
        while (extractor.sampleTrackIndex >= 0) {
            coroutineContext.ensureActive()
            validateSampleFlags(extractor.sampleFlags)
            val pts = extractor.sampleTime
            if (pts < 0) throw SegmentMuxException("Во фрагменте некорректные метки времени.")
            byIndex.getValue(extractor.sampleTrackIndex).addTimestamp(pts)
            extractor.advance()
        }
        if (tracks.values.any { it.samples == 0L }) throw SegmentMuxException("Одна из дорожек фрагмента пуста.")
        val base = tracks.values.minOf { it.minPtsUs }
        val duration = tracks.values.maxOf { it.maxPtsUs - base + it.estimatedSampleDurationUs() }
        if (duration > downloaded.segment.durationUs + 5_000_000L) {
            throw SegmentMuxException("Временные метки фрагмента повреждены. Выберите другой период.")
        }
        return InspectedSegment(tracks, base, duration, tracks.values.sumOf { it.samples })
    } finally {
        extractor.release()
    }
}

private fun openExtractor(file: File): MediaExtractor {
    if (!file.isFile || file.length() == 0L) throw SegmentMuxException("Один из скачанных фрагментов отсутствует.")
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        return extractor
    } catch (_: Exception) {
        extractor.release()
        throw SegmentMuxException("Android не смог прочитать скачанный видеофрагмент.")
    }
}

private fun validateSampleFlags(flags: Int) {
    if (flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw SegmentMuxException("Зашифрованные фрагменты не поддерживаются.")
    if (flags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) throw SegmentMuxException("Неполные кадры этого потока пока не поддерживаются.")
}

private fun compatibleTracks(first: MediaFormat, next: MediaFormat): Boolean {
    if (first.stringOrNull(MediaFormat.KEY_MIME) != next.stringOrNull(MediaFormat.KEY_MIME)) return false
    val keys = listOf(MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_SAMPLE_RATE,
        MediaFormat.KEY_CHANNEL_COUNT, MediaFormat.KEY_AAC_PROFILE, MediaFormat.KEY_PROFILE,
        MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER)
    if (keys.any { first.intOrNull(it) != next.intOrNull(it) }) return false
    return (0..2).all { index -> first.getByteBuffer("csd-$index")?.duplicate() == next.getByteBuffer("csd-$index")?.duplicate() }
}

/** A transport-stream format's is-adts flag must not leak into an MP4 track. */
private fun mp4TrackFormat(source: MediaFormat): MediaFormat {
    if (source.stringOrNull(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) return source
    return MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
        source.getInteger(MediaFormat.KEY_SAMPLE_RATE), source.getInteger(MediaFormat.KEY_CHANNEL_COUNT)).apply {
        source.getByteBuffer("csd-0")?.let { setByteBuffer("csd-0", it.duplicate()) }
        (source.intOrNull(MediaFormat.KEY_AAC_PROFILE) ?: source.intOrNull(MediaFormat.KEY_PROFILE))?.let {
            setInteger(MediaFormat.KEY_AAC_PROFILE, it)
        }
        setInteger(MediaFormat.KEY_IS_ADTS, 0)
    }
}

/** Converts one explicitly ADTS-framed access unit to raw AAC for MP4. */
internal fun adtsPayloadOffset(
    buffer: ByteBuffer,
    size: Int,
    declaredAdts: Boolean,
    sampleRate: Int? = null,
    channels: Int? = null,
): Int {
    // A raw AAC payload may coincidentally begin with 0xff: do not sniff-strip it.
    if (!declaredAdts) return 0
    fun byte(index: Int): Int = buffer.get(index).toInt() and 0xff
    if (size < 7 || byte(0) != 0xff || byte(1) and 0xf6 != 0xf0) {
        throw SegmentMuxException("Повреждён заголовок аудиофрагмента.")
    }
    val headerSize = if (byte(1) and 1 == 1) 7 else 9
    val frameLength = ((byte(3) and 3) shl 11) or (byte(4) shl 3) or (byte(5) shr 5)
    if (size <= headerSize || frameLength != size) {
        throw SegmentMuxException("Аудиофрагмент содержит неполный или составной AAC-кадр.")
    }
    if (byte(6) and 3 != 0) throw SegmentMuxException("AAC с несколькими блоками в кадре пока не поддерживается.")
    val frequencyIndex = (byte(2) shr 2) and 15
    val headerRate = AAC_SAMPLE_RATES.getOrNull(frequencyIndex)
    val headerChannels = ((byte(2) and 1) shl 2) or (byte(3) shr 6)
    if (headerRate == null || (sampleRate != null && headerRate != sampleRate) ||
        (channels != null && headerChannels != 0 && headerChannels != channels)
    ) throw SegmentMuxException("Параметры аудио изменились внутри фрагмента.")
    return headerSize
}

private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
private fun MediaFormat.stringOrNull(key: String): String? = if (containsKey(key)) getString(key) else null
private const val MAX_SAMPLE_BYTES = 16 * 1024 * 1024
private val AAC_SAMPLE_RATES = listOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)
