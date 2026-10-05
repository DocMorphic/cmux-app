package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException

internal data class NativeComputersState(
    val account: NativeTeamScope? = null,
    val ready: Boolean = false,
    val loading: Boolean = false,
    val computers: List<IrohV2Computer> = emptyList(),
    val error: String? = null,
    val connectionKeys: Map<String, String> = emptyMap(),
    val localConnectionKeys: Map<NativeMacIdentity, String> = emptyMap()
) {
    fun connectionKey(pairing: PairingCode.Iroh?, savedTarget: NativeComputerTarget? = null): String? {
        if (pairing == null) return null
        val deviceId = savedTarget?.deviceId ?: pairing.macDeviceId
        val buildTag = savedTarget?.buildTag ?: pairing.buildTag
        val local = deviceId?.let { device -> buildTag?.let { build ->
            localConnectionKeys[NativeMacIdentity(canonicalMacDeviceId(device), build)]
        } }
        if (local != null) return local
        if (deviceId != null && buildTag != null) {
            val current = computers.singleOrNull {
                canonicalMacDeviceId(it.deviceId) == canonicalMacDeviceId(deviceId) && it.buildTag == buildTag
            } ?: return null
            return connectionKeys[current.endpointId]
        }
        return connectionKeys[pairing.endpointId]
    }
}

/** Account/team changes replace the complete discovery, endpoint and RPC owner. */
internal class NativeIrohRuntime(
    private val teams: StateFlow<NativeAccountTeamsState>,
    private val isCurrent: (NativeTeamScope) -> Boolean,
    private val accessToken: suspend () -> String?,
    private val backend: suspend (NativeTeamScope, () -> Boolean) -> IrohAccountBackend,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val retryDelayMillis: Long = 2000,
    private val savedTailscale: NativeSavedTailscaleRuntime? = null,
    private val admitCompatibility: (suspend (NativeTeamScope, MobileRpcClient, org.json.JSONObject, Boolean) -> Unit)? = null,
    private val audience: NativeMacBuildAudience? = null
) : AutoCloseable {
    private class Owner(val account: NativeTeamScope) {
        val connections = MobileRpcConnections()
        val powerMutations = mutableMapOf<String, kotlinx.coroutines.sync.Mutex>()
        val forgetMutations = mutableMapOf<NativeMacIdentity, kotlinx.coroutines.sync.Mutex>()
        var service: IrohAccountBackend? = null
    }
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retry = MutableStateFlow(0L)
    private val mutableState = MutableStateFlow(NativeComputersState())
    val state: StateFlow<NativeComputersState> = savedTailscale?.let { saved ->
        combine(mutableState, saved.state) { discovery, local ->
            val desired = teams.value.scope
            val base = discovery.takeIf { it.account == desired } ?: NativeComputersState(account = desired, loading = desired != null)
            base.copy(localConnectionKeys = local.keys.takeIf { local.account == desired }.orEmpty())
        }.stateIn(scope, SharingStarted.Eagerly, NativeComputersState())
    } ?: mutableState.asStateFlow()
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
                            val settings = service.connectionSettings?.state ?: MutableStateFlow(NativeMacConnectionPreferences())
                            val routes = service.routeRevisions ?: MutableStateFlow(0L)
                            combine(service.state, settings, routes) { snapshot, _, _ -> snapshot }.collect { snapshot ->
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

    /** Refresh and check a specific Mac without selecting it or retaining a new UI/service lease. */
    suspend fun checkComputer(team: NativeTeamScope, target: NativeComputerTarget,
                              timeoutMillis: Long = 30_000): NativeConnectionReport = try {
        withTimeout(timeoutMillis) {
            audience?.requireTag(target.buildTag)
            savedTailscale?.connectIfSelected(team, target)?.let { client ->
                return@withTimeout client.use {
                    NativeConnectionCheck.run(it, NativeCredentialStore.PairedMac("", target.deviceId, target.name, target.buildTag))
                        .also { check(isCurrent(team)) { "Account session changed" } }
                }
            }
            val run = synchronized(lock) { owner } ?: error("Networking is not ready")
            requireCurrent(run)
            if (run.account != team) return@withTimeout NativeConnectionReport(failure = NativeConnectionReport.Failure.ACCOUNT)
            val service = synchronized(lock) { run.service } ?: error("Networking is not ready")
            service.refresh()
            requireCurrent(run)
            val snapshot = service.state.value
            val mac = snapshot.computers.singleOrNull { target.matches(it) }
            if (!snapshot.ready || (snapshot.permissionExpiresAt ?: 0) <= now() || mac == null)
                return@withTimeout NativeConnectionReport(failure = NativeConnectionReport.Failure.DISCOVERY)
            val locator = PairingCodeParser.computer(mac, team)
            val pairing = PairingCodeParser.parse(locator).getOrThrow() as PairingCode.Iroh
            connectCurrent(pairing, run).use { lease ->
                val savedIdentity = NativeCredentialStore.PairedMac(locator, mac.deviceId, mac.name, mac.buildTag)
                NativeConnectionCheck.run(lease, savedIdentity).also { requireCurrent(run) }
            }
        }
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        val reason = when {
            failure is TimeoutCancellationException -> NativeConnectionReport.Failure.TIMEOUT
            failure is TailscaleReadinessException -> NativeConnectionReport.Failure.TAILSCALE
            failure is MacUpdateRequired -> NativeConnectionReport.Failure.UPDATE
            failure is MacBuildNotSupported -> NativeConnectionReport.Failure.BUILD
            !isCurrent(team) || (failure is IrohV2ServerFailure &&
                (failure.code in IrohV2Recovery.terminalCodes || failure.code in IrohV2Recovery.authenticationCodes)) ||
                (failure is IrohV2HttpFailure && failure.status in setOf(401, 403)) -> NativeConnectionReport.Failure.ACCOUNT
            else -> NativeConnectionReport.Failure.CONNECTION
        }
        NativeConnectionReport(failure = reason)
    }

    /** The captured detail's owner fences every network operation. The backend
     * resolves fresh registration IDs and the server decides management permission.
     */
    suspend fun forgetComputer(team: NativeTeamScope, target: NativeComputerTarget, timeoutMillis: Long = 30_000) {
        val run = synchronized(lock) { owner } ?: error("Networking is not ready")
        requireCurrent(run)
        check(run.account == team) { "Account or team changed. Reopen Computer Details." }
        val gate = synchronized(lock) { run.forgetMutations.getOrPut(NativeMacIdentity(canonicalMacDeviceId(target.deviceId), target.buildTag)) {
            kotlinx.coroutines.sync.Mutex()
        } }
        check(gate.tryLock()) { "Computer removal is already in progress" }
        try {
            withTimeout(timeoutMillis) {
                requireCurrent(run)
                val service = synchronized(lock) { run.service } ?: error("Networking is not ready")
                service.revokeComputer(target)
                requireCurrent(run)
            }
        } finally { gate.unlock() }
    }

    fun powerSession(team: NativeTeamScope, target: NativeComputerTarget): NativeMacPowerSession? {
        if (audience?.allowsTag(target.buildTag) == false) return null
        if (savedTailscale?.selected(team, target) == true) return savedTailscale.powerSession(team, target)
        val run = synchronized(lock) { owner } ?: return null
        if (!current(run) || run.account != team) return null
        val mac = synchronized(lock) { run.service }?.state?.value?.computers
            ?.singleOrNull { target.matches(it) } ?: return null
        val intent = runCatching { dialIntent(run, mac) }.getOrNull() ?: return null
        if (!intent.dialable) return null
        val permits = { authorized(run, mac, intent) }
        val key = connectionKey(mac, intent)
        val lease = run.connections.borrowIfConnected(key, permits) ?: return null
        val gate = synchronized(lock) {
            run.powerMutations.getOrPut(key) { kotlinx.coroutines.sync.Mutex() }
        }
        return NativeMacPowerSession(lease, target, permits, gate)
    }

    /** Local appearance may be edited offline, but never through a retired account/team page. */
    fun permitsAppearance(team: NativeTeamScope): Boolean = synchronized(lock) { !closed && isCurrent(team) }
    fun usesSavedTailscale(team: NativeTeamScope, target: NativeComputerTarget) = savedTailscale?.selected(team, target) == true

    suspend fun requestManualAttachTicket(client: MobileRpcClient, route: PairingCode.Route,
        host: org.json.JSONObject, team: NativeTeamScope): MobileAttachTicket? {
        check(permitsAppearance(team)) { "Account or team changed. Reopen Computer Details." }
        return ManualAttachTicketRequest.request(client, route, host, team, teams.value.email).also {
            check(permitsAppearance(team)) { "Account or team changed. Reopen Computer Details." }
        }
    }

    /** Also used by the short-lived, identity-checked Add Tailscale Connection probe. */
    suspend fun admitAuthenticatedHost(team: NativeTeamScope, client: MobileRpcClient, host: org.json.JSONObject) {
        check(permitsAppearance(team)) { "Account or team changed. Reopen Computer Details." }
        admitCompatibility?.invoke(team, client, host, true)
        check(permitsAppearance(team)) { "Account or team changed. Reopen Computer Details." }
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

    /** Only the saved-record boundary may replace a stale endpoint with this team's
     * current directory identity. Fresh codes continue to name an exact endpoint. */
    suspend fun connectSaved(pairing: PairingCode.Iroh, target: NativeComputerTarget,
                             team: NativeTeamScope): MobileRpcClient = try {
        require(pairing.macDeviceId == null || canonicalMacDeviceId(pairing.macDeviceId) == canonicalMacDeviceId(target.deviceId)) { "Mac identity changed" }
        require(pairing.buildTag == null || pairing.buildTag == target.buildTag) { "Mac build changed" }
        check(isCurrent(team)) { "Account session changed" }
        connectCurrent(pairing.copy(macDeviceId = target.deviceId, buildTag = target.buildTag),
            savedTarget = target, savedTeam = team)
    } catch (failure: CancellationException) {
        currentCoroutineContext().ensureActive()
        throw IOException(if (failure is TimeoutCancellationException) "Timed out connecting to this Mac"
            else "The Mac connection changed. Reconnecting…", failure)
    }

    private suspend fun connectCurrent(pairing: PairingCode.Iroh, expectedOwner: Owner? = null,
                                       savedTarget: NativeComputerTarget? = null, savedTeam: NativeTeamScope? = null): MobileRpcClient {
        pairing.buildTag?.let { audience?.requireTag(it) }
        if (savedTeam != null) check(isCurrent(savedTeam)) { "Account session changed" }
        savedTailscale?.connectIfSelected(pairing)?.let { client ->
            if (savedTeam != null && !isCurrent(savedTeam)) {
                client.close()
                error("Account session changed")
            }
            return client
        }
        val available = withTimeout(30_000) { state.first { it.ready || (!it.loading && it.error != null) } }
        check(available.ready) { available.error ?: "Waiting for your computers" }
        val run = synchronized(lock) { owner } ?: error("Account session changed")
        requireCurrent(run)
        check(savedTeam == null || run.account == savedTeam) { "Account session changed" }
        check(expectedOwner == null || expectedOwner === run) { "Account session changed" }
        require(pairing.userId == null || pairing.userId == run.account.userId) { "This computer belongs to another account" }
        require(pairing.teamId == null || pairing.teamId == run.account.teamId) { "Select this computer's team first" }
        val service = synchronized(lock) { run.service } ?: error("Account session changed")
        val mac = service.state.value.computers.singleOrNull {
            if (savedTarget != null) savedTarget.matches(it) else it.endpointId == pairing.endpointId
        }
            ?: error("This Mac is not available in your selected team")
        audience?.requireTag(mac.buildTag)
        require(pairing.macDeviceId == null || canonicalMacDeviceId(pairing.macDeviceId) == canonicalMacDeviceId(mac.deviceId)) { "Mac identity changed" }
        require(pairing.buildTag == null || pairing.buildTag == mac.buildTag) { "Mac build changed" }
        val intent = dialIntent(run, mac)
        check(intent.dialable) { "This connection method needs an address. Open Computer Details to add one." }
        if (intent.method == NativeMacConnectionMethod.TAILSCALE && savedTailscale != null) {
            // Preferences may change while discovery is pending after the first saved-route check.
            return checkNotNull(savedTailscale.connectIfSelected(run.account, NativeComputerTarget.from(mac))) {
                "Connection settings changed. Reconnect to this Mac."
            }
        }
        val permits = { authorized(run, mac, intent) }
        return run.connections.acquire(connectionKey(mac, intent), permits,
            validate = { client ->
                service.validateConnection(mac, intent, client)
                admitCompatibility?.let { admit ->
                    val host = client.hostStatus()
                    NativeCredentialStore.PairedMac("", mac.deviceId, mac.name, mac.buildTag).requireMatchingHost(host)
                    client.workspaces()
                    requireCurrent(run)
                    admit(run.account, client, host, intent.method == NativeMacConnectionMethod.TAILSCALE)
                }
            }) {
            MobileRpcClient(service.transport(mac, permits, intent), {
                requireCurrent(run)
                accessToken().also { requireCurrent(run) }
            })
        }
    }

    private fun publish(run: Owner, snapshot: IrohV2ControlState) {
        val computers = if (snapshot.ready && (snapshot.permissionExpiresAt ?: 0) > now())
            snapshot.computers.filter { audience?.allowsTag(it.buildTag) != false } else emptyList()
        val intents = computers.associateWith { mac -> runCatching { dialIntent(run, mac) }.getOrNull() }
        val keys = intents.entries.associate { (mac, intent) -> mac.endpointId to (intent?.let { connectionKey(mac, it) } ?: "unavailable") }
        synchronized(lock) {
            requireCurrent(run)
            mutableState.value = NativeComputersState(run.account, snapshot.ready, computers = computers, error = snapshot.failure,
                connectionKeys = keys)
        }
        run.connections.retain(intents.entries.mapNotNull { (mac, intent) ->
            intent?.takeIf { it.dialable }?.let { connectionKey(mac, it) }
        }.toSet())
    }

    private fun authorized(run: Owner, mac: IrohV2Computer, intent: NativeMacDialIntent): Boolean {
        if (audience?.allowsTag(mac.buildTag) == false) return false
        if (!current(run) || runCatching { dialIntent(run, mac) != intent }.getOrDefault(true)) return false
        val snapshot = synchronized(lock) { run.service }?.state?.value ?: return false
        return snapshot.ready && (snapshot.permissionExpiresAt ?: 0) > now() && snapshot.computers.any {
            it.endpointId == mac.endpointId && it.recordId == mac.recordId && it.deviceId == mac.deviceId && it.buildTag == mac.buildTag
        }
    }

    private fun current(run: Owner) = synchronized(lock) { !closed && owner === run && isCurrent(run.account) }
    private fun dialIntent(run: Owner, mac: IrohV2Computer) = synchronized(lock) { run.service }
        ?.dialIntent(mac) ?: NativeMacDialIntent()
    private fun connectionKey(mac: IrohV2Computer, intent: NativeMacDialIntent) = org.json.JSONArray(listOf(
        mac.endpointId, mac.recordId, mac.deviceId, mac.buildTag, intent.key())).toString()
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
        savedTailscale?.close()
    }
}
