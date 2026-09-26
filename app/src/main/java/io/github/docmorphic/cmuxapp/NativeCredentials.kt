package io.github.docmorphic.cmuxapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore-backed storage for the official cmux pairing and account session. */
class NativeCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("native_cmux", Context.MODE_PRIVATE)

    fun load(): JSONObject? {
        val encoded = preferences.getString("state", null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun update(transform: (JSONObject) -> Unit) {
        val value = load() ?: JSONObject()
        transform(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toString().toByteArray()), Base64.NO_WRAP)
        check(preferences.edit().putString("state", encoded).commit()) { "Could not save account" }
    }

    fun clear() = preferences.edit().remove("state").apply()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("cmux_native_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(
                "cmux_native_v1",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }
}

/** The same production Stack project and OTP endpoints used by cmux iOS. */
class NativeAccount(private val store: NativeCredentialStore) {
    private val refreshMutex = Mutex()

    suspend fun sendCode(email: String): Unit = withContext(Dispatchers.IO) {
        require(email.contains('@') && email.length <= 254) { "Enter a valid email address" }
        request("/auth/otp/send-sign-in-code", JSONObject()
            .put("email", email.trim())
            .put("callback_url", "https://cmux.com/auth/callback"))
        Unit
    }

    suspend fun signIn(code: String): Unit = withContext(Dispatchers.IO) {
        require(code.isNotBlank()) { "Enter the code from your email" }
        val result = request("/auth/otp/sign-in", JSONObject().put("code", code.trim()))
        val access = result.getString("access_token")
        val refresh = result.getString("refresh_token")
        store.update { it.put("access_token", access).put("refresh_token", refresh) }
    }

    suspend fun accessToken(): String? = refreshMutex.withLock {
        val state = store.load() ?: return@withLock null
        val current = state.optString("access_token").takeIf { it.isNotBlank() }
        if (current != null && !expiresSoon(current)) return@withLock current
        val refresh = state.optString("refresh_token").takeIf { it.isNotBlank() }
            ?: return@withLock current
        try {
            val newToken = withContext(Dispatchers.IO) { refresh(refresh) }
            store.update { it.put("access_token", newToken) }
            newToken
        } catch (error: InvalidRefreshToken) {
            store.update { it.remove("access_token").remove("refresh_token") }
            null
        }
    }

    fun isSignedIn(): Boolean = store.load()?.optString("refresh_token")?.isNotBlank() == true

    fun signOut() {
        store.update { it.remove("access_token").remove("refresh_token") }
    }

    private fun expiresSoon(token: String): Boolean = runCatching {
        val payload = token.split('.')[1]
        val data = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val expires = JSONObject(String(data, Charsets.UTF_8)).getLong("exp")
        expires <= System.currentTimeMillis() / 1000 + 60
    }.getOrDefault(true)

    private fun refresh(token: String): String {
        val connection = URL("https://api.stack-auth.com/api/v1/auth/oauth/token").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("x-stack-project-id", PROJECT_ID)
            connection.setRequestProperty("x-stack-publishable-client-key", PUBLISHABLE_KEY)
            connection.setRequestProperty("x-stack-access-type", "client")
            val body = mapOf(
                "grant_type" to "refresh_token", "refresh_token" to token,
                "client_id" to PROJECT_ID, "client_secret" to PUBLISHABLE_KEY
            ).entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode == 400 || connection.responseCode == 401) throw InvalidRefreshToken()
            check(connection.responseCode == 200) { "Account refresh failed: HTTP ${connection.responseCode}" }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getString("access_token")
        } finally {
            connection.disconnect()
        }
    }

    private fun request(path: String, body: JSONObject): JSONObject {
        val connection = URL("https://api.stack-auth.com/api/v1$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("x-stack-project-id", PROJECT_ID)
            connection.setRequestProperty("x-stack-publishable-client-key", PUBLISHABLE_KEY)
            connection.setRequestProperty("x-stack-client-version", "swift@1.0.0")
            connection.setRequestProperty("x-stack-access-type", "client")
            connection.setRequestProperty("x-stack-override-error-status", "true")
            connection.setRequestProperty("x-stack-random-nonce", UUID.randomUUID().toString())
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.getHeaderField("x-stack-actual-status")?.toIntOrNull() ?: connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val result = JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty().ifBlank { "{}" })
            if (status !in 200..299) error(result.optString("message").ifBlank { "Account request failed: HTTP $status" })
            return result
        } finally {
            connection.disconnect()
        }
    }

    private class InvalidRefreshToken : Exception()

    private companion object {
        const val PROJECT_ID = "9790718f-14cd-4f7e-824d-eaf527a82b82"
        const val PUBLISHABLE_KEY = "pck_kzj80gx4mh2jrzn1cx6y5e8jk0kwa01vkevh2p9zd4twr"
    }
}
