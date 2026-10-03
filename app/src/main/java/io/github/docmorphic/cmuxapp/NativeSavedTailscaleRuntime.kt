package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

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
    private val account: (NativeTeamScope) -> NativeSavedTailscaleAccount
) : AutoCloseable {
    private class Owner(val team: NativeTeamScope, val account: NativeSavedTailscaleAccount) {
        val connections = MobileRpcConnections()
        val power = mutableMapOf<String, Mutex>()
    }
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val owners = MutableStateFlow<Owner?>(null)
    private val mutableState = MutableStateFlow(NativeSavedTailscaleState())
    val state = mutableState.asStateFlow()
    private var closed = false

    init {
        scope.launch {
            teams.map { it.scope }.distinctUntilChanged().collectLatest { team ->
                val previous = synchronized(lock) { owners.value.also { owners.value = null } }
                previous?.connections?.close()
                mutableState.value = NativeSavedTailscaleState(team)
                if (team == null || !isCurrent(team)) return@collectLatest
                var run: Owner? = null
                try {
                    val created = Owner(team, account(team))
                    run = created
                    synchronized(lock) {
                        check(!closed && isCurrent(team)) { "Account session changed" }
                        owners.value = created
                    }
                    combine(created.account.settings.state, created.account.revisions) { _, _ -> Unit }.collect {
                        checkCurrent(created)
                        publish(created)
                    }
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (isCurrent(team)) mutableState.value = NativeSavedTailscaleState(team, error = "Could not load saved Tailscale connections.")
                } finally {
                    synchronized(lock) { if (owners.value === run) owners.value = null }
                    run?.connections?.close()
                    if (mutableState.value.account == team) mutableState.value = mutableState.value.copy(keys = emptyMap())
                }
            }
        }
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
        checkCurrent(run)
        val intent = intent(run, target)
        if (intent.method != NativeMacConnectionMethod.TAILSCALE) return null
        check(intent.dialable) { "Add a Tailscale connection in Computer Details first." }
        val permits = { allowed(run, target, intent) }
        return run.connections.acquire(key(target, intent), permits, validate = { client ->
            val host = client.hostStatus()
            check(intent.tailscale.all { it.matches(host) }) { "This Tailscale route reaches a different Mac or cmux installation." }
            client.workspaces()
            check(permits()) { "The Tailscale authorization changed" }
            admitCompatibility(run.team, client, host)
        }) {
            MobileRpcClient(run.account.transport(intent.tailscale, permits), {
                check(permits()) { "The Tailscale authorization changed" }
                token().also { check(permits()) { "The Tailscale authorization changed" } }
            })
        }
    }

    fun selected(team: NativeTeamScope, target: NativeComputerTarget): Boolean {
        val run = owners.value ?: return false
        return run.team == team && runCatching { current(run) && intent(run, target).method == NativeMacConnectionMethod.TAILSCALE }.getOrDefault(false)
    }

    fun powerSession(team: NativeTeamScope, target: NativeComputerTarget): NativeMacPowerSession? {
        val run = owners.value ?: return null
        if (run.team != team || !current(run)) return null
        val captured = runCatching { intent(run, target) }.getOrNull() ?: return null
        if (captured.method != NativeMacConnectionMethod.TAILSCALE || !captured.dialable) return null
        val permits = { allowed(run, target, captured) }
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
    private fun current(run: Owner) = synchronized(lock) { !closed && owners.value === run } && isCurrent(run.team)
    private fun checkCurrent(run: Owner) = check(current(run)) { "Account session changed" }
    private fun allowed(run: Owner, target: NativeComputerTarget, captured: NativeMacDialIntent) =
        runCatching { current(run) && intent(run, target) == captured }.getOrDefault(false)

    private fun publish(run: Owner) {
        val targets = run.account.settings.state.value.values.filterValues { it.method == NativeMacConnectionMethod.TAILSCALE }.keys
        val resolved = targets.associateWith { id ->
            val target = NativeComputerTarget(id.deviceId, checkNotNull(id.buildTag), "Mac")
            target to runCatching { intent(run, target) }.getOrNull()
        }
        checkCurrent(run)
        mutableState.value = NativeSavedTailscaleState(run.team, resolved.mapValues { (_, value) ->
            value.second?.let { key(value.first, it) } ?: "unavailable"
        })
        run.connections.retain(resolved.values.mapNotNull { (target, intent) -> intent?.takeIf { it.dialable }?.let { key(target, it) } }.toSet())
    }

    override fun close() {
        val previous = synchronized(lock) { closed = true; owners.value.also { owners.value = null } }
        mutableState.value = NativeSavedTailscaleState(teams.value.scope, error = "Saved connections are closed")
        scope.cancel(); previous?.connections?.close()
    }
}
