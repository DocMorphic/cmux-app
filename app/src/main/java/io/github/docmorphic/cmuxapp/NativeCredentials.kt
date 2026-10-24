package io.github.docmorphic.cmuxapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.KeyStoreException
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore-backed storage for the official cmux pairing and account session. */
class NativeCredentialStore private constructor(context: Context, storageName: String,
    private val keyAlias: String, private val keyProviderOverride: (() -> SecretKey)?) {
    constructor(context: Context, storageName: String = "native_cmux") : this(context, storageName, "cmux_native_v1", null)
    private val applicationContext = context.applicationContext
    private val primaryAccount = storageName == "native_cmux"
    // Team admission is called both inside credential transactions and before
    // credential reads. Share their monitor to avoid reversing two lock orders.
    internal val accountStateLock: Any get() = storageLock
    private val preferences = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val documentKey = context.applicationInfo.dataDir + "/" + storageName
    private val readStatus = synchronized(storageLock) {
        readStates.getOrPut(documentKey) { kotlinx.coroutines.flow.MutableStateFlow(NativeCredentialReadStatus.UNCHECKED) }
    }
    /** An unreadable encrypted document is distinct from an absent document. */
    internal val readability: kotlinx.coroutines.flow.StateFlow<NativeCredentialReadStatus> get() = readStatus
    private val changeState = synchronized(storageLock) {
        changes.getOrPut(context.applicationInfo.dataDir + "/" + storageName) { kotlinx.coroutines.flow.MutableStateFlow(0L) }
    }
    internal val revisions: kotlinx.coroutines.flow.StateFlow<Long> get() = changeState
    // Background workers construct separate account/store objects for this same document.
    internal val authWork = synchronized(storageLock) {
        accountWork.getOrPut(context.applicationInfo.dataDir + "/" + storageName) { NativeAuthWork() }
    }

    data class PairedMac(val code: String, val deviceId: String, val name: String, val instanceTag: String? = null,
                         val accountUserId: String? = null, val accountTeamId: String? = null, val stableOrigin: String? = null,
                         val previousOrigins: Set<String> = emptySet(), val ticketRevision: String? = null,
                         val nativeRouteCode: String? = null) {
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

    internal fun visiblePairedMacs(): List<PairedMac> = synchronized(storageLock) {
        val state = load()
        val hidden = NativeComputerVisibility.hiddenOrigins(state)
        NativeComputerVisibility.saved(state).filterNot { NativeComputerVisibility.isHidden(hidden, it) }
    }

    internal fun retainNativeDirectory(team: NativeTeamScope, directory: NativeComputersState, permits: () -> Boolean): Boolean {
        if (!permits()) return false
        return synchronized(storageLock) {
            val state = load() ?: return@synchronized false
            if (!NativeDirectoryRouteUpgrade.retain(state, team, directory)) return@synchronized false
            save(state)
            true
        }
    }

    internal fun setComputerVisible(login: String?, mac: PairedMac, visible: Boolean, permits: () -> Boolean): Boolean = synchronized(storageLock) {
        val state = load() ?: return@synchronized false
        if (!NativeComputerVisibility.setVisible(state, login, mac, visible, permits)) return@synchronized false
        save(state)
        true
    }

    fun rememberMac(code: String, deviceId: String, name: String, instanceTag: String? = null, expected: PairedMac? = null) = update { state ->
        NativePairingPersistence.remember(state, PairedMac(code, deviceId, name, instanceTag), expected = expected)
    }

    internal fun rememberAuthenticatedMac(incoming: PairedMac, team: NativeTeamScope, expected: PairedMac? = null,
                                         permits: () -> Boolean): PairedMac {
        check(permits()) { "Account or team changed. Reconnect to the Mac." }
        var remembered: PairedMac? = null
        update { state -> remembered = NativePairingPersistence.remember(state, incoming, team, expected) }
        // Keep the account/team lock outside the credential transaction, like grant storage.
        check(permits()) { "Account or team changed. Reconnect to the Mac." }
        return checkNotNull(remembered)
    }

    internal fun refreshAuthenticatedMac(incoming: PairedMac, team: NativeTeamScope, expected: PairedMac,
                                        permits: () -> Boolean) {
        check(permits()) { "Account or team changed. Reconnect to the Mac." }
        update { state -> NativePairingPersistence.refresh(state, incoming, team, expected) }
        check(permits()) { "Account or team changed. Reconnect to the Mac." }
    }

    internal fun rememberAttachTicket(team: NativeTeamScope, expected: PairedMac, ticket: MobileAttachTicket,
                                     accountEmail: String?, permits: () -> Boolean): PairedMac {
        check(permits()) { "Account or team changed" }
        var result: PairedMac? = null
        update { state -> result = NativeAttachTicketStore.install(state, team, expected, ticket, accountEmail) }
        check(permits()) { "Account or team changed" }
        return checkNotNull(result)
    }

    internal fun attachTicket(team: NativeTeamScope, mac: PairedMac): MobileAttachTicketContext? =
        NativeAttachTicketStore.read(load(), team, mac)

    internal fun rememberAuthenticatedTicketMac(incoming: PairedMac, team: NativeTeamScope, ticket: MobileAttachTicket,
        email: String?, expected: PairedMac?, permits: () -> Boolean): PairedMac {
        check(permits()) { "Account or pairing changed" }
        var result: PairedMac? = null
        update { state ->
            val row = NativePairingPersistence.remember(state, incoming, team, expected, preferIncomingRoute = true)
            result = NativeAttachTicketStore.install(state, team, row, ticket, email)
        }
        check(permits()) { "Account or pairing changed" }
        return checkNotNull(result)
    }

    fun forgetMac(code: String) = update { NativePairingRecords.removeLocal(it, code) }

    internal fun forgetMac(code: String, team: NativeTeamScope, permits: () -> Boolean) {
        check(permits()) { "Account or team changed" }
        update { NativePairingRecords.removeLocal(it, code, team) }
    }

    internal fun forgetLocalPairing(action: NativeLocalPairingRemoval, permits: () -> Boolean) {
        check(permits()) { "Account or pairing changed. Reopen Settings." }
        update { action.remove(it, permits) }
    }

    internal fun forgetCapturedNativeMac(team: NativeTeamScope, captured: List<PairedMac>, target: NativeComputerTarget) =
        update { state ->
            NativeComputerForgetLocal.remove(state, team, captured)
            TailscaleGrantStore.removeComputer(state, team, target.deviceId, target.buildTag)
        }

    // Existing read-only consumers may lack a snapshot while storage is unavailable.
    // They can observe readability; mutations must use the strict read below.
    fun load(): JSONObject? = synchronized(storageLock) {
        try { readState() } catch (_: NativeCredentialReadException) { null }
    }

    /** Absence is usable; unavailable data must never become an empty mutation baseline. */
    internal fun requireReadable(): JSONObject? = synchronized(storageLock) { readState() }

    /** Tab preferences share the credential transaction so retired logins cannot recreate account state. */
    internal fun lastWorkspaceTab(login: String, key: NativeWorkspaceTabKey): NativeWorkspaceTab? = synchronized(storageLock) {
        val state = load() ?: return@synchronized null
        if (login.isBlank() || state.optString("task_session") != login || state.optString("refresh_token").isBlank()) return@synchronized null
        NativeWorkspaceLastTabs(state.optJSONObject(NativeWorkspaceLastTabs.STORAGE_KEY)).get(key)
    }

    internal fun rememberWorkspaceTab(login: String, key: NativeWorkspaceTabKey, tab: NativeWorkspaceTab): Boolean = synchronized(storageLock) {
        val state = load() ?: return@synchronized false
        if (login.isBlank() || state.optString("task_session") != login || state.optString("refresh_token").isBlank()) return@synchronized false
        val tabs = NativeWorkspaceLastTabs(state.optJSONObject(NativeWorkspaceLastTabs.STORAGE_KEY))
        if (!tabs.set(key, tab)) return@synchronized false
        update { it.put(NativeWorkspaceLastTabs.STORAGE_KEY, tabs.json()) }
        true
    }

    internal fun forgetWorkspaceTab(login: String, key: NativeWorkspaceTabKey): Boolean = synchronized(storageLock) {
        val state = load() ?: return@synchronized false
        if (login.isBlank() || state.optString("task_session") != login || state.optString("refresh_token").isBlank()) return@synchronized false
        val tabs = NativeWorkspaceLastTabs(state.optJSONObject(NativeWorkspaceLastTabs.STORAGE_KEY))
        if (!tabs.remove(key)) return@synchronized false
        update { it.put(NativeWorkspaceLastTabs.STORAGE_KEY, tabs.json()) }
        true
    }

    /** A login incarnation, independent of access-token refresh and Activity recreation. */
    internal fun taskSession(): String? = synchronized(storageLock) {
        taskSessionFrom(load())
    }
    /** Destructive background reconciliation must distinguish unknown from signed out. */
    internal fun requireReadableTaskSession(): String? = synchronized(storageLock) { taskSessionFrom(readState()) }
    private fun taskSessionFrom(state: JSONObject?): String? {
        if (state == null || NativeSignInCompletion.isPending(state) || state.optString("refresh_token").isBlank()) return null
        return state.optString("task_session").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also { session ->
            update { it.put("task_session", session) }
        }
    }

    private fun readState(): JSONObject? {
        return try {
            val encoded = preferences.getString("state", null) ?: run {
                readStatus.value = NativeCredentialReadStatus.EMPTY
                return null
            }
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(createIfMissing = false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)).also {
                readStatus.value = NativeCredentialReadStatus.AVAILABLE
            }
        } catch (_: Exception) {
            // Never delete or replace persisted state on a read verdict, including
            // temporarily unavailable Keystore, missing keys or malformed data.
            // Parser exceptions can contain decrypted text: do not retain a cause.
            readStatus.value = NativeCredentialReadStatus.UNAVAILABLE
            throw NativeCredentialReadException()
        }
    }

    fun update(transform: (JSONObject) -> Unit): Unit = synchronized(storageLock) {
        val value = readState() ?: JSONObject()
        transform(value)
        save(value)
    }

    /** The maintenance ledger already committed this projection; do not re-import deliberately retired keys. */
    internal fun updateFromMaintenance(transform: (JSONObject) -> Unit): Unit = synchronized(storageLock) {
        val value = readState() ?: JSONObject()
        transform(value)
        save(value, preserveHelpers = false)
    }

    internal fun recordMacSeen(login: String?, mac: PairedMac, time: Long, permits: () -> Boolean): Unit = synchronized(storageLock) {
        val value = load() ?: return@synchronized
        if (NativeMacLastSeen.record(value, login, mac, time, permits)) save(value)
    }

    internal fun recordMacVersions(owner: NativeTeamScope, permits: () -> Boolean,
        versions: () -> Map<NativeMacIdentity, String?>): Unit = synchronized(storageLock) {
        val value = load() ?: return@synchronized
        if (!permits()) return@synchronized
        if (NativeMacVersionHistory.record(value, owner, versions(), permits)) save(value)
    }

    /** Caller holds storageLock. */
    private fun save(value: JSONObject, preserveHelpers: Boolean = true) {
        if (primaryAccount && preserveHelpers) PhoneHelperMaintenanceWork.preserve(applicationContext, readState())
        NativeAttachTicketStore.prune(value)
        NativeAccountDeletionRecord.prune(value)
        NativeAccountProfileCache.prune(value)
        NativeNotificationDismissOutbox(value).prune()
        NativeMacLastSeen.prune(value)
        NativeMacVersionHistory.prune(value)
        NativeComputerVisibility.prune(value)
        PhonePushKeyState(value).prune()
        PhonePushHelperState(value).prune()
        PhoneHelperEnrollmentState(value).prune()
        PhonePushInbox(value).prune()
        PhoneFcmQueue(value).prune()
        PhoneReplyActions(value).prune()
        PhoneReplyOutbox(value).prune()
        if (primaryAccount && preserveHelpers) PhoneHelperMaintenanceWork.preserve(applicationContext, value, stageRetirement = true)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(createIfMissing = true))
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toString().toByteArray()), Base64.NO_WRAP)
        check(preferences.edit().putString("state", encoded).commit()) { "Could not save account" }
        readStatus.value = NativeCredentialReadStatus.AVAILABLE
        changeState.value++
    }

    fun clear(): Unit = synchronized(storageLock) {
        if (primaryAccount) {
            PhoneHelperMaintenanceWork.preserve(applicationContext, readState())
            PhoneHelperMaintenanceWork.preserve(applicationContext, JSONObject(), stageRetirement = true)
        }
        check(preferences.edit().remove("state").commit()) { "Could not clear account" }
        readStatus.value = NativeCredentialReadStatus.EMPTY
        changeState.value++
    }

    companion object {
        // Account refresh, the background service, and draft saves share the
        // Keystore key. Serialize read-modify-write and initial key creation.
        private val storageLock = Any()
        private val changes = mutableMapOf<String, kotlinx.coroutines.flow.MutableStateFlow<Long>>()
        private val accountWork = mutableMapOf<String, NativeAuthWork>()
        private val readStates = mutableMapOf<String, kotlinx.coroutines.flow.MutableStateFlow<NativeCredentialReadStatus>>()
        internal fun withKeyProvider(context: Context, storageName: String, keyAlias: String,
            provider: (() -> SecretKey)? = null) = NativeCredentialStore(context, storageName, keyAlias, provider)
    }

    private fun key(createIfMissing: Boolean): SecretKey {
        keyProviderOverride?.let { return it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        if (!createIfMissing) throw KeyStoreException("Saved account key is unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }
}

/** The same production Stack project and OTP endpoints used by cmux iOS. */
class NativeAccount private constructor(private val store: NativeCredentialStore, private val refreshOverride: ((String) -> String)?,
    private val oauthClient: NativeOAuthClient, internal val oauthBrowser: NativeOAuthBrowser,
    private val authWork: NativeAuthWork = store.authWork) {
    constructor(store: NativeCredentialStore, refreshOverride: ((String) -> String)? = null) :
        this(store, refreshOverride, NativeOAuthClient(PROJECT_ID, PUBLISHABLE_KEY), NativeOAuthBrowser())
    internal constructor(store: NativeCredentialStore, oauthClientOverride: NativeOAuthClient, oauthBrowser: NativeOAuthBrowser,
        refreshOverride: ((String) -> String)? = null, authWorkOverride: NativeAuthWork? = null) :
        this(store, refreshOverride, oauthClientOverride, oauthBrowser, authWorkOverride ?: store.authWork)
    private val refreshMutex = Mutex()
    internal var onSignedIn: suspend (String, NativeSignInUser) -> Unit = { _, _ -> }
    private val completion = NativeSignInCompletion(store::load, store::update, oauthClient::currentUser,
        oauthClient.accountEnvironment, store.accountStateLock, onPublished = { login, user -> onSignedIn(login, user) },
        refreshAccess = oauthClient::refreshAccess, work = authWork)
    private val emailSignIn = NativeEmailSignIn(::request) { _, _ -> error("Sign-in completion receipt required") }
    private val oauthSignIn = NativeOAuthSignIn(oauthClient::prepare, oauthBrowser::authorize, oauthClient::exchange,
        publish = { _, _ -> error("Sign-in completion receipt required") })

    suspend fun sendCode(email: String) {
        oauthSignIn.clear()
        authWork.run(NativeAuthPhase.SEND_CODE, 60_000, admission = NativeAuthWork.Admission.EXCLUSIVE) {
            withContext(Dispatchers.IO) { emailSignIn.sendCode(email) }
        }
    }

    suspend fun signIn(code: String) {
        oauthSignIn.clear()
        completion.signIn { write -> withContext(Dispatchers.IO) { emailSignIn.signIn(code, write) } }
    }
    internal suspend fun signInWithProvider(provider: NativeOAuthProvider) {
        emailSignIn.clear()
        completion.signIn(exchangeTimeoutMillis = 300_000, exchangePhase = NativeAuthPhase.OAUTH_EXCHANGE) { write -> oauthSignIn.signIn(provider, write) }
    }

    private val emailRecovery = NativeEmailRecoveryClient()
    suspend fun requestEmailVerification(email: String) = emailRecovery.verification(email)
    suspend fun requestBillingRecovery(email: String) = emailRecovery.billing(email)

    internal fun cancelPendingSignIn() { completion.cancelPending(); emailSignIn.clear(); oauthSignIn.clear() }
    internal suspend fun restorePendingSignIn() = completion.restorePending()
    internal fun supersedeTimedOutAuthPhases() = authWork.supersedeTimedOutPhases()

    suspend fun accessToken(forceRefresh: Boolean = false): String? = authWork.run(
        if (forceRefresh) NativeAuthPhase.FORCE_REFRESH else NativeAuthPhase.REFRESH, 60_000) {
        withContext(Dispatchers.IO) {
            refreshMutex.withLock {
                val state = store.load() ?: return@withLock null
                if (NativeSignInCompletion.isPending(state)) return@withLock null
                val current = state.optString("access_token").takeIf { it.isNotBlank() }
                if (current != null && !forceRefresh && !expiresSoon(current)) return@withLock current
                val refresh = state.optString("refresh_token").takeIf { it.isNotBlank() }
                    ?: return@withLock current
                val session = store.taskSession() ?: return@withLock null
                val writer = kotlinx.coroutines.currentCoroutineContext()
                try {
                    val newToken = withContext(Dispatchers.IO) { refreshOverride?.invoke(refresh) ?: refresh(refresh) }
                    writer.ensureActive()
                    var applied = false
                    store.update {
                        writer.ensureActive()
                        if (it.optString("refresh_token") == refresh && it.optString("task_session") == session) {
                            it.put("access_token", newToken); applied = true
                        }
                    }
                    newToken.takeIf { applied }
                } catch (error: InvalidRefreshToken) {
                    store.update {
                        writer.ensureActive()
                        if (it.optString("refresh_token") == refresh && it.optString("task_session") == session) {
                            it.put("access_token", "").put("refresh_token", "")
                            it.remove("task_session"); it.remove("task_drafts")
                        }
                    }
                    null
                }
            }
        }
    }

    fun isSignedIn(): Boolean = store.load()?.let {
        !NativeSignInCompletion.isPending(it) && it.optString("refresh_token").isNotBlank()
    } == true

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

    internal suspend fun deletionCredentials(login: String): NativeDeletionCredentials? {
        if (store.taskSession() != login) throw kotlinx.coroutines.CancellationException("Account changed")
        val access = accessToken() ?: return null
        val state = store.load() ?: return null
        if (state.optString("task_session") != login || state.optString("access_token") != access)
            throw kotlinx.coroutines.CancellationException("Account changed")
        return NativeDeletionCredentials(access, state.optString("refresh_token"))
    }

    /** Same captured account before token refresh, after it, and after the web exchange. */
    internal suspend fun webSessionSnapshot(login: String): NativeWebSessionSnapshot? {
        if (store.taskSession() != login) throw kotlinx.coroutines.CancellationException("Account changed")
        val access = accessToken() ?: return null
        val state = store.load() ?: return null
        if (state.optString("task_session") != login || state.optString("access_token") != access)
            throw kotlinx.coroutines.CancellationException("Account changed")
        val refresh = state.optString("refresh_token").takeIf { it.isNotBlank() } ?: return null
        return NativeWebSessionSnapshot(login, access, refresh)
    }

    internal fun isWebSessionCurrent(snapshot: NativeWebSessionSnapshot): Boolean {
        val state = store.load() ?: return false
        return state.optString("task_session") == snapshot.login &&
            state.optString("refresh_token") == snapshot.refreshToken &&
            state.optString("access_token") == snapshot.accessToken
    }

    fun signOut(expectedLogin: String? = null): Boolean {
        if (expectedLogin == null) {
            completion.cancelPending()
            authWork.cancelAll()
        }
        val applied = completion.clearIf(includePublished = true) {
            var retired = false
            store.update {
                if (expectedLogin != null && it.optString("task_session") != expectedLogin) return@update
                it.put("access_token", "").put("refresh_token", "")
                it.remove("task_session"); it.remove("task_drafts")
                it.remove(NativeSignInCompletion.PENDING)
                retired = true
            }
            retired
        }
        if (applied) { emailSignIn.clear(); oauthSignIn.clear(); TaskDraftRepository.clearMemory() }
        return applied
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
            val status = (connection.getHeaderField("x-hexclave-actual-status")
                ?: connection.getHeaderField("x-stack-actual-status"))?.toIntOrNull() ?: httpStatus
            // Stack can carry an application error in an HTTP 200 response.
            val stream = if (httpStatus in 200..299) connection.inputStream else connection.errorStream
            return nativeAccountResponse(status, connection.getHeaderField("x-hexclave-known-error")
                ?: connection.getHeaderField("x-stack-known-error"), stream?.bufferedReader()?.use { it.readText() }.orEmpty())
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
