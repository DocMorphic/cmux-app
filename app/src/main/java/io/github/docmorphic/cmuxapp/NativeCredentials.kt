package io.github.docmorphic.cmuxapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
    private val fcmTokenDocument = storageName == "phone_fcm_tokens"
    // Team admission is called both inside credential transactions and before
    // credential reads. Share their monitor to avoid reversing two lock orders.
    internal val accountStateLock: Any get() = storageLock
    private val preferences = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val documentKey = context.applicationInfo.dataDir + "/" + storageName
    internal val authSessionEpoch = synchronized(storageLock) {
        authEpochs.getOrPut(documentKey) { java.util.concurrent.atomic.AtomicLong(0) }
    }
    private val readStatus = synchronized(storageLock) {
        readStates.getOrPut(documentKey) { kotlinx.coroutines.flow.MutableStateFlow(NativeCredentialReadStatus.UNCHECKED) }
    }
    internal fun authTokenHint(environment: String, fallback: Boolean): Boolean = synchronized(storageLock) {
        runCatching { if (localLogoutPending) false else preferences.getBoolean(authHintKey(environment), fallback) }.getOrDefault(fallback)
    }
    internal fun setAuthTokenHint(environment: String, value: Boolean) {
        synchronized(storageLock) { preferences.edit().putBoolean(authHintKey(environment), value && !localLogoutPending).apply() }
    }
    private fun authHintKey(environment: String) = "auth_tokens_" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(environment.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    internal val localLogoutPending: Boolean get() = try { preferences.getBoolean(NativeLocalLogout.PENDING, false) }
        catch (_: Exception) { throw NativeCredentialReadException() }
    /** Persist intent before touching Keystore. Every credential mutation must replay it first. */
    internal fun requestLocalLogout() = synchronized(storageLock) {
        check(preferences.edit().putBoolean(NativeLocalLogout.PENDING, true).commit()) { "Could not save local logout" }
        authSessionEpoch.incrementAndGet()
        refreshOwnersChanged(null)
        changeState.value++
    }
    private fun pendingFcmCallback(): String? = if (!fcmTokenDocument) null else PhoneFcmTokenCallback.pending(preferences)
    internal val hasPendingFcmCallback: Boolean get() = pendingFcmCallback() != null
    internal fun recordFcmCallback() { check(fcmTokenDocument);PhoneFcmTokenCallback.record(preferences) }
    /** Only the logout capture path bypasses pending-intent projection, under the account transaction. */
    internal fun capturedLogoutSession(): NativeCapturedSession = synchronized(storageLock) { NativeCapturedSession.from(readEncryptedState()) }
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
    internal fun refreshCoordinator(environment:String):NativeTokenRefresh = synchronized(storageLock) {
        refreshWork.getOrPut(documentKey to environment) { NativeTokenRefresh(now=android.os.SystemClock::elapsedRealtime) }
    }
    private fun refreshOwnersChanged(root:JSONObject?,except:NativeTokenRefresh?=null) {
        val owner=NativeRefreshOwner.stored(root,authSessionEpoch.get())
        refreshWork.filterKeys { it.first==documentKey }.values.filter { it!==except }.forEach { it.ownerChanged(owner) }
    }
    internal fun refreshView():NativeRefreshView=synchronized(storageLock) {
        val root=readState()
        NativeRefreshView(NativeRefreshOwner.from(root,authSessionEpoch.get()),root?.optString("access_token")?.takeIf(String::isNotBlank))
    }
    internal fun pendingRefreshView(receipt:String,epoch:Long):NativeRefreshView=synchronized(storageLock) {
        val root=readState()
        if(authSessionEpoch.get()!=epoch || root?.opt(NativeSignInCompletion.PENDING)!=receipt || root.opt("task_session")!=receipt)
            return@synchronized NativeRefreshView(null,null)
        NativeRefreshView(NativeRefreshOwner.stored(root,epoch),root.opt("access_token") as? String)
    }
    internal fun applyRefresh(owner:NativeRefreshOwner,access:String?,fence:((()->Boolean)->Boolean),coordinator:NativeTokenRefresh):Boolean=synchronized(storageLock) {
        val root=readState() ?: return@synchronized false
        if((if(owner.pending)NativeRefreshOwner.stored(root,authSessionEpoch.get()) else NativeRefreshOwner.from(root,authSessionEpoch.get()))!=owner)
            return@synchronized false
        if(access==null) {
            root.put("access_token", "").put("refresh_token", "");root.remove("task_session");root.remove("task_drafts")
            if(owner.pending){root.remove(NativeSignInCompletion.PENDING);NativeAccountProfileCache.prune(root)}
        } else root.put("access_token",access)
        save(root,refreshOwner=coordinator,commitFence={ action ->
            if(authSessionEpoch.get()!=owner.epoch)throw kotlinx.coroutines.CancellationException("Account changed")
            fence(action)
        })
        true
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

    private fun readState(): JSONObject? = try {
        val value = readEncryptedState()
        val retiring = localLogoutPending
        val callback = pendingFcmCallback()
        if (retiring || callback != null) {
            if (value == null) {
                val edit = preferences.edit()
                if (retiring) edit.remove(NativeLocalLogout.PENDING)
                if (callback != null) edit.putString(PhoneFcmTokenCallback.APPLIED,callback)
                if (fcmTokenDocument) PhoneFcmTokenCallback.commit(preferences,callback) {
                    check(edit.commit()) { "Could not acknowledge saved intent" }
                } else check(edit.commit()) { "Could not acknowledge saved intent" }
                changeState.value++
            } else {
                if (retiring) NativeLocalLogout.retire(value)
                if (callback != null) PhoneFcmTokenState(value).providerChanged(callback)
                save(value, retiringAuth = retiring, appliedFcmCallback = callback)
            }
        }
        value
    } catch (_: Exception) {
        readStatus.value = NativeCredentialReadStatus.UNAVAILABLE
        throw NativeCredentialReadException()
    }

    private fun readEncryptedState(): JSONObject? {
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
        val epoch = authSessionEpoch.get()
        val value = readState() ?: JSONObject()
        transform(value)
        if (authSessionEpoch.get() != epoch) throw kotlinx.coroutines.CancellationException("Account changed")
        save(value)
    }

    /** The maintenance ledger already committed this projection; do not re-import deliberately retired keys. */
    internal fun updateFromMaintenance(transform: (JSONObject) -> Unit): Unit = synchronized(storageLock) {
        val epoch = authSessionEpoch.get()
        val value = readState() ?: JSONObject()
        transform(value)
        if (authSessionEpoch.get() != epoch) throw kotlinx.coroutines.CancellationException("Account changed")
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
    private fun save(value: JSONObject, preserveHelpers: Boolean = true, retiringAuth: Boolean = false, appliedFcmCallback: String? = null,
        commitFence:((()->Boolean)->Boolean)?=null,refreshOwner:NativeTokenRefresh?=null) {
        if (!retiringAuth && localLogoutPending) throw NativeCredentialReadException()
        if (pendingFcmCallback() != appliedFcmCallback) throw NativeCredentialReadException()
        if (primaryAccount && preserveHelpers) PhoneHelperMaintenanceWork.preserve(applicationContext,
            if (retiringAuth) readEncryptedState() else readState())
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
        val edit = preferences.edit().putString("state", encoded)
        if (retiringAuth) edit.remove(NativeLocalLogout.PENDING)
        if (appliedFcmCallback != null) edit.putString(PhoneFcmTokenCallback.APPLIED,appliedFcmCallback)
        val commit={ commitFence?.invoke { edit.commit() } ?: edit.commit() }
        if (fcmTokenDocument) PhoneFcmTokenCallback.commit(preferences,appliedFcmCallback) {
            check(commit()) { "Could not save account" }
        } else check(commit()) { "Could not save account" }
        refreshOwnersChanged(value,refreshOwner)
        readStatus.value = NativeCredentialReadStatus.AVAILABLE
        changeState.value++
    }

    fun clear(): Unit = synchronized(storageLock) {
        if (primaryAccount) {
            PhoneHelperMaintenanceWork.preserve(applicationContext, readState())
            PhoneHelperMaintenanceWork.preserve(applicationContext, JSONObject(), stageRetirement = true)
        }
        val edit = preferences.edit().remove("state").remove(NativeLocalLogout.PENDING).remove(PhoneFcmTokenCallback.LATEST).remove(PhoneFcmTokenCallback.APPLIED)
        preferences.all.keys.filter { it.startsWith("auth_tokens_") }.forEach(edit::remove)
        check(edit.commit()) { "Could not clear account" }
        refreshOwnersChanged(null)
        readStatus.value = NativeCredentialReadStatus.EMPTY
        changeState.value++
    }

    companion object {
        // Account refresh, the background service, and draft saves share the
        // Keystore key. Serialize read-modify-write and initial key creation.
        private val storageLock = Any()
        private val changes = mutableMapOf<String, kotlinx.coroutines.flow.MutableStateFlow<Long>>()
        private val accountWork = mutableMapOf<String, NativeAuthWork>()
        private val refreshWork = mutableMapOf<Pair<String,String>,NativeTokenRefresh>()
        private val readStates = mutableMapOf<String, kotlinx.coroutines.flow.MutableStateFlow<NativeCredentialReadStatus>>()
        private val authEpochs = mutableMapOf<String, java.util.concurrent.atomic.AtomicLong>()
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
    private val authWork: NativeAuthWork = store.authWork,
    private val tokenRefreshLeadMillis: Long = kotlin.random.Random.nextLong(60_000,120_001),
    private val tokenNowMillis: () -> Long = System::currentTimeMillis,
    private val refreshCoordinator:NativeTokenRefresh=store.refreshCoordinator(oauthClient.accountEnvironment)) {
    constructor(store: NativeCredentialStore, refreshOverride: ((String) -> String)? = null) :
        this(store, refreshOverride, NativeOAuthClient(PROJECT_ID, PUBLISHABLE_KEY), NativeOAuthBrowser())
    internal constructor(store: NativeCredentialStore, oauthClientOverride: NativeOAuthClient, oauthBrowser: NativeOAuthBrowser,
        refreshOverride: ((String) -> String)? = null, authWorkOverride: NativeAuthWork? = null,
        tokenRefreshLeadMillis: Long = kotlin.random.Random.nextLong(60_000,120_001),
        tokenNowMillis: () -> Long = System::currentTimeMillis, refreshCoordinatorOverride:NativeTokenRefresh?=null) :
        this(store, refreshOverride, oauthClientOverride, oauthBrowser, authWorkOverride ?: store.authWork,
            tokenRefreshLeadMillis, tokenNowMillis, refreshCoordinatorOverride ?: store.refreshCoordinator(oauthClientOverride.accountEnvironment))
    internal var onSignedIn: suspend (String, NativeSignInUser) -> Unit = { _, _ -> }
    internal var onSignedOut: suspend (String?,String?) -> Unit = { _,_ -> }
    internal var signOutTeardownTimeoutMillis: Long = 5_000
    private val completion = NativeSignInCompletion(store::load, store::update, {access->oauthClient.currentUser(access,store.load()?.opt("refresh_token") as? String)},
        oauthClient.accountEnvironment, store.accountStateLock, onPublished = { login, user -> onSignedIn(login, user) },
        refreshAccess = oauthClient::refreshAccess, work = authWork,
        onCredentialsWritten = { sessionRestore.noteCredentialWrite(); store.authSessionEpoch.incrementAndGet() },
        onVerified = { owner, user -> publishedOwner.set(owner); observedAuthEpoch.set(store.authSessionEpoch.get()); sessionRestore.publishedSignIn(user) },
        sessionEpoch = { store.authSessionEpoch.get() },
        fetchPendingUser={receipt,check->pendingUser(receipt,check)})
    private val emailSignIn = NativeEmailSignIn(oauthClient::emailRequest) { _, _ -> error("Sign-in completion receipt required") }
    private val oauthSignIn = NativeOAuthSignIn(oauthClient::prepare, oauthBrowser::authorize, oauthClient::exchange,
        publish = { _, _ -> error("Sign-in completion receipt required") })

    private val restoredOwner = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val publishedOwner = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val observedAuthEpoch = java.util.concurrent.atomic.AtomicLong(0)
    internal val sessionRestore: NativeSessionRestore by lazy {
        val root = store.load()
        val cached = cachedSessionUser(root)
        publishedOwner.set(root?.optString("task_session")?.takeIf(String::isNotBlank))
        observedAuthEpoch.set(store.authSessionEpoch.get())
        val hasTokens = root?.let { it.optString("access_token").isNotBlank() || it.optString("refresh_token").isNotBlank() } == true
        NativeSessionRestore(store.authTokenHint(oauthClient.accountEnvironment, hasTokens), cached,
            setHint = { store.setAuthTokenHint(oauthClient.accountEnvironment, it) },
            loadCachedUser = { cachedSessionUser(store.load()) },
            storageAvailable = { store.readability.value != NativeCredentialReadStatus.UNAVAILABLE },
            hasStoredTokens = {
                // The token probe, including storage and refresh, is phase-bounded.
                val snapshot = store.requireReadable()
                val owner = if (snapshot?.optString("refresh_token")?.isNotBlank() == true && !NativeSignInCompletion.isPending(snapshot))
                    store.requireReadableTaskSession() else snapshot?.optString("task_session")?.takeIf(String::isNotBlank)
                restoredOwner.set(owner)
                val access = accessToken()
                val refresh = store.requireReadable()?.optString("refresh_token")?.takeIf(String::isNotBlank)
                access != null || refresh != null
            },
            currentUser = { savedSessionUser() },
            refreshToken = { store.requireReadable()?.optString("refresh_token")?.takeIf(String::isNotBlank) },
            clearPersisted = { expected -> clearSavedSession(expected) },
            saveCachedUser = { user ->
                withContext(Dispatchers.IO) {
                    store.update { value ->
                        if (value.optString("task_session").takeIf(String::isNotBlank) == restoredOwner.get() &&
                            (value.optString("access_token").isNotBlank() || value.optString("refresh_token").isNotBlank())) {
                            NativeAuthUserCache.write(value, oauthClient.accountEnvironment, user)
                            if(value.optString("refresh_token").isNotBlank() && restoredOwner.get()!=null)
                                NativeAccountProfileCache.writeVerifiedUser(value, checkNotNull(restoredOwner.get()), oauthClient.accountEnvironment, user)
                            publishedOwner.set(restoredOwner.get());observedAuthEpoch.set(store.authSessionEpoch.get())
                        }
                    }
                }
            },
            refreshTeams = { user ->
                val owner = store.requireReadableTaskSession()
                if (owner != null && owner == restoredOwner.get()) onSignedIn(owner, user)
            }, work = authWork, onCleared = ::clearCachedSessionUser,
            externalEpoch = { store.authSessionEpoch.get() }, lock = store.accountStateLock)
    }
    init { sessionRestore }
    private fun cachedSessionUser(root: JSONObject?) = NativeAuthUserCache.read(root, oauthClient.accountEnvironment)
    private fun clearCachedSessionUser() {
        try {
            val root = store.requireReadable() ?: return
            if (root.optString("access_token").isNotBlank() || root.optString("refresh_token").isNotBlank() ||
                !root.has(NativeAuthUserCache.KEY) && !root.has(NativeAccountProfileCache.KEY) && !root.has(NativeTeamSelectionStore.KEY)) return
            store.update { value ->
                if (value.optString("access_token").isBlank() && value.optString("refresh_token").isBlank()) {
                    value.remove(NativeAuthUserCache.KEY);value.remove(NativeAccountProfileCache.KEY);NativeTeamSelectionStore.clear(value)
                }
            }
        } catch (_: NativeCredentialReadException) { /* Keep unreadable encrypted data. */ }
    }
    private suspend fun savedSessionUser(): NativeSignInUser? {
        val root = store.requireReadable() ?: throw NativeSessionUserUnavailable()
        if (NativeSignInCompletion.isPending(root)) {
            val receipt = root.optString("task_session")
            if (completion.hasLivePending(receipt) || authWork.sessionWriteInProgress) throw kotlinx.coroutines.CancellationException("Interactive sign-in owns validation")
            val caller=kotlinx.coroutines.currentCoroutineContext();val epoch=store.authSessionEpoch.get()
            fun check(){caller.ensureActive();if(store.authSessionEpoch.get()!=epoch)throw kotlinx.coroutines.CancellationException("Account changed")}
            val resolved=pendingTokens(receipt,epoch,false,null,::check)
            if(resolved.access==null)throw NativeSessionUserUnavailable()
            val snapshot=pendingSnapshot(receipt,epoch,::check)
            val user = oauthClient.restoredCurrentUser(snapshot.opt("access_token") as? String,snapshot.opt("refresh_token") as? String) {failed->
                pendingTokens(receipt,epoch,true,failed,::check).access
            }
            if (user != null) {
                val caller = kotlinx.coroutines.currentCoroutineContext()
                store.update { value ->
                    caller.ensureActive()
                    if (value.optString("task_session") != receipt || value.optString(NativeSignInCompletion.PENDING) != receipt)
                        throw kotlinx.coroutines.CancellationException("Account changed")
                    NativeAccountProfileCache.writeVerifiedUser(value, receipt, oauthClient.accountEnvironment, user)
                    value.remove(NativeSignInCompletion.PENDING)
                }
            }
            return user
        }
        val token = accessToken() ?: throw NativeSessionUserUnavailable()
        return oauthClient.restoredCurrentUser(token,root.opt("refresh_token") as? String) {failed->accessToken(forceRefresh=true,rejectedAccess=failed)}
    }

    private fun pendingSnapshot(receipt:String,epoch:Long,check:()->Unit):JSONObject=synchronized(store.accountStateLock) {
        check()
        val root=store.requireReadable()
        if(store.authSessionEpoch.get()!=epoch || root?.opt(NativeSignInCompletion.PENDING)!=receipt || root.opt("task_session")!=receipt)
            throw kotlinx.coroutines.CancellationException("Account changed")
        root
    }

    private suspend fun pendingUser(receipt:String,check:()->Unit):NativeSignInUser=withContext(Dispatchers.IO) {
        val epoch=store.authSessionEpoch.get();pendingSnapshot(receipt,epoch,check)
        val resolved=pendingTokens(receipt,epoch,false,null,check)
        if(resolved.access==null)throw NativeSdkUserNotSignedIn()
        // getUser's token preflight establishes presence; sendRequest then reads the store again.
        val snapshot=pendingSnapshot(receipt,epoch,check)
        oauthClient.currentUser(snapshot.opt("access_token") as? String,snapshot.opt("refresh_token") as? String) {failed->
            pendingTokens(receipt,epoch,true,failed,check).access
        }
    }

    private suspend fun pendingTokens(receipt:String,epoch:Long,force:Boolean,rejected:String?,check:()->Unit):NativeSdkTokens =
        withContext(Dispatchers.IO) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive();check()
            fun view()=store.pendingRefreshView(receipt,epoch)
            val result=nativeSdkResolveTokens(force,rejected,
                refresh={view().owner?.refresh},access={view().access},
                fresh={nativeCapturedAccessIsFresh(it,tokenNowMillis(),tokenRefreshLeadMillis)},
                expired={nativeAccessTokenIsExpired(it,tokenNowMillis())}) {refresh,observed->
                val owner=NativeRefreshOwner(receipt,refresh,epoch,pending=true)
                val outcome=try {refreshCoordinator.resolve(owner,observed,authWork,::view,
                    apply={expected,access,fence->store.applyRefresh(expected,access,fence,refreshCoordinator)},
                    exchange={refreshOverride?.invoke(refresh) ?: oauthClient.refreshAccess(refresh)},
                    expired={nativeAccessTokenIsExpired(it,tokenNowMillis())})}
                catch(failure:kotlinx.coroutines.CancellationException){NativeRefreshResult.Cancelled}
                when(outcome) {
                    is NativeRefreshResult.Success->NativeSdkTokens(refresh,outcome.access)
                    NativeRefreshResult.Rejected->NativeSdkTokens(null,null)
                    NativeRefreshResult.Changed->NativeSdkTokens(null,null,NativeSdkTokenFailure.SESSION_CHANGED)
                    NativeRefreshResult.Cancelled->NativeSdkTokens(null,null,NativeSdkTokenFailure.CANCELLED)
                    NativeRefreshResult.TimedOut->NativeSdkTokens(null,null,NativeSdkTokenFailure.TIMED_OUT)
                    is NativeRefreshResult.Failure->{
                        if(outcome.error is NativeCredentialReadException)throw outcome.error
                        NativeSdkTokens(refresh,null)
                    }
                }
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive();check()
            result
        }
    private suspend fun clearSavedSession(expectedRefresh: String?): Boolean = withContext(Dispatchers.IO) {
        var cleared = false
        try { store.update { value ->
            val owner = value.optString("task_session").takeIf(String::isNotBlank)
            val refresh = value.optString("refresh_token").takeIf(String::isNotBlank)
            val access = value.optString("access_token").takeIf(String::isNotBlank)
            val sameOwner = owner == restoredOwner.get() || owner == null && access == null && refresh == null
            if (!sameOwner || expectedRefresh != null && refresh != expectedRefresh) return@update
            value.put("access_token", "").put("refresh_token", "")
            value.remove("task_session"); value.remove("task_drafts"); value.remove(NativeSignInCompletion.PENDING)
            value.remove(NativeAccountProfileCache.KEY); value.remove(NativeAuthUserCache.KEY);NativeTeamSelectionStore.clear(value); cleared = true
        } } catch (_: NativeCredentialReadException) { return@withContext false }
        cleared
    }
    internal suspend fun reconcileStoredSession() = withContext(Dispatchers.IO) { synchronized(store.accountStateLock) {
        val root = try {
            if (store.localLogoutPending) sessionRestore.clear(clearCachedUser = false)
            store.requireReadable()
        } catch (_: NativeCredentialReadException) { return@synchronized }
        if (root?.optString("access_token").isNullOrBlank() && root?.optString("refresh_token").isNullOrBlank()) sessionRestore.clear()
        else if (!NativeSignInCompletion.isPending(root)) {
            val owner = root?.optString("task_session")?.takeIf(String::isNotBlank)
            val user = cachedSessionUser(root)
            val model = sessionRestore
            if (owner != null && user != null && observedAuthEpoch.get() != store.authSessionEpoch.get() &&
                root?.optJSONObject(NativeAuthUserCache.KEY)?.optString("verified_for_login") == owner) {
                observedAuthEpoch.set(store.authSessionEpoch.get());publishedOwner.set(owner);model.publishedSignIn(user)
            }
        }
    } }

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

    suspend fun accessToken(forceRefresh: Boolean = false,rejectedAccess:String?=null): String? = authWork.run(
        if (forceRefresh) NativeAuthPhase.FORCE_REFRESH else NativeAuthPhase.REFRESH, 60_000) {
        withContext(Dispatchers.IO) {
            val (state,epoch) = synchronized(store.accountStateLock) {
                store.requireReadable() to store.authSessionEpoch.get()
            }
            if(state==null) return@withContext null
            if (NativeSignInCompletion.isPending(state)) return@withContext null
            val current = state.optString("access_token").takeIf { it.isNotBlank() }
            val refresh = state.optString("refresh_token").takeIf { it.isNotBlank() }
            fun unchanged() = synchronized(store.accountStateLock) {
                val latest=store.requireReadable()
                store.authSessionEpoch.get()==epoch && latest?.optString("task_session")==state.optString("task_session") &&
                    latest.optString("access_token")==state.optString("access_token") && latest.optString("refresh_token")==state.optString("refresh_token") &&
                    !NativeSignInCompletion.isPending(latest)
            }
            // A later 401 for an old request reuses the token installed by the first waiter.
            if(forceRefresh && refresh!=null && rejectedAccess!=null && current!=null && current!=rejectedAccess &&
                !nativeAccessTokenIsExpired(current,tokenNowMillis()))return@withContext current.takeIf{unchanged()}
            if (refresh == null) return@withContext current?.takeIf {
                !forceRefresh && !nativeAccessTokenIsExpired(it, tokenNowMillis()) && unchanged()
            }
            if (!forceRefresh && nativeCapturedAccessIsFresh(current,tokenNowMillis(),tokenRefreshLeadMillis))
                return@withContext current?.takeIf { unchanged() }
            val session = synchronized(store.accountStateLock) {
                if(unchanged()) store.requireReadableTaskSession() else null
            } ?: return@withContext null
            val owner=NativeRefreshOwner(session,refresh,epoch)
            val result=refreshCoordinator.resolve(owner,current,authWork,store::refreshView,
                apply={ expected,access,fence->store.applyRefresh(expected,access,fence,refreshCoordinator) },
                exchange={ refreshOverride?.invoke(refresh) ?: oauthClient.refreshAccess(refresh) },
                expired={ nativeAccessTokenIsExpired(it,tokenNowMillis()) })
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            return@withContext when(result) {
                is NativeRefreshResult.Success -> result.access.takeIf { store.refreshView()==NativeRefreshView(owner,it) }
                NativeRefreshResult.Rejected,NativeRefreshResult.Changed -> null
                NativeRefreshResult.Cancelled -> throw kotlinx.coroutines.CancellationException("Refresh cancelled")
                else -> {
                    if(result is NativeRefreshResult.Failure && result.error is NativeCredentialReadException)throw result.error
                    val latest=store.refreshView()
                    if (!forceRefresh && latest==NativeRefreshView(owner,current) && !nativeAccessTokenIsExpired(current,tokenNowMillis())) current
                    else when(result) {
                        is NativeRefreshResult.Failure -> throw result.error
                        NativeRefreshResult.TimedOut -> throw NativeSignInFailure(NativeSignInError.TIMED_OUT)
                        else -> null
                    }
                }
            }
        }
    }

    fun isSignedIn(): Boolean = store.load()?.let {
        !NativeSignInCompletion.isPending(it) && it.optString("refresh_token").isNotBlank()
    } == true

    suspend fun userId():String? = sessionRestore.restoreIdentityIfNeeded()?.id?.takeIf{it.isNotEmpty()}

    internal suspend fun deletionCredentials(login: String): NativeDeletionCredentials? {
        return deletionCredentials(deletionOwner(login) ?: throw kotlinx.coroutines.CancellationException("Account changed"))
    }
    /** Published authority survives a definitive refresh rejection long enough to retire that old model. */
    internal fun deletionOwner(login: String): NativeAuthenticatedOwner? = synchronized(store.accountStateLock) {
        val root=store.requireReadable();val identity=sessionRestore.identity() ?: return@synchronized null
        if(NativeSignInCompletion.isPending(root))return@synchronized null
        val epoch=store.authSessionEpoch.get()
        if(publishedOwner.get()!=login || observedAuthEpoch.get()!=epoch)return@synchronized null
        val empty=root?.optString("task_session").isNullOrBlank() && root?.optString("access_token").isNullOrBlank() && root?.optString("refresh_token").isNullOrBlank()
        if(!empty && root?.optString("task_session")!=login)return@synchronized null
        NativeAuthenticatedOwner(identity,login,epoch)
    }
    internal fun isDeletionOwnerCurrent(owner: NativeAuthenticatedOwner): Boolean =
        !authWork.storedSessionCaptureBlocked && runCatching { deletionOwner(owner.login)==owner }.getOrDefault(false)
    /** currentTokens resolves the live pair, including bootstrap, under one absolute capture deadline. */
    internal suspend fun deletionCredentials(owner: NativeAuthenticatedOwner): NativeDeletionCredentials = withContext(Dispatchers.IO) {
        val started=android.os.SystemClock.elapsedRealtime()
        try { kotlinx.coroutines.withTimeout(60_000) {
            // Bootstrap may join token work itself; never register this read-only wait as token work.
            sessionRestore.awaitBootstrapped()
            val remaining=60_000-(android.os.SystemClock.elapsedRealtime()-started)
            if(remaining<=0)throw NativeSignInFailure(NativeSignInError.TIMED_OUT)
            authWork.run(NativeAuthPhase.ACCOUNT_DELETION,remaining,owner.login) {
                fun admitted() { if(!isDeletionOwnerCurrent(owner))throw kotlinx.coroutines.CancellationException("Account changed") }
                repeat(3) {
                    admitted()
                    val before=store.requireReadable()
                    val refresh=before?.optString("refresh_token")?.takeIf(String::isNotBlank)
                        ?: throw NativeSignInFailure(NativeSignInError.UNAUTHORIZED)
                    val access=accessToken()
                    kotlinx.coroutines.currentCoroutineContext().ensureActive();admitted()
                    val after=store.requireReadable()
                    if(access.isNullOrBlank())throw NativeSignInFailure(if(after?.optString("refresh_token").isNullOrBlank())
                        NativeSignInError.UNAUTHORIZED else NativeSignInError.NETWORK_ERROR)
                    if(after?.optString("refresh_token")==refresh)return@run NativeDeletionCredentials(access,refresh)
                }
                throw NativeSignInFailure(NativeSignInError.NETWORK_ERROR)
            }
        } } catch(failure:kotlinx.coroutines.TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive();throw NativeSignInFailure(NativeSignInError.TIMED_OUT)
        }
    }

    private fun storedAuthenticatedSession(): NativeStoredAuthSession = synchronized(store.accountStateLock) {
        val root = store.requireReadable()
        val identity = sessionRestore.identity()
        val epoch = store.authSessionEpoch.get()
        val login = root?.optString("task_session")?.takeIf(String::isNotBlank)
        val owner = if (identity != null && login != null && publishedOwner.get() == login &&
            observedAuthEpoch.get() == epoch && !NativeSignInCompletion.isPending(root))
            NativeAuthenticatedOwner(identity,login,epoch) else null
        NativeStoredAuthSession(owner,root?.optString("access_token")?.takeIf(String::isNotBlank),
            root?.optString("refresh_token")?.takeIf(String::isNotBlank),NativeSignInCompletion.isPending(root))
    }
    private val authenticatedSnapshots by lazy { NativeAuthenticatedSnapshots(::storedAuthenticatedSession,
        { authWork.storedSessionCaptureBlocked },
        { authWork.sessionWriteInProgress || sessionRestore.revalidating.value &&
            (sessionRestore.state.value.authenticated || sessionRestore.state.value.restoring) },
        sessionRestore::awaitBootstrapped, { accessToken() }) }
    internal suspend fun authenticatedSessionSnapshot(): NativeAuthenticatedSessionSnapshot =
        withContext(Dispatchers.IO) { authenticatedSnapshots.session() }
    internal suspend fun authenticatedRefreshTokenSnapshot(): NativeAuthenticatedRefreshSnapshot =
        withContext(Dispatchers.IO) { authenticatedSnapshots.refresh() }
    internal fun isAuthenticatedSessionCurrent(owner: NativeAuthenticatedOwner) = authenticatedSnapshots.isCurrent(owner)

    /** The mobile cookie exchange uses a full pair; refresh-only browser auth has its own contract. */
    internal suspend fun webSessionSnapshot(login: String): NativeWebSessionSnapshot? {
        if (store.taskSession() != login) throw kotlinx.coroutines.CancellationException("Account changed")
        val snapshot = authenticatedSessionSnapshot()
        if (snapshot.owner.login != login) throw kotlinx.coroutines.CancellationException("Account changed")
        return NativeWebSessionSnapshot(login,snapshot.accessToken,snapshot.refreshToken,snapshot.owner)
    }
    internal fun isWebSessionCurrent(snapshot: NativeWebSessionSnapshot): Boolean =
        snapshot.owner?.let { it.login == snapshot.login && isAuthenticatedSessionCurrent(it) } == true

    fun signOut(expectedLogin: String? = null): Boolean = signOutLocal(expectedLogin,false)
    internal fun signOutAndRevoke(expectedLogin: String? = null, expectedOwner: NativeAuthenticatedOwner? = null): Boolean = signOutLocal(expectedLogin,true,expectedOwner)
    internal fun signOutDeletion(owner: NativeAuthenticatedOwner): Boolean = signOutLocal(owner.login,false,owner)
    internal suspend fun awaitSignOutTeardown() = authWork.awaitSignOutTeardown()
    private fun signOutLocal(expectedLogin: String?, revoke: Boolean, expectedOwner: NativeAuthenticatedOwner? = null): Boolean {
        val finishCapture=authWork.beginSignOutCapture()
        try {
        var captured=NativeCapturedSession(null,null)
        if (expectedLogin == null) {
            completion.cancelPending()
            authWork.cancelAll()
        }
        val applied = completion.clearIf(includePublished = true) {
            if(expectedOwner!=null && deletionOwner(expectedOwner.login)!=expectedOwner)return@clearIf false
            if (expectedLogin == null) {
                // User intent survives process death and unavailable encrypted storage.
                // A conditional deletion callback must still prove its captured owner.
                store.requestLocalLogout()
                sessionRestore.clear(clearCachedUser = false)
                if(revoke) captured=try { store.capturedLogoutSession() } catch(_:NativeCredentialReadException) { NativeCapturedSession(null,null) }
                try { store.requireReadable() } catch (_: NativeCredentialReadException) { /* Replay on the next readable transaction. */ }
                true
            } else {
                var retired = false
                store.update {
                    val empty=it.optString("task_session").isBlank() && it.optString("access_token").isBlank() && it.optString("refresh_token").isBlank()
                    if (it.optString("task_session") != expectedLogin && !(expectedOwner!=null && empty)) return@update
                    if(revoke) captured=NativeCapturedSession.from(it)
                    NativeLocalLogout.retire(it)
                    retired = true
                }
                if (retired) { store.authSessionEpoch.incrementAndGet(); sessionRestore.clear() }
                retired
            }
        }
        if (applied) {
            emailSignIn.clear(); oauthSignIn.clear(); TaskDraftRepository.clearMemory()
            if(revoke) authWork.launchSignOutTeardown(signOutTeardownTimeoutMillis) {
                nativeSignOutTeardown(captured,oauthClient::freshCapturedAccess,onSignedOut,oauthClient::revokeCapturedSession)
            }
        }
        return applied
        } finally { finishCapture() }
    }

    internal fun retryLocalLogout() {
        try {
            if (!store.localLogoutPending) return
            sessionRestore.clear(clearCachedUser = false)
            store.requireReadable()
        } catch (_: NativeCredentialReadException) { /* Keep durable intent and ciphertext. */ }
    }

    private fun expiresSoon(token: String): Boolean = !nativeCapturedAccessIsFresh(token,tokenNowMillis(),tokenRefreshLeadMillis)

    internal class InvalidRefreshToken : Exception()

    internal companion object {
        const val PROJECT_ID = "9790718f-14cd-4f7e-824d-eaf527a82b82"
        const val PUBLISHABLE_KEY = "pck_kzj80gx4mh2jrzn1cx6y5e8jk0kwa01vkevh2p9zd4twr"
    }
}
