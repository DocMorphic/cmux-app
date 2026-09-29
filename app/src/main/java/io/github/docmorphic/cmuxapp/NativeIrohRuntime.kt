package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException

internal data class NativeComputersState(
    val account: NativeTeamScope? = null,
    val ready: Boolean = false,
    val loading: Boolean = false,
    val computers: List<IrohV2Computer> = emptyList(),
    val error: String? = null
)

/** Account/team changes replace the complete discovery, endpoint and RPC owner. */
internal class NativeIrohRuntime(
    teams: StateFlow<NativeAccountTeamsState>,
    private val isCurrent: (NativeTeamScope) -> Boolean,
    private val accessToken: suspend () -> String?,
    private val backend: suspend (NativeTeamScope, () -> Boolean) -> IrohAccountBackend,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val retryDelayMillis: Long = 2000
) : AutoCloseable {
    private class Owner(val account: NativeTeamScope) {
        val connections = MobileRpcConnections()
        var service: IrohAccountBackend? = null
    }
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retry = MutableStateFlow(0L)
    private val mutableState = MutableStateFlow(NativeComputersState())
    val state = mutableState.asStateFlow()
    private var owner: Owner? = null
    private var closed = false

    init {
        scope.launch {
            combine(teams.map { it.scope }.distinctUntilChanged(), retry) { team, _ -> team }
                .collectLatest { team ->
                    if (team == null) {
                        synchronized(lock) { if (!closed) mutableState.value = NativeComputersState() }
                        return@collectLatest
                    }
                    var attempt = 0
                    while (currentCoroutineContext().isActive && isCurrent(team)) {
                        val run = Owner(team)
                        synchronized(lock) {
                            if (closed) return@collectLatest
                            owner = run
                            mutableState.value = NativeComputersState(team, loading = true)
                        }
                        var service: IrohAccountBackend? = null
                        var failure: Throwable? = null
                        try {
                            service = backend(team) { current(run) }
                            synchronized(lock) {
                                check(current(run)) { "Account session changed" }
                                run.service = service
                            }
                            currentCoroutineContext().ensureActive()
                            service.start()
                            service.state.collect { snapshot ->
                                requireCurrent(run)
                                publish(run, snapshot)
                                if (!snapshot.ready) throw IOException(snapshot.failure ?: "Connection interrupted")
                                attempt = 0
                            }
                        } catch (error: Throwable) {
                            currentCoroutineContext().ensureActive()
                            val reason = service?.state?.value?.failure
                            failure = if (reason in IrohV2Recovery.terminalCodes || reason in IrohV2Recovery.authenticationCodes)
                                IrohV2ServerFailure(checkNotNull(reason), false) else error
                        } finally {
                            synchronized(lock) {
                                if (owner === run) {
                                    owner = null
                                    if (!closed) mutableState.value = NativeComputersState(team,
                                        error = failureMessage(failure))
                                }
                            }
                            run.connections.close()
                            service?.close()
                            withContext(NonCancellable) { service?.awaitClosed() }
                        }
                        if (!isCurrent(team)) break
                        if (IrohV2Recovery.stops(failure)) break
                        delay(IrohV2Recovery.delayMillis(failure, attempt++, retryDelayMillis, now() * 1000))
                    }
                }
        }
    }

    fun refresh() {
        val run = synchronized(lock) { owner }
        val service = synchronized(lock) { run?.service }
        if (run == null || service == null || !current(run) || !service.state.value.ready) {
            retry.value += 1
        } else scope.launch {
            try { service.refresh() }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (current(run)) mutableState.value = mutableState.value.copy(error = failureMessage(failure))
                }
            }
        }
    }

    suspend fun networking(team: NativeTeamScope, refresh: Boolean = false): NativeNetworkingSnapshot =
        withContext(Dispatchers.IO) {
            val run = synchronized(lock) { owner } ?: error("Networking is not ready")
            requireCurrent(run)
            check(run.account == team) { "Account session changed" }
            val service = synchronized(lock) { run.service } ?: error("Networking is not ready")
            if (refresh) {
                service.refreshNetworking()
                requireCurrent(run)
            }
            val endpoint = service.endpointStatus()
            val control = service.state.value
            requireCurrent(run)
            NativeNetworkingSnapshot.from(control, endpoint, now()).also { requireCurrent(run) }
        }

    suspend fun privatePaths(team: NativeTeamScope, change: ((NativePrivatePathStore) -> Unit)? = null): List<NativePrivatePath> =
        withContext(Dispatchers.IO) {
            val run = synchronized(lock) { owner } ?: error("Account session changed")
            requireCurrent(run)
            check(run.account == team) { "Account session changed" }
            val store = synchronized(lock) { run.service }?.privatePaths ?: error("Networking is not ready")
            requireCurrent(run)
            change?.invoke(store)
            requireCurrent(run)
            store.load().also { requireCurrent(run) }
        }

    suspend fun connect(pairing: PairingCode.Iroh): MobileRpcClient = try {
        connectCurrent(pairing)
    } catch (failure: CancellationException) {
        // A child dial deadline or retirement must not cancel a still-active UI/service owner.
        // Actual caller cancellation still propagates, including during account/Activity teardown.
        currentCoroutineContext().ensureActive()
        throw IOException(if (failure is TimeoutCancellationException) "Timed out connecting to this Mac"
            else "The Mac connection changed. Reconnecting…", failure)
    }

    private suspend fun connectCurrent(pairing: PairingCode.Iroh): MobileRpcClient {
        val available = withTimeout(30_000) { state.first { it.ready || (!it.loading && it.error != null) } }
        check(available.ready) { available.error ?: "Waiting for your computers" }
        val run = synchronized(lock) { owner } ?: error("Account session changed")
        requireCurrent(run)
        require(pairing.userId == null || pairing.userId == run.account.userId) { "This computer belongs to another account" }
        require(pairing.teamId == null || pairing.teamId == run.account.teamId) { "Select this computer's team first" }
        val service = synchronized(lock) { run.service } ?: error("Account session changed")
        val mac = service.state.value.computers.singleOrNull { it.endpointId == pairing.endpointId }
            ?: error("This Mac is not available in your selected team")
        require(pairing.macDeviceId == null || pairing.macDeviceId.equals(mac.deviceId, ignoreCase = true)) { "Mac identity changed" }
        require(pairing.buildTag == null || pairing.buildTag == mac.buildTag) { "Mac build changed" }
        val permits = { authorized(run, mac) }
        return run.connections.acquire(connectionKey(mac), permits) {
            MobileRpcClient(service.transport(mac, permits), {
                requireCurrent(run)
                accessToken().also { requireCurrent(run) }
            })
        }
    }

    private fun publish(run: Owner, snapshot: IrohV2ControlState) {
        val computers = if (snapshot.ready && (snapshot.permissionExpiresAt ?: 0) > now()) snapshot.computers else emptyList()
        synchronized(lock) {
            requireCurrent(run)
            mutableState.value = NativeComputersState(run.account, snapshot.ready, computers = computers, error = snapshot.failure)
        }
        run.connections.retain(computers.map(::connectionKey).toSet())
    }

    private fun authorized(run: Owner, mac: IrohV2Computer): Boolean {
        if (!current(run)) return false
        val snapshot = synchronized(lock) { run.service }?.state?.value ?: return false
        return snapshot.ready && (snapshot.permissionExpiresAt ?: 0) > now() && snapshot.computers.any {
            it.endpointId == mac.endpointId && it.recordId == mac.recordId && it.deviceId == mac.deviceId && it.buildTag == mac.buildTag
        }
    }

    private fun current(run: Owner) = synchronized(lock) { !closed && owner === run && isCurrent(run.account) }
    private fun connectionKey(mac: IrohV2Computer) = org.json.JSONArray(listOf(
        mac.endpointId, mac.recordId, mac.deviceId, mac.buildTag)).toString()
    private fun requireCurrent(run: Owner) { if (!current(run)) throw CancellationException("Account session changed") }
    private fun failureMessage(failure: Throwable?) = when (failure) {
        is IrohV2ServerFailure -> when (failure.code) {
            "unauthorized", "ticket_expired" -> "Sign in again to connect to your computers"
            "device_revoked" -> "This device’s cmux access was revoked"
            "team_access_revoked" -> "Your access to this team was removed"
            else -> "Could not connect to your computers"
        }
        is IrohV2HttpFailure -> if (failure.status == 401) "Sign in again to connect to your computers" else "Could not connect to your computers"
        null, is CancellationException -> null
        else -> "Could not connect to your computers"
    }

    override fun close() {
        val old = synchronized(lock) {
            if (closed) return
            closed = true
            mutableState.value = NativeComputersState()
            owner.also { owner = null }
        }
        old?.connections?.close()
        old?.service?.close()
        scope.cancel()
    }
}
