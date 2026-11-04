package ru.is74.door.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Collections
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToLong

/** URLs contain short-lived access signatures: never persist or log this object. */
data class HlsSegment(val url: String, val durationUs: Long, val start: Instant?, val discontinuity: Boolean) {
    val end: Instant? get() = start?.plusNanos(durationUs * 1_000L)
    override fun toString(): String = "HlsSegment(durationUs=$durationUs, timed=${start != null}, discontinuity=$discontinuity)"
}
data class HlsExportPlan(
    val segments: List<HlsSegment>, val requestedStart: Instant, val requestedEnd: Instant,
    val effectiveStart: Instant, val effectiveEnd: Instant, val sourceDurationUs: Long, val gapDurationUs: Long,
) {
    override fun toString(): String = "HlsExportPlan(segments=${segments.size}, durationUs=$sourceDurationUs, gapUs=$gapDurationUs)"
}
data class DownloadedSegment(val file: File, val segment: HlsSegment)

internal data class ParsedHls(val segments: List<HlsSegment>, val variants: List<String>, val ended: Boolean)

internal fun safeHlsUri(url: String): URI {
    val uri = try { URI(url) } catch (_: Exception) { throw ApiException("Сервер вернул некорректную ссылку на видео") }
    if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.host?.lowercase(Locale.ROOT) !in setOf("cdn.cams.is74.ru", "cams.is74.ru") || uri.userInfo != null || uri.port !in listOf(-1, 443) || uri.fragment != null) {
        throw ApiException("Сервер вернул ссылку на неподдерживаемый видеосервис")
    }
    return uri
}

/** Pure HLS parser for the unencrypted MPEG-TS archive returned by this service. */
internal fun parseExportPlaylist(url: String, text: String): ParsedHls {
    val base = safeHlsUri(url)
    val lines = text.removePrefix("\uFEFF").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    if (lines.firstOrNull() != "#EXTM3U") throw ApiException("Сервер вернул ответ вместо видеоплейлиста")
    val segments = mutableListOf<HlsSegment>(); val variants = mutableListOf<String>()
    var nextVariant = false; var durationUs: Long? = null; var clock: Instant? = null
    var discontinuity = false; var ended = false
    for (line in lines.drop(1)) {
        when {
            line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:") -> {
                if (!Regex("(?:^|,)METHOD=NONE(?:,|$)").containsMatchIn(line.substringAfter(':'))) {
                    throw ApiException("Зашифрованное видео нельзя сохранить этим способом")
                }
            }
            line.startsWith("#EXT-X-BYTERANGE") || line.startsWith("#EXT-X-MAP") || line.startsWith("#EXT-X-PART:") || line.startsWith("#EXT-X-GAP") || line.startsWith("#EXT-X-I-FRAMES-ONLY") ->
                throw ApiException("Этот формат видеоархива пока не поддерживается")
            line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO") && line.contains("URI=") ->
                throw ApiException("Видео с отдельной аудиодорожкой пока не поддерживается")
            line.startsWith("#EXT-X-STREAM-INF:") -> nextVariant = true
            line.startsWith("#EXTINF:") -> {
                val seconds = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                if (seconds == null || !seconds.isFinite() || seconds <= 0 || seconds > 3_600) throw ApiException("Некорректная длительность видеофрагмента")
                durationUs = (seconds * 1_000_000).roundToLong()
            }
            line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> {
                val value = line.substringAfter(':')
                clock = runCatching { Instant.parse(value) }.getOrNull()
                    ?: runCatching { OffsetDateTime.parse(value, DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE_TIME).appendOffset("+HHmm", "Z").toFormatter()).toInstant() }.getOrNull()
                    ?: throw ApiException("Не удалось определить время видеофрагмента")
            }
            line == "#EXT-X-DISCONTINUITY" -> discontinuity = true
            line == "#EXT-X-ENDLIST" -> ended = true
            !line.startsWith('#') -> {
                val resolved = safeHlsUri(base.resolve(line).toString()).toString()
                if (nextVariant) { variants.add(resolved); nextVariant = false }
                else {
                    val duration = durationUs ?: throw ApiException("В плейлисте отсутствует длительность видеофрагмента")
                    segments.add(HlsSegment(resolved, duration, clock, discontinuity))
                    clock = clock?.plusNanos(duration * 1_000L)
                    durationUs = null; discontinuity = false
                }
            }
        }
    }
    if (nextVariant || durationUs != null) throw ApiException("Сервер вернул неполный видеоплейлист")
    return ParsedHls(Collections.unmodifiableList(segments), Collections.unmodifiableList(variants), ended)
}

internal fun makeExportPlan(segments: List<HlsSegment>, start: Instant, end: Instant): HlsExportPlan {
    require(start < end)
    // Adjacent hourly requests may return the same keyframe segment under fresh signatures.
    // Program-date-time and duration identify that same video independently of signed URLs.
    val selected = segments.filter {
        val a = it.start ?: throw ApiException("В архиве нет временных меток для точного выбора диапазона")
        val b = it.end!!
        a < end && b > start
    }.distinctBy { it.start to it.durationUs }.sortedBy { it.start }
    if (selected.isEmpty()) throw ApiException("В выбранном диапазоне нет видеозаписи")
    var cursor = start; var coveredUs = 0L
    for (segment in selected) {
        val a = maxOf(segment.start!!, start, cursor)
        val b = minOf(segment.end!!, end)
        if (a < b) { coveredUs += Duration.between(a, b).toNanos() / 1_000L; cursor = b }
    }
    // Preserve source discontinuities and mark archive gaps even if the server omitted the tag.
    val normalized = selected.mapIndexed { index, segment ->
        val previous = selected.getOrNull(index - 1)
        val gap = previous?.end?.let { Duration.between(it, segment.start).toMillis() > 100 } ?: false
        if (gap) segment.copy(discontinuity = true) else segment
    }
    return HlsExportPlan(Collections.unmodifiableList(normalized), start, end,
        selected.first().start!!, selected.maxOf { it.end!! }, selected.sumOf { it.durationUs },
        (Duration.between(start, end).toNanos() / 1_000L - coveredUs).coerceAtLeast(0))
}

class HlsExporter {
    /** Downloads only manifests. No video segments are fetched during planning. */
    suspend fun prepare(
        camera: Camera, start: Instant, end: Instant, headers: Map<String, String>,
        onPlanProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): HlsExportPlan = withContext(Dispatchers.IO) {
        if (!camera.canArchive) throw ApiException(camera.archiveReason.ifBlank { "Нет доступа к видеоархиву" })
        if (start >= end) throw ApiException("Выберите конец диапазона позже начала")
        val queryStart = maxOf(start, camera.archiveStart ?: start)
        val queryEnd = minOf(end, camera.archiveEnd ?: end, Instant.now())
        if (queryStart >= queryEnd) throw ApiException("Выбранный диапазон вне доступного видеоархива")
        val duration = Duration.between(queryStart, queryEnd)
        val seconds = (duration.seconds + if (duration.nano > 0) 1 else 0).coerceAtLeast(1)
        val total = ((seconds + 3_599) / 3_600).toInt()
        if (total > 8_784) throw ApiException("Выберите диапазон не длиннее одного года")
        val segments = mutableListOf<HlsSegment>()
        onPlanProgress(0, total)
        var cursor = queryStart
        for (chunk in 0 until total) {
            coroutineContext.ensureActive()
            val stop = minOf(cursor.plusSeconds(3_600), queryEnd)
            val url = archivePlaybackUrl(camera, cursor, stop)
            val playlist = mediaPlaylist(url, headers)
            if (playlist != null) {
                if (!playlist.ended) throw ApiException("Сервер ещё формирует архив. Выберите время немного раньше")
                segments.addAll(playlist.segments)
                if (segments.size > 150_000) throw ApiException("Слишком большой архив. Выберите меньший диапазон")
            }
            cursor = stop
            onPlanProgress(chunk + 1, total)
        }
        makeExportPlan(segments, start, end)
    }

    suspend fun download(
        plan: HlsExportPlan, directory: File, headers: Map<String, String>,
        onProgress: (done: Int, total: Int, bytes: Long) -> Unit,
    ): List<DownloadedSegment> = withContext(Dispatchers.IO) {
        if (!directory.isDirectory && !directory.mkdirs()) throw ApiException("Не удалось создать временную папку")
        val result = mutableListOf<DownloadedSegment>()
        var partial: File? = null; var totalBytes = 0L
        try {
            onProgress(0, plan.segments.size, 0)
            for ((index, segment) in plan.segments.withIndex()) {
                coroutineContext.ensureActive()
                val name = "segment-%06d.ts".format(Locale.ROOT, index)
                val final = File(directory, name); val temp = File(directory, "$name.part")
                if (final.exists() || temp.exists()) throw ApiException("Временная папка уже содержит видео. Повторите сохранение")
                ensureStorage(directory, 64L * 1024 * 1024)
                partial = temp
                val connection = connect(segment.url, headers)
                try {
                    val status = connection.responseCode
                    if (status !in 200..299) throw responseError(status)
                    val declaredLength = connection.contentLengthLong
                    var segmentBytes = 0L
                    connection.inputStream.use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var first = true
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (first && count > 0) {
                                    if (buffer[0].toInt() and 0xff != 0x47) throw ApiException("Сервер вернул данные вместо MPEG-TS видео")
                                    first = false
                                }
                                segmentBytes += count
                                if (segmentBytes > 128L * 1024 * 1024) throw ApiException("Слишком большой видеофрагмент")
                                if ((segmentBytes - count) / (1024 * 1024) != segmentBytes / (1024 * 1024)) ensureStorage(directory, 32L * 1024 * 1024)
                                output.write(buffer, 0, count)
                                totalBytes += count
                                onProgress(index, plan.segments.size, totalBytes)
                            }
                        }
                    }
                    if (segmentBytes == 0L) throw ApiException("Сервер вернул пустой видеофрагмент")
                    if (declaredLength >= 0 && segmentBytes != declaredLength) throw ApiException("Видео загрузилось не полностью. Повторите сохранение")
                    coroutineContext.ensureActive()
                    if (!temp.renameTo(final)) throw ApiException("Не удалось сохранить видеофрагмент")
                    partial = null
                    result.add(DownloadedSegment(final, segment))
                    onProgress(index + 1, plan.segments.size, totalBytes)
                } finally { connection.disconnect() }
            }
            Collections.unmodifiableList(result)
        } catch (error: Exception) {
            partial?.delete()
            result.forEach { it.file.delete() }
            throw error
        }
    }

    private suspend fun mediaPlaylist(url: String, headers: Map<String, String>): ParsedHls? {
        var current = url
        repeat(3) {
            coroutineContext.ensureActive()
            val text = playlistText(current, headers) ?: return null
            val parsed = parseExportPlaylist(current, text)
            if (parsed.variants.isEmpty()) return parsed
            current = parsed.variants.first() // This service currently exposes a single main MPEG-TS variant.
        }
        throw ApiException("Слишком много вложенных видеоплейлистов")
    }
    private suspend fun playlistText(url: String, headers: Map<String, String>): String? {
        val connection = connect(url, headers)
        try {
            val status = connection.responseCode
            if (status == 404) return null
            if (status !in 200..299) throw responseError(status)
            val builder = StringBuilder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8_192)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = reader.read(buffer); if (count < 0) break
                    builder.append(buffer, 0, count)
                    if (builder.length > 8_000_000) throw ApiException("Слишком большой видеоплейлист")
                }
            }
            return builder.toString()
        } finally { connection.disconnect() }
    }
    private fun ensureStorage(directory: File, reserveBytes: Long) {
        val free = directory.usableSpace
        if (free > 0 && free < reserveBytes) throw ApiException("Недостаточно места для сохранения видео")
    }
    private fun connect(url: String, headers: Map<String, String>): HttpURLConnection {
        val connection = safeHlsUri(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000; connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "skyplayer")
        connection.setRequestProperty("Accept-Encoding", "identity")
        headers.entries.firstOrNull { it.key.equals("Authorization", true) }?.value?.let { connection.setRequestProperty("Authorization", it) }
        return connection
    }
    private fun responseError(status: Int): ApiException = ApiException(when (status) {
        401, 403 -> "Доступ к видео истёк. Обновите камеру и повторите сохранение"
        404 -> "Видео за часть выбранного периода больше недоступно"
        in 300..399 -> "Видеосервис изменил адрес. Не удалось продолжить безопасное скачивание"
        else -> "Не удалось получить видео с сервера ($status)"
    })
}
