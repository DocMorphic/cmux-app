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
    private val controller: CloudSystemVpnController = CloudSystemVpnController(scope, nativeCloudVpnStore(application), platform)
    val state = controller.state
    private val mutableOwner = MutableStateFlow<NativeTeamScope?>(null)
    val owner = mutableOwner.asStateFlow()
    private val mutableFailure = MutableStateFlow<String?>(null)
    val failure = mutableFailure.asStateFlow()
    private val refresh = MutableStateFlow(0)
    init {
        scope.launch {
            combine(teams.state, store.revisions, refresh) { state, _, retry ->
                state.scope?.takeIf(teams::isCurrent) to retry
            }.distinctUntilChanged().collectLatest { (next, _) ->
                mutableOwner.value = null
                controller.bind(null)
                mutableFailure.value = null
                if (next != null) try {
                    val access = nativeCloudVpnAccess(application, account, teams, next)
                    ensureActive()
                    if (teams.isCurrent(next)) { controller.bind(access); mutableOwner.value = next }
                } catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    mutableFailure.value = "Cloud VPN account setup was interrupted. Retry when connected."
                }
                  catch (_: Exception) { mutableFailure.value = "Could not prepare the Cloud VPN account. Retry when connected." }
            }
        }
    }
    fun enable(expected: NativeTeamScope): Boolean = owner.value == expected && teams.isCurrent(expected) && controller.enable()
    fun disable() = controller.disable()
    fun retry() { if (failure.value != null) refresh.value++ else controller.retryCleanup() }
    override fun close() { controller.close(); job.cancel() }
}
