package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class NativeSavedTailscaleAccount(
    val settings: NativeMacConnectionStore,
    val revisions: StateFlow<Long>,
    val grants: (NativeComputerTarget) -> List<TailscaleSavedGrant>,
    val transport: (List<TailscaleSavedGrant>, () -> Boolean) -> MobileRpcTransport,
    val resolve: (PairingCode.Iroh) -> NativeComputerTarget? = { pairing ->
        pairing.macDeviceId?.let { device -> pairing.buildTag?.let { NativeComputerTarget(device, it, "Mac") } }
    }
)

internal data class NativeSavedTailscaleState(val account: NativeTeamScope? = null,
    val keys: Map<NativeMacIdentity, String> = emptyMap(), val error: String? = null)

/** Account-owned legacy TCP sessions are independent of Iroh discovery, enrollment and endpoint lifetime. */
internal class NativeSavedTailscaleRuntime(
    private val teams: StateFlow<NativeAccountTeamsState>,
    private val isCurrent: (NativeTeamScope) -> Boolean,
    private val token: suspend () -> String?,
    private val admitCompatibility: suspend (NativeTeamScope, MobileRpcClient, org.json.JSONObject) -> Unit = { _, _, _ -> },
    private val audience: NativeMacBuildAudience? = null,
    private val manualTicket: suspend (MobileRpcClient, PairingCode.Route, org.json.JSONObject, NativeTeamScope, String?) -> MobileAttachTicket? = ManualAttachTicketRequest::request,
    private val forceToken: (suspend () -> String?)? = null,
    private val allowsContinuation: (NativeTeamContinuation) -> Boolean = { false },
    private val account: (NativeTeamScope) -> NativeSavedTailscaleAccount
) : AutoCloseable {
    private class Owner(var team: NativeTeamScope, val account: NativeSavedTailscaleAccount) {
        val connections = MobileRpcConnections()
        val power = mutableMapOf<String, Mutex>()
        var job: Job? = null
        var disposed = false
    }
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val owners = MutableStateFlow<Owner?>(null)
    private val liveOwners = mutableSetOf<Owner>()
    private val ownership = MutableStateFlow(0L)
    private val ownershipSequence = AtomicLong()
    private val foreground = AtomicReference<ForegroundLease?>(null)
    private val mutableState = MutableStateFlow(NativeSavedTailscaleState())
    val state = mutableState.asStateFlow()
    private var closed = false

    private inner class ForegroundLease(val run: Owner, val key: String,
        val proof: NativeTeamContinuation, val client: MobileRpcClient) : NativeForegroundTransport {
        val closed = AtomicBoolean(false)
        private val activated = AtomicBoolean(false)
        private val monitor = AtomicReference<Job?>(null)
        override fun activate(stillCurrent: () -> Boolean) {
            check(run.connections.keyFor(client) == key && allowsContinuation(proof)) { "Saved Tailscale transport changed" }
            synchronized(lock) {
                check(!closed.get() && current(run, proof.admittedScope) && !client.isClosed && stillCurrent() &&
                    activated.compareAndSet(false, true)) { "Saved Tailscale foreground admission changed" }
                foreground.set(this)
            }
            val watching = scope.launch { client.disconnected.first(); close() }
            monitor.set(watching); if (closed.get()) watching.cancel()
            wakeOwnership()
        }
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                monitor.getAndSet(null)?.cancel()
                wakeOwnership()
            }
        }
    }
    private fun wakeOwnership() { ownership.value = ownershipSequence.incrementAndGet() }
    private fun held(run: Owner) = foreground.get()?.takeIf {
        it.run === run && !it.closed.get() && !it.client.isClosed && allowsContinuation(it.proof)
    }
    private fun alive(run: Owner) = synchronized(lock) {
        !closed && !run.disposed && run in liveOwners && (current(run) || held(run) != null)
    }
    fun reserveForeground(proof: NativeTeamContinuation, client: MobileRpcClient): NativeForegroundTransport? {
        if (!allowsContinuation(proof)) return null
        val run = owners.value ?: return null
        if (!current(run, proof.admittedScope)) return null
        val key = run.connections.keyFor(client) ?: return null
        return ForegroundLease(run, key, proof, client)
    }

    /** Read-only wire ownership; takes no runtime monitor inside the compatibility gate. */
    fun retainsForeground(owner: NativeTeamScope, client: MobileRpcClient): Boolean {
        val lease = foreground.get() ?: return false
        val admitted = lease.proof.admittedScope
        return !lease.closed.get() && !lease.client.isClosed && !client.isClosed &&
            admitted.login == owner.login && admitted.userId == owner.userId && admitted.teamId == owner.teamId &&
            lease.client.compatibilityWire === client.compatibilityWire && allowsContinuation(lease.proof)
    }

    private fun dispose(run: Owner) {
        synchronized(lock) {
            if (run.disposed) return
            run.disposed = true; liveOwners.remove(run)
        }
        run.connections.close(); run.job?.cancel()
    }

    init {
        scope.launch {
            combine(teams, ownership) { account, _ -> account.scope }.collect { team ->
                val selected = team?.takeIf(isCurrent)
                // Creating settings/routes may touch storage. Keep it outside the runtime monitor.
                val configuration = try {
                    if (selected != null && owners.value?.team != selected) account(selected) else null
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    val retired = synchronized(lock) {
                        if (closed || selected == null || !isCurrent(selected)) return@collect
                        owners.value = null
                        mutableState.value = NativeSavedTailscaleState(selected, error = "Could not load saved Tailscale connections.")
                        liveOwners.filter { held(it) == null }
                    }
                    retired.forEach(::dispose)
                    retired.forEach { it.job?.join() }
                    return@collect
                }
                val trim = mutableListOf<Pair<Owner, Set<String>>>()
                val retired = mutableListOf<Owner>()
                var start: Job? = null
                var republish: Owner? = null
                synchronized(lock) {
                    if (closed) return@collect
                    foreground.get()?.let { previous ->
                        if (held(previous.run) !== previous) {
                            foreground.compareAndSet(previous, null); previous.close()
                        }
                    }
                    if (selected != null && !isCurrent(selected)) return@collect
                    val old = owners.value
                    val changed = old?.team != selected
                    val next = when {
                        selected == null -> null
                        !changed -> old
                        else -> liveOwners.firstOrNull { !it.disposed && held(it) != null &&
                            it.team.login == selected.login && it.team.userId == selected.userId && it.team.teamId == selected.teamId }
                            ?: Owner(selected, checkNotNull(configuration)).also { run ->
                                liveOwners += run
                                run.job = scope.launch(start = CoroutineStart.LAZY) { observe(run) }
                                start = run.job
                            }
                    }
                    if (changed) old?.let { trim += it to setOfNotNull(held(it)?.key) }
                    next?.let { it.team = checkNotNull(selected) }
                    owners.value = next
                    if (changed || next == null) mutableState.value = NativeSavedTailscaleState(selected)
                    if (changed && next != null && start == null) republish = next
                    retired += liveOwners.filter { it !== next && held(it) == null }
                }
                trim.forEach { (run, keys) -> run.connections.retain(keys) }
                retired.forEach(::dispose)
                retired.forEach { it.job?.join() }
                republish?.let(::publish)
                start?.start()
            }
        }
    }

    private suspend fun observe(run: Owner) {
        try {
            combine(run.account.settings.state, run.account.revisions) { _, _ -> Unit }.collect {
                check(alive(run)) { "Saved Tailscale owner changed" }
                publish(run)
            }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (current(run)) mutableState.value = NativeSavedTailscaleState(run.team, error = "Could not load saved Tailscale connections.")
        } finally { run.connections.close() }
    }

    private suspend fun owner(): Owner {
        synchronized(lock) { check(!closed) { "Saved connections are closed" } }
        val accountState = withTimeout(30_000) {
            combine(teams, state) { accountState, _ -> accountState }.first {
                synchronized(lock) { closed } || it.scope != null || it.error != null
            }
        }
        synchronized(lock) { check(!closed) { "Saved connections are closed" } }
        val team = accountState.scope ?: error("Refresh your account teams before connecting.")
        val result = withTimeout(30_000) {
            combine(owners, state) { candidate, status -> candidate to status }.first { (candidate, status) ->
                synchronized(lock) { closed } || !isCurrent(team) || candidate?.team == team || (status.account == team && status.error != null)
            }
        }
        check(isCurrent(team)) { "Account session changed" }
        synchronized(lock) { check(!closed) { "Saved connections are closed" } }
        return checkNotNull(result.first?.takeIf { it.team == team }) { result.second.error ?: "Saved connections are unavailable" }
    }

    suspend fun connectIfSelected(pairing: PairingCode.Iroh): MobileRpcClient? {
        val run = owner()
        require(pairing.userId == null || pairing.userId == run.team.userId) { "This computer belongs to another account" }
        require(pairing.teamId == null || pairing.teamId == run.team.teamId) { "Select this computer’s team first" }
        val target = run.account.resolve(pairing) ?: return null
        require(pairing.macDeviceId == null || canonicalMacDeviceId(pairing.macDeviceId) == canonicalMacDeviceId(target.deviceId)) { "Mac identity changed" }
        require(pairing.buildTag == null || pairing.buildTag == target.buildTag) { "Mac build changed" }
        return connectIfSelected(run, target)
    }

    suspend fun connectIfSelected(team: NativeTeamScope, target: NativeComputerTarget): MobileRpcClient? {
        val run = owner()
        check(run.team == team) { "Account or team changed. Reopen Computer Details." }
        return connectIfSelected(run, target)
    }

    private suspend fun connectIfSelected(run: Owner, target: NativeComputerTarget): MobileRpcClient? {
        val selected = synchronized(lock) { run.team }
        check(current(run, selected)) { "Account session changed" }
        audience?.requireTag(target.buildTag)
        val intent = intent(run, target)
        if (intent.method != NativeMacConnectionMethod.TAILSCALE) return null
        check(intent.dialable) { "Add a Tailscale connection in Computer Details first." }
        val live = { allowed(run, target, intent) }
        val permits = { current(run, selected) && live() }
        var admittedHost: org.json.JSONObject? = null
        return run.connections.acquire(key(target, intent), permits, ticketContext = { client ->
            val host = client.hostStatus()
            check(intent.tailscale.all { it.matches(host) }) { "This Tailscale route reaches a different Mac or cmux installation." }
            admittedHost = host
            val route = checkNotNull(client.tailscalePeer()) { "The connected Tailscale route is unavailable" }
            check(intent.tailscale.any { it.route == route }) { "The connected route is not authorized" }
            check(permits()) { "Saved Tailscale admission changed" }
            val ticket = manualTicket(client, route, host, selected, teams.value.email)
            check(permits()) { "The Tailscale authorization changed" }
            ticket?.context()
        }, validate = { client ->
            val host = checkNotNull(admittedHost)
            client.workspaces()
            check(permits()) { "The Tailscale authorization changed" }
            admitCompatibility(selected, client, host)
        }) {
            MobileRpcClient(run.account.transport(intent.tailscale, live), {
                check(live()) { "The Tailscale authorization changed" }
                token().also { check(live()) { "The Tailscale authorization changed" } }
            }).also { client -> forceToken?.let { refresh -> client.configureAccountTokenRefresh {
                check(live()) { "The Tailscale authorization changed" }
                refresh().also { check(live()) { "The Tailscale authorization changed" } }
            } } }
        }
    }

    fun selected(team: NativeTeamScope, target: NativeComputerTarget): Boolean {
        val run = owners.value ?: return false
        return run.team == team && runCatching { current(run) && intent(run, target).method == NativeMacConnectionMethod.TAILSCALE }.getOrDefault(false)
    }

    fun powerSession(team: NativeTeamScope, target: NativeComputerTarget): NativeMacPowerSession? {
        if (audience?.allowsTag(target.buildTag) == false) return null
        val run = owners.value ?: return null
        if (run.team != team || !current(run)) return null
        val captured = runCatching { intent(run, target) }.getOrNull() ?: return null
        if (captured.method != NativeMacConnectionMethod.TAILSCALE || !captured.dialable) return null
        val permits = { current(run, team) && allowed(run, target, captured) }
        val key = key(target, captured)
        val lease = run.connections.borrowIfConnected(key, permits) ?: return null
        val gate = synchronized(lock) { run.power.getOrPut(key) { Mutex() } }
        return NativeMacPowerSession(lease, target, permits, gate)
    }

    private fun intent(run: Owner, target: NativeComputerTarget): NativeMacDialIntent {
        val mac = IrohV2Computer("", "", target.deviceId, target.buildTag, target.name, emptyList())
        val intent = run.account.settings.state.value.intent(mac)
        return if (intent.method == NativeMacConnectionMethod.TAILSCALE)
            intent.copy(tailscale = run.account.grants(target).also { grants ->
                check(grants.all { it.user == run.team.userId && it.team == run.team.teamId &&
                    it.device == canonicalMacDeviceId(target.deviceId) && it.build == target.buildTag }) { "Saved route identity changed" }
            }) else intent
    }
    private fun key(target: NativeComputerTarget, intent: NativeMacDialIntent) = org.json.JSONArray(listOf(
        canonicalMacDeviceId(target.deviceId), target.buildTag, intent.key())).toString()
    private fun current(run: Owner, expected: NativeTeamScope? = null) = synchronized(lock) {
        !closed && !run.disposed && owners.value === run && (expected == null || run.team == expected) && isCurrent(run.team)
    }
    private fun checkCurrent(run: Owner) = check(current(run)) { "Account session changed" }
    private fun allowed(run: Owner, target: NativeComputerTarget, captured: NativeMacDialIntent) =
        runCatching { alive(run) && (current(run) || held(run)?.key == key(target, captured)) &&
            intent(run, target) == captured }.getOrDefault(false)

    private fun publish(run: Owner) {
        val targets = run.account.settings.state.value.values.filterValues { it.method == NativeMacConnectionMethod.TAILSCALE }
            .keys.filter { audience?.allowsTag(it.buildTag) != false }
        val resolved = targets.associateWith { id ->
            val target = NativeComputerTarget(id.deviceId, checkNotNull(id.buildTag), "Mac")
            target to runCatching { intent(run, target) }.getOrNull()
        }
        synchronized(lock) {
            if (current(run)) mutableState.value = NativeSavedTailscaleState(run.team, resolved.mapValues { (_, value) ->
                value.second?.let { key(value.first, it) } ?: "unavailable"
            })
        }
        val keys = resolved.values.mapNotNull { (target, intent) -> intent?.takeIf { it.dialable }?.let { key(target, it) } }.toSet()
        run.connections.retain(if (current(run)) keys else keys.intersect(setOfNotNull(held(run)?.key)))
    }

    override fun close() {
        val previous = synchronized(lock) { closed = true; owners.value = null; liveOwners.toList() }
        mutableState.value = NativeSavedTailscaleState(teams.value.scope, error = "Saved connections are closed")
        foreground.getAndSet(null)?.close(); previous.forEach(::dispose); scope.cancel()
    }
}
