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
class NativeCredentialStore(context: Context, storageName: String = "native_cmux") {
    private val preferences = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val changeState = synchronized(storageLock) {
        changes.getOrPut(context.applicationInfo.dataDir + "/" + storageName) { kotlinx.coroutines.flow.MutableStateFlow(0L) }
    }
    internal val revisions: kotlinx.coroutines.flow.StateFlow<Long> get() = changeState

    data class PairedMac(val code: String, val deviceId: String, val name: String, val instanceTag: String? = null,
                         val accountUserId: String? = null, val accountTeamId: String? = null, val stableOrigin: String? = null,
                         val previousOrigins: Set<String> = emptySet()) {
        internal val origin = stableOrigin ?: pairingOrigin(code, deviceId, instanceTag)
        internal val origins get() = previousOrigins + origin
        internal fun ownsOrigin(value: String?) = value != null && value in origins
        fun requireMatchingHost(status: JSONObject) {
            require(deviceId.isBlank() || canonicalMacDeviceId(status.optString("mac_device_id")) == canonicalMacDeviceId(deviceId)) {
                "This pairing now reaches a different Mac. Forget it and pair the intended Mac again."
            }
            require(instanceTag == null || status.optString("mac_instance_tag") == instanceTag) {
                "This pairing now reaches a different cmux installation. Pair it again."
            }
        }
    }

    fun pairedMacs(): List<PairedMac> {
        val array = load()?.optJSONArray("pairings") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                NativePairingRecords.decode(item)?.let(::add)
            }
        }
    }

    fun rememberMac(code: String, deviceId: String, name: String, instanceTag: String? = null) = update { state ->
        NativePairingPersistence.remember(state, PairedMac(code, deviceId, name, instanceTag))
    }

    internal fun rememberAuthenticatedMac(incoming: PairedMac, team: NativeTeamScope,
                                         permits: () -> Boolean): PairedMac {
        check(permits()) { "Account or team changed. Reconnect to the Mac." }
        var remembered: PairedMac? = null
        update { state -> remembered = NativePairingPersistence.remember(state, incoming, team) }
        // Keep the account/team lock outside the credential transaction, like grant storage.
        check(permits()) { "Account or team changed. Reconnect to the Mac." }
        return checkNotNull(remembered)
    }

    fun forgetMac(code: String) = update { NativePairingRecords.removeLocal(it, code) }

    internal fun forgetMac(code: String, team: NativeTeamScope, permits: () -> Boolean) {
        check(permits()) { "Account or team changed" }
        update { NativePairingRecords.removeLocal(it, code, team) }
    }

    internal fun forgetCapturedNativeMac(team: NativeTeamScope, captured: List<PairedMac>, target: NativeComputerTarget) =
        update { state ->
            NativeComputerForgetLocal.remove(state, team, captured)
            TailscaleGrantStore.removeComputer(state, team, target.deviceId, target.buildTag)
        }

    fun load(): JSONObject? = synchronized(storageLock) { readState() }

    /** A login incarnation, independent of access-token refresh and Activity recreation. */
    internal fun taskSession(): String? = synchronized(storageLock) {
        val state = load() ?: return@synchronized null
        if (state.optString("refresh_token").isBlank()) return@synchronized null
        state.optString("task_session").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also { session ->
            update { it.put("task_session", session) }
        }
    }

    private fun readState(): JSONObject? {
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

    fun update(transform: (JSONObject) -> Unit): Unit = synchronized(storageLock) {
        val value = load() ?: JSONObject()
        transform(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toString().toByteArray()), Base64.NO_WRAP)
        check(preferences.edit().putString("state", encoded).commit()) { "Could not save account" }
        changeState.value++
    }

    fun clear(): Unit = synchronized(storageLock) { preferences.edit().remove("state").apply(); changeState.value++ }

    companion object {
        // Account refresh, the background service, and draft saves share the
        // Keystore key. Serialize read-modify-write and initial key creation.
        private val storageLock = Any()
        private val changes = mutableMapOf<String, kotlinx.coroutines.flow.MutableStateFlow<Long>>()
    }

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
class NativeAccount(private val store: NativeCredentialStore, private val refreshOverride: ((String) -> String)? = null) {
    private val refreshMutex = Mutex()
    private val emailSignIn = NativeEmailSignIn(::request) { access, refresh ->
        store.update {
            it.put("access_token", access).put("refresh_token", refresh).put("task_session", UUID.randomUUID().toString())
            it.remove("task_drafts")
        }
    }

    suspend fun sendCode(email: String): Unit = withContext(Dispatchers.IO) { emailSignIn.sendCode(email) }

    suspend fun signIn(code: String): Unit = withContext(Dispatchers.IO) { emailSignIn.signIn(code) }

    suspend fun accessToken(forceRefresh: Boolean = false): String? = refreshMutex.withLock {
        val state = store.load() ?: return@withLock null
        val current = state.optString("access_token").takeIf { it.isNotBlank() }
        if (current != null && !forceRefresh && !expiresSoon(current)) return@withLock current
        val refresh = state.optString("refresh_token").takeIf { it.isNotBlank() }
            ?: return@withLock current
        val session = store.taskSession() ?: return@withLock null
        try {
            val newToken = withContext(Dispatchers.IO) { refreshOverride?.invoke(refresh) ?: refresh(refresh) }
            var applied = false
            store.update {
                if (it.optString("refresh_token") == refresh && it.optString("task_session") == session) {
                    it.put("access_token", newToken); applied = true
                }
            }
            newToken.takeIf { applied }
        } catch (error: InvalidRefreshToken) {
            store.update {
                if (it.optString("refresh_token") == refresh && it.optString("task_session") == session) {
                    it.put("access_token", "").put("refresh_token", "")
                    it.remove("task_session"); it.remove("task_drafts")
                }
            }
            null
        }
    }

    fun isSignedIn(): Boolean = store.load()?.optString("refresh_token")?.isNotBlank() == true

    suspend fun userId(): String? = withContext(Dispatchers.IO) {
        val token = accessToken() ?: return@withContext null
        val connection = URL("https://api.stack-auth.com/api/v1/users/me").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("x-stack-project-id", PROJECT_ID)
            connection.setRequestProperty("x-stack-publishable-client-key", PUBLISHABLE_KEY)
            connection.setRequestProperty("x-stack-client-version", "swift@1.0.0")
            connection.setRequestProperty("x-stack-access-type", "client")
            connection.setRequestProperty("x-stack-access-token", token)
            connection.setRequestProperty("x-stack-override-error-status", "true")
            connection.setRequestProperty("x-stack-random-nonce", UUID.randomUUID().toString())
            val status = connection.getHeaderField("x-stack-actual-status")?.toIntOrNull() ?: connection.responseCode
            check(status in 200..299) { "Could not verify your cmux account" }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optString("id").takeIf { it.isNotBlank() }
        } finally { connection.disconnect() }
    }

    fun signOut() {
        emailSignIn.clear()
        store.update {
            it.put("access_token", "").put("refresh_token", "")
            it.remove("task_session"); it.remove("task_drafts")
        }
        TaskDraftRepository.clearMemory()
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
            val httpStatus = connection.responseCode
            val status = connection.getHeaderField("x-stack-actual-status")?.toIntOrNull() ?: httpStatus
            // Stack can carry an application error in an HTTP 200 response.
            val stream = if (httpStatus in 200..299) connection.inputStream else connection.errorStream
            val result = JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty().ifBlank { "{}" })
            if (status !in 200..299) error(result.optString("message").ifBlank { "Account request failed: HTTP $status" })
            return result
        } finally {
            connection.disconnect()
        }
    }

    internal class InvalidRefreshToken : Exception()

    internal companion object {
        const val PROJECT_ID = "9790718f-14cd-4f7e-824d-eaf527a82b82"
        const val PUBLISHABLE_KEY = "pck_kzj80gx4mh2jrzn1cx6y5e8jk0kwa01vkevh2p9zd4twr"
    }
}
