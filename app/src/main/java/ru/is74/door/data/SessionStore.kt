package ru.is74.door.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The session payload is AES-GCM ciphertext; the random installation ID is stored separately. */
class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("private_session", Context.MODE_PRIVATE)
    private val alias = "is74.session.aes.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun deviceId(): String {
        prefs.getString("installation", null)?.let { return it }
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }.also { prefs.edit().putString("installation", it).commit() }
    }
    fun load(): Session? {
        val encoded = prefs.getString("session", null) ?: return null
        return runCatching {
            val packet = Base64.decode(encoded, Base64.NO_WRAP)
            require(packet.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packet.copyOfRange(0, 12)))
            val obj = JSONObject(String(cipher.doFinal(packet.copyOfRange(12, packet.size)), Charsets.UTF_8))
            Session(obj.getString("token"), obj.getString("userId"), obj.getString("profileId"), obj.getString("deviceId"), obj.text("intercomToken").takeIf { it.isNotBlank() })
        }.getOrElse { clear(); null }
    }
    fun save(session: Session) {
        val json = JSONObject().put("token", session.token).put("userId", session.userId).put("profileId", session.profileId)
            .put("deviceId", session.deviceId).put("intercomToken", session.intercomToken ?: JSONObject.NULL)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("session", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit()) { "Не удалось сохранить вход" }
    }
    fun clear() { prefs.edit().remove("session").commit() }
}
