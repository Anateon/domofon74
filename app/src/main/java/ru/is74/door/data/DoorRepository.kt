package ru.is74.door.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

class DoorRepository(context: Context) {
    private val store = SessionStore(context)
    private val client = ApiClient()
    private val intercomMutex = Mutex()
    private val sessionGuard = Any()
    private var sessionGeneration = 0
    @Volatile private var session: Session? = store.load()
    private val device = store.deviceId()
    fun currentSession(): Session? = session
    fun playerHeaders(): Map<String, String> = session?.let { mapOf("Authorization" to "Bearer ${it.token}") }.orEmpty()
    fun logout() = synchronized(sessionGuard) { sessionGeneration++; session = null; store.clear() }
    private fun signedIn(): Session = session ?: throw ApiException("Войдите в аккаунт", 401, true)
    private suspend fun save(value: Session, expectedGeneration: Int, existingToken: String? = null) = withContext(Dispatchers.IO) {
        val context = coroutineContext
        synchronized(sessionGuard) {
            context.ensureActive()
            if (expectedGeneration != sessionGeneration || (existingToken != null && session?.token != existingToken)) {
                throw CancellationException("Session changed")
            }
            store.save(value)
            session = value
        }
    }
    private fun jsonObject(value: String): JSONObject = try { JSONObject(value) } catch (_: JSONException) { throw ApiException("Не удалось прочитать ответ сервера") }
    private fun jsonArray(value: String): JSONArray = try { JSONArray(value) } catch (_: JSONException) { throw ApiException("Не удалось прочитать ответ сервера") }

    suspend fun requestCode(phone: String): AuthChallenge {
        val normalized = normalizePhone(phone)
        val response = client.request(API, "/mobile/auth/get-confirm", "POST", json = JSONObject().put("deviceId", device).put("phone", normalized), deviceId = device, authV2 = true)
        return ApiParser.challenge(jsonObject(response))
    }
    suspend fun confirmCode(phone: String, code: String): ConfirmedAuth {
        if (code.isBlank() || !code.all(Char::isDigit)) throw ApiException("Введите цифры кода подтверждения")
        val response = client.request(API, "/mobile/auth/check-confirm", "POST", form = listOf("phone" to normalizePhone(phone), "confirmCode" to code), deviceId = device, authV2 = true)
        val confirmed = ApiParser.confirmed(jsonObject(response))
        if (confirmed.authId.isBlank() || confirmed.accounts.isEmpty()) throw ApiException("Для номера не найден доступный договор")
        return confirmed
    }
    suspend fun signIn(authId: String, account: Account): Session {
        val generation = synchronized(sessionGuard) { sessionGeneration }
        val response = client.request(API, "/mobile/auth/get-token", "POST", form = listOf("authId" to authId, "userId" to account.userId, "uniqueDeviceId" to device), deviceId = device, authV2 = true)
        val value = ApiParser.session(jsonObject(response), device)
        try {
            save(value, generation)
        } catch (cancelled: CancellationException) {
            synchronized(sessionGuard) {
                if (sessionGeneration == generation && session?.token == value.token) {
                    session = null
                    store.clear()
                }
            }
            throw cancelled
        }
        // The separate history session is requested lazily: a history outage must not prevent video login.
        return value
    }
    suspend fun doors(): List<Door> {
        val account = signedIn()
        val address = jsonObject(client.request(API, "/user/address", session = account, deviceId = device))
        val building = address.text("BUILDING_ID")
        val result = mutableListOf<Door>()
        for (shared in listOf("0", "1")) {
            var page = 1
            while (true) {
                if (page > 100) throw ApiException("Слишком много домофонов. Не удалось загрузить полный список")
                val query = mutableListOf("pagination" to "1", "pageSize" to "40", "page" to page.toString(), "isShared" to shared)
                if (shared == "0") { query.add("mainFirst" to "1"); if (building.isNotBlank()) query.add("buildingIds" to building) }
                val items = jsonArray(client.request(API, "/domofon/relays", query = query, session = account, deviceId = device)).objects()
                result.addAll(items.map(ApiParser::door).filter { it.id.isNotBlank() })
                if (items.size < 40) break
                page++
            }
        }
        return result.distinctBy { it.id }
    }
    suspend fun camera(door: Door): Camera {
        if (!door.hasVideo || door.entranceUid.isBlank()) throw ApiException("У этого домофона нет камеры")
        val account = signedIn()
        val response = jsonObject(client.request(CAMS, "/api/limited-info-by-uuid", "POST", form = listOf("CAMERA_UUIDS[]" to door.entranceUid), session = account, deviceId = device))
        val items = response.keys().asSequence().mapNotNull { response.optJSONObject(it) }.toList()
        val json = items.firstOrNull { it.text("UUID").equals(door.entranceUid, true) } ?: throw ApiException("Камера не найдена или недоступна")
        return ApiParser.camera(json)
    }
    suspend fun recordings(camera: Camera, from: Instant, to: Instant): List<RecordingRange> {
        if (!camera.canArchive) throw ApiException(camera.archiveReason.ifBlank { "Нет доступа к видеоархиву" }, 403)
        require(from < to)
        val response = client.request(CAMS, "/api/record/interval/list", query = listOf("CAMERA_ID" to camera.id, "START_DATE" to apiTimestamp(from), "STOP_DATE" to apiTimestamp(to)), session = signedIn(), deviceId = device)
        return ApiParser.ranges(jsonArray(response), from, to)
    }
    private suspend fun historySession(forceRefresh: Boolean = false): Session = intercomMutex.withLock {
        val generation = synchronized(sessionGuard) { sessionGeneration }
        val account = signedIn()
        if (!forceRefresh && !account.intercomToken.isNullOrBlank()) return@withLock account
        val response = jsonObject(client.request(TD, "/api/auth-lk", "POST", form = listOf("token" to account.token, "buyerId" to "1"), session = account, token = "", deviceId = device))
        val token = response.text("TOKEN")
        if (token.isBlank()) throw ApiException("Сервер не выдал доступ к истории домофона")
        if (session?.token != account.token) throw ApiException("Сессия изменилась. Войдите снова", 401, true)
        account.copy(intercomToken = token).also { save(it, generation, account.token) }
    }
    suspend fun events(door: Door, from: LocalDate, to: LocalDate): List<DoorEvent> {
        require(from <= to)
        if (normalizeMac(door.mac).isBlank()) throw ApiException("Для этого домофона недоступна привязка событий")
        var account = historySession()
        val start = from.atStartOfDay(CameraZone).toInstant()
        val end = to.plusDays(1).atStartOfDay(CameraZone).toInstant()
        val result = mutableListOf<DoorEvent>()
        var page = 1; var received = 0; var refreshed = false
        while (true) {
            if (page > 100) throw ApiException("Слишком много событий за этот период. Выберите один день")
            val query = listOf("from" to from.minusDays(1).toString(), "to" to to.plusDays(1).toString(), "page" to page.toString(), "perPage" to "60")
            val response = try {
                jsonObject(client.request(TD, "/api/user/history", query = query, session = account, token = account.intercomToken, deviceId = device))
            } catch (e: ApiException) {
                if (e.status == 401 && !refreshed) { account = historySession(forceRefresh = true); refreshed = true; continue }
                if (e.status == 401) throw ApiException("Не удалось получить доступ к истории домофона. Попробуйте обновить её позже", 401)
                throw e
            }
            val rows = response.optJSONArray("data")?.objects() ?: throw ApiException("Не удалось прочитать историю домофона")
            received += rows.size
            result.addAll(rows.mapNotNull(ApiParser::event).filter { normalizeMac(it.mac) == normalizeMac(door.mac) && it.at >= start && it.at < end })
            val count = response.optInt("count", -1)
            if (rows.isEmpty() || (count >= 0 && received >= count) || (count < 0 && rows.size < 60)) break
            page++
        }
        return result.distinctBy { it.id }.sortedByDescending { it.at }
    }
    suspend fun openDoor(door: Door) {
        if (!door.canOpen) throw ApiException("Этот домофон сейчас недоступен")
        require(door.id.matches(Regex("[0-9]+"))) { "Некорректный идентификатор домофона" }
        val response = jsonObject(client.request(API, "/domofon/relays/${door.id}/open", "POST", query = listOf("from" to "app"), session = signedIn(), deviceId = device))
        if (!response.has("statusCode") || response.optInt("statusCode", -1) != 0) throw ApiException("Домофон не подтвердил открытие. Попробуйте позже")
    }
    companion object {
        private const val API = "https://api.is74.ru"
        private const val CAMS = "https://cams.is74.ru"
        private const val TD = "https://td-crm.is74.ru"
        fun normalizePhone(phone: String): String {
            val digits = phone.filter(Char::isDigit)
            val normalized = if (digits.length == 11 && digits.first() in "78") digits.drop(1) else digits
            if (normalized.length != 10) throw ApiException("Введите российский номер телефона из 10 цифр после +7")
            return normalized
        }
    }
}
