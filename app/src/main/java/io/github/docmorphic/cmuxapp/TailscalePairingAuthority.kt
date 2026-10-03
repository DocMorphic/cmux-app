package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

internal data class TailscaleSavedGrant(val id: String, val user: String, val team: String,
    val source: String, val device: String, val build: String?, val route: PairingCode.Route) {
    fun matches(status: JSONObject) = canonicalMacDeviceId(status.optString("mac_device_id")) == device &&
        status.optString("mac_instance_tag").takeIf { !status.isNull("mac_instance_tag") && it.isNotBlank() } == build
    override fun toString() = "TailscaleSavedGrant(redacted)"
}

/** Stored inside the existing encrypted credential transaction, never inferred from saved QR rows. */
internal class TailscaleGrantStore(private val read: () -> JSONObject?,
    private val update: ((JSONObject) -> Unit) -> Unit) {
    fun find(scope: NativeTeamScope, source: String): TailscaleSavedGrant? =
        decode(read()).singleOrNull { it.user == scope.userId && it.team == scope.teamId && it.source == source }

    fun owners(source: String, device: String, build: String?): Set<Pair<String, String>> = decode(read())
        .filter { it.source == source && it.device == canonicalMacDeviceId(device) && it.build == build }
        .map { it.user to it.team }.toSet()

    fun computer(scope: NativeTeamScope, target: NativeComputerTarget): List<TailscaleSavedGrant> = decode(read())
        .filter { it.user == scope.userId && it.team == scope.teamId &&
            it.device == canonicalMacDeviceId(target.deviceId) && it.build == target.buildTag }
        .asReversed().distinctBy { it.route }

    fun removeRoute(scope: NativeTeamScope, target: NativeComputerTarget, grant: TailscaleSavedGrant, permits: () -> Boolean) {
        check(permits()) { "Account or team changed. Reopen Computer Details." }
        update { state ->
            check(state.optString("task_session") == scope.login && state.optString("refresh_token").isNotBlank())
            val values = decode(state)
            check(grant in values && grant.user == scope.userId && grant.team == scope.teamId &&
                grant.device == canonicalMacDeviceId(target.deviceId) && grant.build == target.buildTag) { "The route changed. Reopen its menu." }
            state.put(FIELD, encode(values.filterNot { it.user == grant.user && it.team == grant.team &&
                it.device == grant.device && it.build == grant.build && it.route == grant.route }))
        }
    }

    fun save(scope: NativeTeamScope, grant: TailscaleSavedGrant, replacing: TailscaleSavedGrant? = null,
        permits: () -> Boolean) {
        check(permits()) { "Account or team changed. Pair this Mac again." }
        update { state ->
            // Check the login inside the atomic credential transaction. Do not acquire the
            // teams lock here; the opposite lock order is used by account refresh.
            check(state.optString("task_session") == scope.login &&
                state.optString("refresh_token").isNotBlank()) { "Account or team changed. Pair this Mac again." }
            require(grant.user == scope.userId && grant.team == scope.teamId)
            val previous = decode(state)
            if (replacing != null) check(replacing in previous && replacing.user == grant.user && replacing.team == grant.team &&
                replacing.device == grant.device && replacing.build == grant.build) { "The route changed. Reopen its editor." }
            val values = previous.filterNot { old ->
                (old.user == grant.user && old.team == grant.team && old.source == grant.source) ||
                    (replacing != null && old.user == replacing.user && old.team == replacing.team &&
                        old.device == replacing.device && old.build == replacing.build && old.route == replacing.route)
            } + grant
            require(values.size <= 256) { "Too many saved Tailscale connections" }
            val encoded = encode(values)
            decode(JSONObject().put(FIELD, encoded)) // Validate before the credential commit.
            state.put(FIELD, encoded)
        }
    }

    companion object {
        private const val FIELD = "tailscale_grants_v1"
        fun source(pairing: PairingCode.Tailscale): String {
            require(pairing.routes.size in 1..8)
            require(pairing.stackUserId == null || (pairing.stackUserId.isNotBlank() && pairing.stackUserId.length <= 128))
            val routes = pairing.routes.map {
                require(it.port in 1..65535)
                val host = TailscalePeerAddress.canonical(it.host) ?: it.host.lowercase().also { name ->
                    require(TailscalePeerAddress.isMagicDnsName(name))
                }
                JSONArray(listOf(host, it.port))
            }
            val bytes = JSONArray(listOf(pairing.stackUserId, JSONArray(routes))).toString().toByteArray()
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        fun removeForCode(state: JSONObject, code: String, scope: NativeTeamScope? = null) {
            val pairing = PairingCodeParser.parse(code).getOrNull() as? PairingCode.Tailscale ?: return
            val key = source(pairing)
            state.put(FIELD, encode(decode(state).filterNot { it.source == key &&
                (scope == null || (it.user == scope.userId && it.team == scope.teamId)) }))
        }
        fun removeComputer(state: JSONObject, scope: NativeTeamScope, device: String, build: String?) {
            state.put(FIELD, encode(decode(state).filterNot { it.user == scope.userId && it.team == scope.teamId &&
                it.device == canonicalMacDeviceId(device) && it.build == build }))
        }
        private fun encode(values: List<TailscaleSavedGrant>) = JSONArray(values.map { grant ->
            JSONObject().put("id", grant.id).put("user", grant.user).put("team", grant.team).put("source", grant.source)
                .put("device", grant.device).put("build", grant.build ?: JSONObject.NULL)
                .put("host", grant.route.host).put("port", grant.route.port)
        })
        private fun decode(state: JSONObject?): List<TailscaleSavedGrant> {
            if (state == null || !state.has(FIELD)) return emptyList()
            val values = state.getJSONArray(FIELD)
            require(values.length() <= 256)
            val grants = (0 until values.length()).map { index ->
                val row = values.getJSONObject(index)
                fun text(key: String, limit: Int = 128): String = (row.get(key) as String).also {
                    require(it.isNotBlank() && it == it.trim() && it.length <= limit && it.none(Char::isISOControl))
                }
                val id = text("id"); require(UUID.fromString(id).toString() == id)
                val source = text("source", 64); require(source.length == 64 && source.all { it in "0123456789abcdef" })
                val device = text("device"); require(canonicalMacDeviceId(device) == device)
                require(row.has("build"))
                val build = if (row.isNull("build")) null else text("build", 64)
                val host = text("host", 64); require(TailscalePeerAddress.canonical(host) == host)
                val port = row.get("port") as Int; require(port in 1..65535)
                TailscaleSavedGrant(id, text("user"), text("team"), source, device, build, PairingCode.Route(host, port))
            }
            require(grants.map { Triple(it.user, it.team, it.source) }.distinct().size == grants.size)
            require(grants.map { it.id }.distinct().size == grants.size)
            return grants
        }
    }
}

/** Explicit confirmation is memory-only; an authenticated probe promotes only its successful numeric route. */
internal class TailscalePairingAuthority(
    private val current: () -> NativeTeamScope?,
    private val permits: (NativeTeamScope) -> Boolean,
    private val grants: TailscaleGrantStore,
    private val resolve: suspend (PairingCode.Route, () -> Boolean) -> PairingCode.Route,
    private val dial: suspend (PairingCode.Route, () -> Boolean, suspend () -> String?) -> MobileRpcClient,
    private val expected: (PairingCode.Tailscale) -> NativeCredentialStore.PairedMac? = { null },
    private val replacing: TailscaleSavedGrant? = null,
    private val admitCompatibility: suspend (NativeTeamScope, MobileRpcClient, JSONObject) -> Unit = { _, _, _ -> }
) : AutoCloseable {
    private data class Consent(val scope: NativeTeamScope, val nonce: String = UUID.randomUUID().toString()) {
        val resolved = ConcurrentHashMap<PairingCode.Route, PairingCode.Route>()
    }
    private val lock = Any()
    private val consents = mutableMapOf<String, Consent>()
    private val clients = mutableMapOf<MobileRpcClient, () -> Boolean>()
    private var closed = false

    fun authorize(pairing: PairingCode.Tailscale) {
        val owner = owner(pairing)
        synchronized(lock) {
            check(!closed)
            // A replacement confirmation cannot revive a pending attempt from an earlier confirmation.
            consents[TailscaleGrantStore.source(pairing)] = Consent(owner)
        }
    }

    fun allowsSaved(pairing: PairingCode.Tailscale): Boolean = runCatching {
        val owner = owner(pairing)
        synchronized(lock) { !closed } && grants.find(owner, TailscaleGrantStore.source(pairing)) != null
    }.getOrDefault(false)

    private fun owner(pairing: PairingCode.Tailscale): NativeTeamScope {
        val owner = checkNotNull(current()) { "Refresh your account teams before pairing this Mac." }
        check(permits(owner)) { "Account or team changed. Pair this Mac again." }
        require(pairing.stackUserId == null || pairing.stackUserId == owner.userId) { "This Mac is signed in to a different cmux account" }
        return owner
    }

    suspend fun connect(pairing: PairingCode.Tailscale, expectedScope: NativeTeamScope? = null, token: suspend () -> String?): MobileRpcClient {
        val owner = owner(pairing)
        check(expectedScope == null || expectedScope == owner) { "Account or team changed. Reconnect to the Mac." }
        val source = TailscaleGrantStore.source(pairing)
        val consent = synchronized(lock) { check(!closed); consents[source]?.takeIf { it.scope == owner } }
        val saved = grants.find(owner, source)
        check(consent != null || saved != null) { "Scan or paste this Mac’s pairing code and confirm Connect to authorize Tailscale." }
        val capturedExpected = expected(pairing)
        val promoted = AtomicReference<TailscaleSavedGrant?>(if (consent == null) saved else null)
        fun allowed(): Boolean = runCatching {
            permits(owner) && synchronized(lock) { !closed } &&
                (promoted.get()?.let { grants.find(owner, source) == it }
                    ?: synchronized(lock) { consents[source] == consent })
        }.getOrDefault(false)
        fun requireAllowed() = check(allowed()) { "The Tailscale authorization changed. Pair this Mac again." }
        val routes = if (consent != null) pairing.routes else listOf(checkNotNull(saved).route)
        var lastError: Exception? = null
        for (hint in routes) {
            currentCoroutineContext().ensureActive(); requireAllowed()
            var candidate: MobileRpcClient? = null
            try {
                // Saved reconnects use the captured numeric destination, never another DNS answer.
                val route = if (consent == null) hint else consent.resolved[hint] ?: resolve(hint, ::allowed).let { resolved ->
                    require(TailscalePeerAddress.canonical(resolved.host) == resolved.host && resolved.port == hint.port)
                    val numericHint = TailscalePeerAddress.canonical(hint.host)
                    require(numericHint == null || numericHint == resolved.host)
                    requireAllowed()
                    // One user confirmation cannot follow a different DNS answer on a later retry.
                    consent.resolved.putIfAbsent(hint, resolved) ?: resolved
                }
                require(TailscalePeerAddress.canonical(route.host) == route.host && route.port == hint.port)
                requireAllowed()
                val client = dial(route, ::allowed) {
                    requireAllowed()
                    val value = token()
                    requireAllowed()
                    checkNotNull(value?.takeIf { it.isNotBlank() }) { "Sign in before connecting to this Mac." }
                }
                candidate = client
                requireAllowed()
                synchronized(lock) { check(!closed); clients[client] = ::allowed }
                client.connect()
                val status = client.hostStatus()
                requireAllowed()
                val device = status.optString("mac_device_id")
                require(device.isNotBlank() && device == device.trim() && device.length <= 128) { "The Mac did not provide a valid device identity." }
                capturedExpected?.requireMatchingHost(status)
                saved?.let { check(it.matches(status)) { "This route reaches a different Mac or cmux installation. Pair the intended Mac again." } }
                // Host status alone is not a successful account-authenticated session.
                client.workspaces()
                currentCoroutineContext().ensureActive(); requireAllowed()
                admitCompatibility(owner, client, status)
                requireAllowed()
                if (consent != null) {
                    val build = status.optString("mac_instance_tag").takeIf { !status.isNull("mac_instance_tag") && it.isNotBlank() }
                    val grant = TailscaleSavedGrant(UUID.randomUUID().toString(), owner.userId, owner.teamId, source,
                        canonicalMacDeviceId(device), build, route)
                    grants.save(owner, grant, replacing, ::allowed)
                    promoted.set(grant)
                    synchronized(lock) { if (consents[source] == consent) consents.remove(source) }
                }
                requireAllowed()
                return client
            } catch (failure: Exception) {
                candidate?.let { synchronized(lock) { clients.remove(it) }; it.close() }
                if (failure is CancellationException || failure is TailscaleReadinessException) throw failure
                lastError = failure
            }
        }
        throw lastError ?: IllegalStateException("No Tailscale route is reachable")
    }

    /** Account/team changes close blocked reads too; write/read guards check synchronously. */
    fun retireInvalid() {
        // Never invoke scope/storage callbacks while holding the registry lock.
        val pending = synchronized(lock) { consents.toMap() }
        val invalid = pending.filterValues { !permits(it.scope) }
        val observed = synchronized(lock) { clients.toMap() }
        val retired = observed.filter { (client, allows) -> client.isClosed || !allows() }.keys
        synchronized(lock) {
            invalid.forEach { (key, value) -> if (consents[key] == value) consents.remove(key) }
            retired.forEach { clients.remove(it) }
        }
        retired.forEach { it.close() }
    }
    override fun close() {
        val retired = synchronized(lock) {
            closed = true; consents.clear(); clients.keys.toList().also { clients.clear() }
        }
        retired.forEach { it.close() }
    }
}
