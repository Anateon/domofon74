package ru.is74.door.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

val CameraZone: ZoneId = ZoneId.of("Asia/Yekaterinburg")

data class Account(val userId: String, val address: String)
data class AuthChallenge(val authId: String, val message: String, val retryAfterSeconds: Int, val confirmType: Int)
data class ConfirmedAuth(val authId: String, val accounts: List<Account>)
data class Session(val token: String, val userId: String, val profileId: String, val deviceId: String, val intercomToken: String? = null)
data class Door(val id: String, val entranceUid: String, val mac: String, val title: String, val address: String, val canOpen: Boolean, val hasVideo: Boolean)
data class Camera(val id: String, val uuid: String, val name: String, val liveUrl: String, val archiveUrl: String, val archiveStart: Instant?, val archiveEnd: Instant?, val canLive: Boolean, val canArchive: Boolean, val archiveReason: String)
data class RecordingRange(val start: Instant, val end: Instant)
data class DoorEvent(val id: String, val at: Instant, val type: String, val title: String, val mac: String, val imageUrl: String?)
class ApiException(message: String, val status: Int = 0, val sessionExpired: Boolean = false) : Exception(message)

fun parseCameraInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
    ?: runCatching { OffsetDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]xx")).toInstant() }.getOrNull()
    ?: runCatching { LocalDateTime.parse(value, DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")).atZone(CameraZone).toInstant() }.getOrNull()

// The backend rejects fractional seconds in query timestamps (HTTP 422).
internal fun apiTimestamp(value: Instant): String = value.truncatedTo(ChronoUnit.SECONDS).toString()

/** Only a signed URL provided by the camera API is accepted; no account data is appended. */
fun archivePlaybackUrl(camera: Camera, start: Instant, end: Instant): String {
    require(start < end) { "Конец фрагмента должен быть позже начала" }
    val uri = URI(camera.archiveUrl)
    require(uri.scheme == "https" && uri.host in setOf("cdn.cams.is74.ru", "cams.is74.ru") && uri.userInfo == null) { "Недопустимый адрес видеоархива" }
    val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() && it.substringBefore('=') !in setOf("start-date", "stop-date", "realtime") }
    return "${uri.scheme}://${uri.rawAuthority}${uri.rawPath}?" + (query + listOf(
        "start-date=" + encodeQuery(apiTimestamp(start)), "stop-date=" + encodeQuery(apiTimestamp(end))
    )).joinToString("&")
}
internal fun encodeQuery(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
internal fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key, "")
internal fun normalizeMac(value: String): String = value.filter { it.isLetterOrDigit() }.uppercase()
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

internal object ApiParser {
    fun challenge(json: JSONObject) = AuthChallenge(
        json.text("authId"), json.text("message"),
        (if (json.isNull("timeoutMins")) json.optInt("timeoutMinsDefault", 1) else json.optInt("timeoutMins", 1)).coerceAtLeast(1) * 60,
        json.optInt("confirmType", 1)
    )
    fun confirmed(json: JSONObject): ConfirmedAuth = ConfirmedAuth(json.text("authId"),
        json.optJSONArray("addresses")?.objects().orEmpty().map { Account(it.text("USER_ID"), it.text("ADDRESS")) }.filter { it.userId.isNotBlank() })
    fun session(json: JSONObject, device: String): Session {
        val token = json.text("TOKEN")
        if (token.isBlank() || json.text("USER_ID").isBlank()) throw ApiException("Сервер не выдал данные для входа")
        return Session(token, json.text("USER_ID"), json.text("PROFILE_ID"), device)
    }
    fun door(json: JSONObject): Door = Door(
        json.text("RELAY_ID"), json.text("ENTRANCE_UID"), json.text("MAC_ADDR"),
        json.text("RELAY_DESCR").ifBlank { json.text("RELAY_TYPE") }.ifBlank { "Домофон" } +
            json.text("PORCH_NUM").let { if (it.isNotBlank()) " · подъезд $it" else "" },
        json.text("ADDRESS"), json.text("STATUS_CODE") == "0", json.text("HAS_VIDEO") == "1"
    )
    fun camera(json: JSONObject): Camera {
        val hls = json.optJSONObject("MEDIA")?.optJSONObject("HLS")
        val live = hls?.optJSONObject("LIVE")
        val archive = json.optJSONObject("ARCHIVE")
        val access = json.optJSONObject("ACCESS")
        val archiveAccess = access?.optJSONObject("ARCHIVE")
        return Camera(json.text("ID"), json.text("UUID"), json.text("NAME"),
            live?.text("LOW_LATENCY").orEmpty().ifBlank { live?.text("MAIN").orEmpty() },
            hls?.text("ARCHIVE").orEmpty(), parseCameraInstant(archive?.text("START_TIME").orEmpty()),
            parseCameraInstant(archive?.text("STOP_TIME").orEmpty()), access?.optJSONObject("LIVE")?.optBoolean("STATUS", false) == true,
            archiveAccess?.optBoolean("STATUS", false) == true, archiveAccess?.text("REASON").orEmpty())
    }
    fun ranges(json: JSONArray, from: Instant, to: Instant): List<RecordingRange> {
        val sorted = json.objects().mapNotNull {
            val start = parseCameraInstant(it.text("START_DATE")) ?: return@mapNotNull null
            val end = parseCameraInstant(it.text("STOP_DATE")) ?: return@mapNotNull null
            val clippedStart = maxOf(start, from); val clippedEnd = minOf(end, to)
            if (clippedStart < clippedEnd) RecordingRange(clippedStart, clippedEnd) else null
        }.sortedBy { it.start }
        val result = mutableListOf<RecordingRange>()
        for (range in sorted) {
            val previous = result.lastOrNull()
            if (previous != null && range.start <= previous.end) result[result.lastIndex] = previous.copy(end = maxOf(previous.end, range.end))
            else result.add(range)
        }
        return result
    }
    fun event(json: JSONObject): DoorEvent? {
        val at = parseCameraInstant(json.text("create_date")) ?: return null
        val type = json.text("type"); val params = json.optJSONObject("params")
        val mac = params?.text("mac").orEmpty()
        val title = when (type) { "HANDSET_CALL" -> "Вызов домофона"; "OPEN_API" -> "Открыто из приложения"; "OPEN_HANDSET" -> "Открыто с трубки"; else -> "Событие домофона" }
        val image = json.text("image_link").takeIf { it.isNotBlank() }
        val id = MessageDigest.getInstance("SHA-256").digest("$at|$type|${normalizeMac(mac)}|${image.orEmpty()}".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        return DoorEvent(id, at, type, title, mac, image)
    }
}
