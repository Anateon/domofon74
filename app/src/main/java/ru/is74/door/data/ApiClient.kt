package ru.is74.door.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

internal class ApiClient {
    suspend fun request(
        base: String, path: String, method: String = "GET", query: List<Pair<String, String>> = emptyList(),
        form: List<Pair<String, String>>? = null, json: JSONObject? = null,
        session: Session? = null, deviceId: String, token: String? = session?.token, authV2: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val suffix = query.joinToString("&") { encodeQuery(it.first) + "=" + encodeQuery(it.second) }
        val uri = URI(base + path + if (suffix.isBlank()) "" else "?$suffix")
        require(uri.scheme == "https" && uri.host in setOf("api.is74.ru", "cams.is74.ru", "td-crm.is74.ru") && uri.userInfo == null)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            coroutineContext.ensureActive()
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", if (authV2) "application/json; version=v2" else "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("X-Device-Id", deviceId)
            connection.setRequestProperty("X-Api-User-Id", session?.userId ?: "-1")
            connection.setRequestProperty("X-Api-Profile-Id", session?.profileId?.ifBlank { "null" } ?: "null")
            connection.setRequestProperty("X-Api-Source", "com.intersvyaz.lk")
            connection.setRequestProperty("Platform", "Android")
            connection.setRequestProperty("X-App-Version", "2.21.3-RS-2d9fb554")
            connection.setRequestProperty("User-Agent", "Door74/1.0.0 (Android)")
            connection.setRequestProperty("Authorization", if (token != null) "Bearer $token".trimEnd() else "")
            if (method == "POST") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", if (form != null) "application/x-www-form-urlencoded" else "application/json; charset=UTF-8")
                val payload = (form?.joinToString("&") { encodeQuery(it.first) + "=" + encodeQuery(it.second) } ?: json?.toString().orEmpty()).toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }
            val status = connection.responseCode
            coroutineContext.ensureActive()
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.let { if (connection.contentEncoding == "gzip") GZIPInputStream(it) else it }
                ?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val out = StringBuilder(); val chars = CharArray(8192)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = reader.read(chars); if (count < 0) break
                        out.append(chars, 0, count)
                        if (out.length > 4_000_000) throw ApiException("Ответ сервера слишком большой")
                    }
                    out.toString()
                }.orEmpty()
            if (status !in 200..299) throw ApiException(when (status) {
                401 -> if (authV2) "Не удалось подтвердить вход. Проверьте номер и код" else "Сессия закончилась. Войдите снова"
                403 -> "Нет доступа к этому действию"
                400, 422 -> if (authV2) "Проверьте номер и код подтверждения" else "Сервер не принял запрос"
                429 -> "Слишком много запросов. Попробуйте немного позже"
                in 300..399 -> "Сервер изменил адрес API. Требуется обновление приложения"
                else -> "Сервис временно недоступен ($status)"
            }, status, status == 401 && !authV2)
            raw
        } catch (e: SocketTimeoutException) {
            throw ApiException("Сервер не ответил вовремя. Проверьте подключение")
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw ApiException("Не удалось подключиться. Проверьте интернет")
        } finally {
            connection.disconnect()
        }
    }
}
