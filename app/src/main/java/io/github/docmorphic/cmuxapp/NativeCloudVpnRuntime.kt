package io.github.docmorphic.cmuxapp

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Shared connections and the active VPN service retain this owner across activity recreation. */
internal class NativeCloudVpnRuntime(context: Context, parent: CoroutineScope,
    private val account: NativeAccount, store: NativeCredentialStore, private val teams: NativeAccountTeams) : AutoCloseable {
    private val application = context.applicationContext
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(Dispatchers.IO + job)
    val platform: NativeCloudVpnPlatform = NativeCloudVpnPlatform(application) { attempt, connected ->
        scope.launch { controller.platformChanged(attempt, connected) }
    }
    private val vpnStore = nativeCloudVpnStore(application)
    private val controller: CloudSystemVpnController = CloudSystemVpnController(scope, vpnStore, platform)
    val state = controller.state
    private val mutableOwner = MutableStateFlow<NativeTeamScope?>(null)
    val owner = mutableOwner.asStateFlow()
    private val mutableFailure = MutableStateFlow<String?>(null)
    val failure = mutableFailure.asStateFlow()
    private val refresh = MutableStateFlow(0)
    init {
        scope.launch {
            var firstBinding = true
            val initialLogin = store.taskSession()
            combine(teams.state, store.revisions, refresh) { state, _, retry ->
                Triple(state.scope?.takeIf(teams::isCurrent), store.taskSession(), retry)
            }.distinctUntilChanged().collectLatest { (next, login, _) ->
                // Initial membership loading is not sign-out. A saved VPN remains
                // dormant until the same login/user/team has been verified.
                if (firstBinding && login != initialLogin) {
                    controller.bind(null); firstBinding = false
                }
                if (firstBinding && next == null && login != null) return@collectLatest
                mutableOwner.value = null
                if (!firstBinding || next == null) controller.bind(null)
                if (next == null) firstBinding = false
                mutableFailure.value = null
                if (next != null) try {
                    val access = nativeCloudVpnAccess(application, account, teams, next)
                    ensureActive()
                    if (teams.isCurrent(next)) {
                        controller.bind(access, restoreSaved = firstBinding)
                        firstBinding = false
                        mutableOwner.value = next
                    }
                } catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    mutableFailure.value = "Cloud VPN account setup was interrupted. Retry when connected."
                }
                  catch (_: Exception) { mutableFailure.value = "Could not prepare the Cloud VPN account. Retry when connected." }
            }
        }
    }
    fun enable(expected: NativeTeamScope): Boolean = owner.value == expected && teams.isCurrent(expected) && controller.enable()
    /** Called only by Android restarting our previously sticky service. It adopts
     * the empty service, not a native tunnel; verified binding restores the profile. */
    fun recoverService(service: NativeCloudVpnService) {
        platform.adopt(service)
        controller.platformRecovered()
        scope.launch {
            val requested = runCatching { vpnStore.load().profile?.requested == true }.getOrDefault(false)
            if (!requested) controller.disable()
        }
    }
    fun disable() = controller.disable()
    fun retry() { if (failure.value != null) refresh.value++ else controller.retryCleanup() }
    override fun close() { controller.close(); job.cancel() }
}
