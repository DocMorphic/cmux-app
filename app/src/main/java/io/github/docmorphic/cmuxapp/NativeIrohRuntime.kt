package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

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
        if (pairing == null && savedTarget == null) return null
        val deviceId = savedTarget?.deviceId ?: pairing?.macDeviceId
        val buildTag = savedTarget?.buildTag ?: pairing?.buildTag
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
        return pairing?.endpointId?.let(connectionKeys::get)
    }
}

/** Selected discovery and existing foreground transport have separate lifetimes. */
internal class NativeIrohRuntime(
    private val teams: StateFlow<NativeAccountTeamsState>,
    private val isCurrent: (NativeTeamScope) -> Boolean,
    private val accessToken: suspend () -> String?,
    private val backend: suspend (NativeTeamScope, () -> Boolean) -> IrohAccountBackend,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val retryDelayMillis: Long = 2000,
    private val savedTailscale: NativeSavedTailscaleRuntime? = null,
    private val admitCompatibility: (suspend (NativeTeamScope, MobileRpcClient, org.json.JSONObject, Boolean) -> Unit)? = null,
    private val audience: NativeMacBuildAudience? = null,
    private val allowsContinuation: (NativeTeamContinuation) -> Boolean = { false }
) : AutoCloseable {
    private class BackendLease(val backend: IrohAccountBackend) {
        private val closed = AtomicBoolean(false)
        fun close() { if (closed.compareAndSet(false, true)) backend.close() }
    }
    private class Owner(var account: NativeTeamScope) {
        var connections = MobileRpcConnections()
        val powerMutations = mutableMapOf<String, kotlinx.coroutines.sync.Mutex>()
        val forgetMutations = mutableMapOf<NativeMacIdentity, kotlinx.coroutines.sync.Mutex>()
        var lease: BackendLease? = null
        val service get() = lease?.backend
        var job: Job? = null
        var disposed = false
        var retryEpoch = 0L
    }
    private class Selection(val team: NativeTeamScope) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Selection>
    }
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retry = MutableStateFlow(0L)
    private val ownership = MutableStateFlow(0L)
    private val ownershipSequence = AtomicLong()
    private val owners = mutableSetOf<Owner>()
    private val operations = mutableMapOf<Job, NativeTeamScope>()
    private val foreground = AtomicReference<ForegroundLease?>(null)
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

    private inner class ForegroundLease(val run: Owner, val pool: MobileRpcConnections,
        val key: String, val proof: NativeTeamContinuation, val client: MobileRpcClient) : NativeForegroundTransport {
        val closed = AtomicBoolean(false)
        private val activated = AtomicBoolean(false)
        private val monitor = AtomicReference<Job?>(null)
        override fun activate(stillCurrent: () -> Boolean) {
            check(pool.keyFor(client) == key && allowsContinuation(proof)) { "Foreground transport changed" }
            synchronized(lock) {
                check(!closed.get() && current(run, proof.admittedScope) && run.connections === pool &&
                    !client.isClosed && stillCurrent() && activated.compareAndSet(false, true)) { "Foreground admission changed" }
                foreground.set(this)
            }
            val watching = scope.launch { client.disconnected.first(); close() }
            monitor.set(watching)
            if (closed.get()) watching.cancel()
            wakeOwnership()
        }
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                monitor.getAndSet(null)?.cancel()
                // Atomic wake only: callers may be reconciling inside a credential transaction.
                wakeOwnership()
            }
        }
    }

    fun reserveForeground(proof: NativeTeamContinuation, client: MobileRpcClient): NativeForegroundTransport? {
        if (!allowsContinuation(proof)) return null
        savedTailscale?.reserveForeground(proof, client)?.let { return it }
        val run = synchronized(lock) { owner } ?: return null
        if (!current(run, proof.admittedScope)) return null
        val pool = synchronized(lock) { run.connections }
        val key = pool.keyFor(client) ?: return null
        return ForegroundLease(run, pool, key, proof, client)
    }

    private fun wakeOwnership() { ownership.value = ownershipSequence.incrementAndGet() }

    /** Selected-team admission remains separate; only the activated exact wire can continue. */
    fun retainsForeground(owner: NativeTeamScope, client: MobileRpcClient): Boolean {
        if (savedTailscale?.retainsForeground(owner, client) == true) return true
        val lease = foreground.get() ?: return false
        val admitted = lease.proof.admittedScope
        return !lease.closed.get() && !lease.client.isClosed && !client.isClosed &&
            admitted.login == owner.login && admitted.userId == owner.userId && admitted.teamId == owner.teamId &&
            lease.client.compatibilityWire === client.compatibilityWire && allowsContinuation(lease.proof)
    }

    private fun held(run: Owner): ForegroundLease? = foreground.get()?.takeIf {
        it.run === run && !it.closed.get() && !it.client.isClosed && allowsContinuation(it.proof)
    }

    private fun alive(run: Owner): Boolean = synchronized(lock) {
        !closed && !run.disposed && run in owners && (current(run) || held(run) != null)
    }

    private fun sameAccount(a: NativeTeamScope, b: NativeTeamScope) =
        a.login == b.login && a.userId == b.userId && a.teamId == b.teamId

    private fun dispose(run: Owner) {
        val lease = synchronized(lock) {
            if (run.disposed) return
            run.disposed = true; owners.remove(run)
            run.lease.also { run.lease = null }
        }
        run.connections.close(); lease?.close(); run.job?.cancel()
    }

    init {
        scope.launch {
            combine(teams, retry, ownership) { account, epoch, _ -> account.scope to epoch }
                .collect { (desired, epoch) -> reconcile(desired, epoch) }
        }
    }

    private suspend fun reconcile(desired: NativeTeamScope?, epoch: Long) {
        val cancel = mutableListOf<Job>()
        val trim = mutableListOf<Pair<Owner, Set<String>>>()
        val retire = mutableListOf<Owner>()
        var start: Job? = null
        var republish: Owner? = null
        synchronized(lock) {
            if (closed) return
            operations.entries.filter { !isCurrent(it.value) }.forEach { (job, _) -> cancel += job }
            cancel.forEach { operations.remove(it) }
            foreground.get()?.let { previous ->
                if (held(previous.run) !== previous) {
                    foreground.compareAndSet(previous, null)
                    previous.close()
                }
            }
            val selected = desired?.takeIf(isCurrent)
            val old = owner
            val changed = old?.account != selected
            val restart = old != null && selected != null && old.retryEpoch != epoch &&
                (old.job?.isActive != true || old.service?.state?.value?.ready != true)
            val next = when {
                selected == null -> null
                !changed && !restart -> old
                else -> owners.firstOrNull { !it.disposed && it !== old && sameAccount(it.account, selected) && held(it) != null }
                    ?: if (!restart && old != null && sameAccount(old.account, selected) && held(old) != null) old
                    else Owner(selected).also { run ->
                        owners += run
                        run.job = scope.launch(start = CoroutineStart.LAZY) { runOwner(run) }
                        start = run.job
                    }
            }
            if (changed || restart) {
                old?.let { trim += it to setOfNotNull(held(it)?.key) }
            }
            owner = next
            if (next != null && selected != null) {
                next.account = selected
                next.retryEpoch = epoch
                if (changed || restart) {
                    mutableState.value = NativeComputersState(selected, loading = true)
                    republish = next.takeIf { it.service?.state?.value?.ready == true }
                }
            } else mutableState.value = NativeComputersState()
            retire += owners.filter { it !== next && held(it) == null }
        }
        cancel.forEach { it.cancel(CancellationException("Team selection changed")) }
        trim.forEach { (run, allowed) -> run.connections.retain(allowed) }
        retire.forEach(::dispose)
        // A replacement may use the same installation key. Join old binds/endpoints first.
        retire.forEach { it.job?.join() }
        republish?.let { run -> run.service?.state?.value?.let { publish(run, it) } }
        start?.start()
    }

    private suspend fun runOwner(run: Owner) {
        var attempt = 0
        while (currentCoroutineContext().isActive && alive(run)) {
            val pool = synchronized(lock) {
                if (attempt > 0) run.connections = MobileRpcConnections()
                if (current(run)) mutableState.value = NativeComputersState(run.account, loading = true)
                run.connections
            }
            var lease: BackendLease? = null
            var failure: Throwable? = null
            try {
                lease = BackendLease(backend(run.account) { alive(run) })
                synchronized(lock) {
                    check(alive(run)) { "Account session changed" }
                    run.lease = lease
                }
                currentCoroutineContext().ensureActive()
                val service = lease.backend
                service.start()
                val settings = service.connectionSettings?.state ?: MutableStateFlow(NativeMacConnectionPreferences())
                val routes = service.routeRevisions ?: MutableStateFlow(0L)
                combine(service.state, settings, routes) { snapshot, _, _ -> snapshot }.collect { snapshot ->
                    requireAlive(run)
                    if (!snapshot.ready) throw IOException(snapshot.failure ?: "Connection interrupted")
                    publish(run, snapshot)
                    attempt = 0
                }
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                val reason = lease?.backend?.state?.value?.failure
                failure = if (reason in IrohV2Recovery.terminalCodes || reason in IrohV2Recovery.authenticationCodes)
                    IrohV2ServerFailure(checkNotNull(reason), false) else error
            } finally {
                // Preserve the control-plane rejection before ordinary directory trimming can retire the wire.
                if (failure == null) pool.close() else pool.fail(failure)
                synchronized(lock) {
                    if (run.lease === lease) run.lease = null
                    if (current(run)) mutableState.value = NativeComputersState(run.account, error = failureMessage(failure))
                }
                lease?.close()
                withContext(NonCancellable) { lease?.backend?.awaitClosed() }
            }
            if (!current(run) || IrohV2Recovery.stops(failure)) break
            delay(IrohV2Recovery.delayMillis(failure, attempt++, retryDelayMillis, now() * 1000))
        }
    }

    private suspend fun <T> selectedOperation(team: NativeTeamScope, action: suspend () -> T): T {
        val job = Job(currentCoroutineContext()[Job])
        synchronized(lock) {
            if (closed || !isCurrent(team)) {
                job.cancel()
                throw CancellationException("Account selection changed")
            }
            operations[job] = team
        }
        var acquired: Any? = null
        try { return withContext(job + Selection(team)) { action().also { acquired = it } } }
        catch (failure: Throwable) { (acquired as? AutoCloseable)?.close(); throw failure }
        finally { synchronized(lock) { operations.remove(job) }; job.complete() }
    }

    fun refresh() {
        val run = synchronized(lock) { owner }
        val service = synchronized(lock) { run?.service }
        if (run == null || service == null || !current(run) || !service.state.value.ready) {
            retry.value += 1
        } else {
            val selected = run.account
            scope.launch {
                try { selectedOperation(selected) { requireCurrent(run); service.refresh(); requireCurrent(run) } }
                catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        if (current(run, selected)) mutableState.value = mutableState.value.copy(error = failureMessage(failure))
                    }
                }
            }
        }
    }

    /** Refresh and check a specific Mac without selecting it or retaining a new UI/service lease. */
    suspend fun checkComputer(team: NativeTeamScope, target: NativeComputerTarget,
                              timeoutMillis: Long = 30_000): NativeConnectionReport = try {
        withTimeout(timeoutMillis) { selectedOperation(team) {
            audience?.requireTag(target.buildTag)
            savedTailscale?.connectIfSelected(team, target)?.let { client ->
                return@selectedOperation client.use {
                    NativeConnectionCheck.run(it, NativeCredentialStore.PairedMac("", target.deviceId, target.name, target.buildTag))
                        .also { check(isCurrent(team)) { "Account session changed" } }
                }
            }
            val run = synchronized(lock) { owner } ?: error("Networking is not ready")
            requireCurrent(run)
            if (run.account != team) return@selectedOperation NativeConnectionReport(failure = NativeConnectionReport.Failure.ACCOUNT)
            val service = synchronized(lock) { run.service } ?: error("Networking is not ready")
            service.refresh()
            requireCurrent(run)
            val snapshot = service.state.value
            val mac = snapshot.computers.singleOrNull { target.matches(it) }
            if (!snapshot.ready || (snapshot.permissionExpiresAt ?: 0) <= now() || mac == null)
                return@selectedOperation NativeConnectionReport(failure = NativeConnectionReport.Failure.DISCOVERY)
            val locator = PairingCodeParser.computer(mac, team)
            val pairing = PairingCodeParser.parse(locator).getOrThrow() as PairingCode.Iroh
            connectCurrent(pairing, run).use { lease ->
                val savedIdentity = NativeCredentialStore.PairedMac(locator, mac.deviceId, mac.name, mac.buildTag)
                NativeConnectionCheck.run(lease, savedIdentity).also { requireCurrent(run) }
            }
        } }
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
    suspend fun forgetComputer(team: NativeTeamScope, target: NativeComputerTarget, timeoutMillis: Long = 30_000) = selectedOperation(team) {
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

    /** Account control only: an explicitly confirmed original-owner target never opens a Mac transport. */
    suspend fun forgetForegroundComputer(permit: NativeForegroundComputerRevoke, timeoutMillis: Long = 30_000) {
        if (!permit.begin()) return
        var control: IrohAccountBackend? = null
        try {
            withTimeout(timeoutMillis) {
                check(permit.current()) { "Computer removal owner changed" }
                check(!synchronized(lock) { closed }) { "Networking is closed" }
                control = backend(permit.team) { permit.current() && !synchronized(lock) { closed } }
                check(permit.current())
                control!!.startManagement()
                check(permit.current())
                control!!.revokeComputer(permit.target)
                currentCoroutineContext().ensureActive()
                permit.acknowledge()
            }
        } finally {
            try {
                control?.close()
                withContext(NonCancellable) { withTimeoutOrNull(2000) { control?.awaitClosed() } }
            } finally { permit.finish() }
        }
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
        val permits = { current(run, team) && authorized(run, mac, intent) }
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
        host: org.json.JSONObject, team: NativeTeamScope): MobileAttachTicket? = selectedOperation(team) {
        check(permitsAppearance(team)) { "Account or team changed. Reopen Computer Details." }
        ManualAttachTicketRequest.request(client, route, host, team, teams.value.email).also {
            check(permitsAppearance(team)) { "Account or team changed. Reopen Computer Details." }
        }
    }

    /** Also used by the short-lived, identity-checked Add Tailscale Connection probe. */
    suspend fun admitAuthenticatedHost(team: NativeTeamScope, client: MobileRpcClient, host: org.json.JSONObject) = selectedOperation(team) {
        check(permitsAppearance(team)) { "Account or team changed. Reopen Computer Details." }
        admitCompatibility?.invoke(team, client, host, true)
        check(permitsAppearance(team)) { "Account or team changed. Reopen Computer Details." }
    }

    suspend fun networking(team: NativeTeamScope, refresh: Boolean = false): NativeNetworkingSnapshot =
        withContext(Dispatchers.IO) { selectedOperation(team) {
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
        } }

    /** Local settings only, addressed through the original enrolled identity. No control login or Mac dial. */
    suspend fun foregroundPrivatePaths(access: NativeForegroundComputerDetailAccess,
        change: ((NativePrivatePathStore) -> Unit)? = null): List<NativePrivatePath> = withContext(Dispatchers.IO) {
        val allowed = { access.current() && !synchronized(lock) { closed } }
        check(allowed()) { "Computer detail owner changed" }
        var settings: IrohAccountBackend? = null
        try {
            settings = backend(access.team, allowed)
            check(allowed()) { "Computer detail owner changed" }
            val store = checkNotNull(settings.privatePaths) { "Private address settings are unavailable" }
                .computer(access.target, allowed)
            change?.invoke(store)
            store.load().also { check(allowed()) { "Computer detail owner changed" } }
        } finally {
            settings?.close()
            withContext(NonCancellable) { withTimeoutOrNull(2000) { settings?.awaitClosed() } }
        }
    }

    suspend fun privatePaths(team: NativeTeamScope, change: ((NativePrivatePathStore) -> Unit)? = null): List<NativePrivatePath> =
        withContext(Dispatchers.IO) { selectedOperation(team) {
            val run = synchronized(lock) { owner } ?: error("Account session changed")
            requireCurrent(run)
            check(run.account == team) { "Account session changed" }
            val store = synchronized(lock) { run.service }?.privatePaths ?: error("Networking is not ready")
            requireCurrent(run)
            change?.invoke(store)
            requireCurrent(run)
            store.load().also { requireCurrent(run) }
        } }

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
    suspend fun connectSaved(pairing: PairingCode.Iroh, target: NativeComputerTarget?,
                             team: NativeTeamScope): MobileRpcClient = try {
        if (target != null) {
            require(pairing.macDeviceId == null || canonicalMacDeviceId(pairing.macDeviceId) == canonicalMacDeviceId(target.deviceId)) { "Mac identity changed" }
            require(pairing.buildTag == null || pairing.buildTag == target.buildTag) { "Mac build changed" }
        } else {
            // Pre-tag saved rows may reconnect to their literal endpoint, then
            // learn the authenticated build. They cannot select a replacement peer.
            require(!pairing.macDeviceId.isNullOrBlank() && pairing.buildTag == null) { "Saved Mac identity is incomplete. Pair this Mac again." }
        }
        check(isCurrent(team)) { "Account session changed" }
        connectCurrent(pairing.copy(macDeviceId = target?.deviceId ?: pairing.macDeviceId, buildTag = target?.buildTag ?: pairing.buildTag),
            savedTarget = target, savedTeam = team)
    } catch (failure: CancellationException) {
        currentCoroutineContext().ensureActive()
        throw IOException(if (failure is TimeoutCancellationException) "Timed out connecting to this Mac"
            else "The Mac connection changed. Reconnecting…", failure)
    }

    private suspend fun connectCurrent(pairing: PairingCode.Iroh, expectedOwner: Owner? = null,
                                       savedTarget: NativeComputerTarget? = null, savedTeam: NativeTeamScope? = null): MobileRpcClient {
        val selected = savedTeam ?: withTimeout(30_000) {
            teams.first { it.scope != null || it.error != null }.scope
                ?: error("Refresh your account teams before connecting.")
        }
        return selectedOperation(selected) {
        pairing.buildTag?.let { audience?.requireTag(it) }
        if (savedTeam != null) check(isCurrent(savedTeam)) { "Account session changed" }
        savedTailscale?.connectIfSelected(pairing)?.let { client ->
            if (savedTeam != null && !isCurrent(savedTeam)) {
                client.close()
                error("Account session changed")
            }
            return@selectedOperation client
        }
        val available = withTimeout(30_000) { state.first {
            it.account == selected && (it.ready || (!it.loading && it.error != null))
        } }
        check(available.ready) { available.error ?: "Waiting for your computers" }
        val run = synchronized(lock) { owner } ?: error("Account session changed")
        requireCurrent(run)
        check(savedTeam == null || run.account == savedTeam) { "Account session changed" }
        check(expectedOwner == null || expectedOwner === run) { "Account session changed" }
        require(pairing.userId == null || pairing.userId == run.account.userId) { "This computer belongs to another account" }
        require(pairing.teamId == null || pairing.teamId == run.account.teamId) { "Select this computer's team first" }
        val (service, pool) = synchronized(lock) {
            (run.service ?: error("Account session changed")) to run.connections
        }
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
            return@selectedOperation checkNotNull(savedTailscale.connectIfSelected(run.account, NativeComputerTarget.from(mac))) {
                "Connection settings changed. Reconnect to this Mac."
            }
        }
        val live = { ownsService(run, service, pool) && authorized(run, mac, intent) }
        val permits = { current(run, selected) && live() }
        pool.acquire(connectionKey(mac, intent), permits,
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
            MobileRpcClient(service.transport(mac, live, intent), {
                requireAlive(run)
                check(ownsService(run, service, pool)) { "Transport backend changed" }
                accessToken().also { requireAlive(run) }
            })
        }
        }
    }

    private fun publish(run: Owner, snapshot: IrohV2ControlState) {
        val computers = if (snapshot.ready && (snapshot.permissionExpiresAt ?: 0) > now())
            snapshot.computers.filter { audience?.allowsTag(it.buildTag) != false } else emptyList()
        val intents = computers.associateWith { mac -> runCatching { dialIntent(run, mac) }.getOrNull() }
        val keys = intents.entries.associate { (mac, intent) -> mac.endpointId to (intent?.let { connectionKey(mac, it) } ?: "unavailable") }
        synchronized(lock) {
            if (current(run)) mutableState.value = NativeComputersState(run.account, snapshot.ready, computers = computers, error = snapshot.failure,
                connectionKeys = keys)
        }
        val allowed = intents.entries.mapNotNull { (mac, intent) ->
            intent?.takeIf { it.dialable }?.let { connectionKey(mac, it) }
        }.toSet()
        run.connections.retain(if (current(run)) allowed else allowed.intersect(setOfNotNull(held(run)?.key)))
    }

    private fun authorized(run: Owner, mac: IrohV2Computer, intent: NativeMacDialIntent): Boolean {
        if (audience?.allowsTag(mac.buildTag) == false) return false
        if (!alive(run) || (!current(run) && held(run)?.key != connectionKey(mac, intent)) ||
            runCatching { dialIntent(run, mac) != intent }.getOrDefault(true)) return false
        val snapshot = synchronized(lock) { run.service }?.state?.value ?: return false
        return snapshot.ready && (snapshot.permissionExpiresAt ?: 0) > now() && snapshot.computers.any {
            it.endpointId == mac.endpointId && it.recordId == mac.recordId && it.deviceId == mac.deviceId && it.buildTag == mac.buildTag
        }
    }

    private fun current(run: Owner, expected: NativeTeamScope? = null) = synchronized(lock) {
        val selected = run.account
        !closed && !run.disposed && owner === run && (expected == null || selected == expected) && isCurrent(selected)
    }
    private fun ownsService(run: Owner, service: IrohAccountBackend, pool: MobileRpcConnections) = synchronized(lock) {
        run.service === service && run.connections === pool
    }
    private fun dialIntent(run: Owner, mac: IrohV2Computer) = synchronized(lock) { run.service }
        ?.dialIntent(mac) ?: NativeMacDialIntent()
    private fun connectionKey(mac: IrohV2Computer, intent: NativeMacDialIntent) = org.json.JSONArray(listOf(
        mac.endpointId, mac.recordId, mac.deviceId, mac.buildTag, intent.key())).toString()
    private suspend fun requireCurrent(run: Owner) {
        currentCoroutineContext().ensureActive()
        if (!current(run, currentCoroutineContext()[Selection]?.team ?: run.account))
            throw CancellationException("Account session changed")
    }
    private fun requireAlive(run: Owner) { if (!alive(run)) throw CancellationException("Transport owner changed") }
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
            owner = null
            owners.toList()
        }
        foreground.getAndSet(null)?.close()
        old.forEach(::dispose)
        val pending = synchronized(lock) { operations.keys.toList().also { operations.clear() } }
        pending.forEach { it.cancel() }
        scope.cancel()
        savedTailscale?.close()
    }
}
