package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal class IrohV2Relay(val url: String, val token: String, val expiresAt: Long, val refreshAfter: Long) {
    override fun toString() = "IrohV2Relay(url=$url, expiresAt=$expiresAt)"
}

internal data class IrohV2Computer(val recordId: String, val endpointId: String, val deviceId: String,
    val buildTag: String, val name: String, val relayUrls: List<String>)

internal data class IrohV2ControlState(
    val ready: Boolean = false,
    val mode: String? = null,
    val computers: List<IrohV2Computer> = emptyList(),
    val directoryRevision: Long? = null,
    val permissionExpiresAt: Long? = null,
    val relays: List<IrohV2Relay> = emptyList(),
    val failure: String? = null
)

/** One authenticated account/team incarnation. A replacement owns a separate instance. */
internal class IrohV2ControlSession(
    private val requests: IrohV2SignedRequests,
    private val accessToken: suspend (forceRefresh: Boolean) -> String,
    private val isScopeCurrent: () -> Boolean,
    private val origin: HttpUrl = "https://cmux-iroh-v2.debussy.workers.dev/".toHttpUrl(),
    private val client: OkHttpClient = OkHttpClient(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val maintainAutomatically: Boolean = true
) : AutoCloseable {
    private class Ticket(val token: String, val expiresAt: Long, val refreshAfter: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    private val lock = Any()
    private val mutableState = MutableStateFlow(IrohV2ControlState())
    val state = mutableState.asStateFlow()
    private var closed = false
    private var started = false
    private var epoch = 0L
    private var socket: IrohV2ControlSocket? = null
    private var http: IrohV2ControlHttp? = null
    private var receiver: Job? = null
    private var maintenance: Job? = null
    private var directoryWorker: Job? = null
    private var watchdog: Job? = null
    private val directoryChanges = Channel<Unit>(Channel.CONFLATED)
    private var ticket: Ticket? = null
    private var ownRecordId: String? = null
    private var wantedRevision = 0L
    private val revokedRecords = mutableMapOf<String, Long>()
    private var cooldownUntil = 0L
    private var lastDirectoryAt = 0L
    private val renewalRetryAt = mutableMapOf<String, Long>() // Owned by the operation mutex.

    suspend fun connect(): IrohV2ControlState = operations.withLock {
        val run = synchronized(lock) {
            check(!closed && isScopeCurrent()) { "Account session changed" }
            check(!started) { "Create a new control session to reconnect" }
            started = true
            ++epoch
        }
        try {
            val transport = IrohV2ControlHttp(client)
            try { mutate(run) { http = transport } }
            catch (error: Throwable) { transport.close(); throw error }
            // Scope and lease checks must run even while a request owns the operation mutex.
            mutate(run) { watchdog = scope.launch { watchAuthority(run) } }
            var setup = requests.setup()
            var ready: JSONObject
            val initialAuthorization = authorization(run)
            try {
                val opened = IrohV2ControlSocket.open(client, requests.socketRequest(origin, initialAuthorization, setup), setup.getString("requestId"))
                try { mutate(run) { socket = opened.first; mutableState.value = mutableState.value.copy(mode = "websocket") } }
                catch (error: Throwable) { opened.first.close(); throw error }
                ready = opened.second
                mutate(run) { receiver = scope.launch { receive(run, opened.first) } }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                checkCurrent(run)
                if (!permitsHttp(error)) throw error
                // A new proof avoids reusing a nonce possibly consumed by a lost socket setup.
                setup = requests.setup()
                ready = transport.exchange(requests.httpSessionRequest(origin, authorization(run), setup),
                    setup.getString("requestId"), "session.ready.v1")
                mutate(run) { mutableState.value = mutableState.value.copy(mode = "http") }
            }
            checkCurrent(run)
            mutate(run) { wantedRevision = maxOf(wantedRevision, IrohV2Wire.integer(ready, "teamRevision")) }
            ready.optJSONObject("ticket")?.let { value -> mutate(run) { ticket = parseTicket(value) } }
            val record = when {
                ready.has("challenge") -> perform(run, requests.registration(ready.getJSONObject("challenge"))).getJSONObject("device")
                ready.has("device") -> ready.getJSONObject("device")
                else -> throw IOException("Iroh service omitted enrollment result")
            }
            val enrolled = requests.acceptDevice(record)
            mutate(run) {
                ownRecordId = enrolled.getString("deviceRecordId")
                if (ownRecordId in revokedRecords) throw IrohV2ServerFailure("device_revoked", false)
            }
            if (!IrohV2SigningCodec.encode(enrolled.getJSONObject("descriptor").getJSONObject("metadata"))
                    .contentEquals(IrohV2SigningCodec.encode(requests.device().getJSONObject("metadata")))) {
                perform(run, requests.operation("device.metadata.v1").put("metadata", requests.device().getJSONObject("metadata")))
            }
            directory(run)
            relays(run)
            mutate(run) { mutableState.value = mutableState.value.copy(ready = true, failure = null) }
            mutate(run) {
                directoryWorker = scope.launch { drainDirectoryChanges(run) }
                if (maintainAutomatically) maintenance = scope.launch { maintain(run) }
            }
            mutableState.value
        } catch (error: Throwable) {
            invalidate(run, error)
            throw error
        }
    }

    suspend fun refreshDirectory(): IrohV2ControlState = operations.withLock {
        val run = currentRun()
        try { directory(run); mutableState.value }
        catch (error: Throwable) { handleFailure(run, error); throw error }
    }

    suspend fun refreshRelays(): List<IrohV2Relay> = operations.withLock {
        val run = currentRun()
        try { relays(run); mutableState.value.relays }
        catch (error: Throwable) { handleFailure(run, error); throw error }
    }

    private suspend fun directory(run: Long) {
        repeat(3) {
            var revision: Long? = null
            var expires: Long? = null
            var cursor: String? = null
            var restart = false
            val cursors = mutableSetOf<String>()
            val records = mutableSetOf<String>()
            val endpoints = mutableSetOf<String>()
            val computers = mutableListOf<IrohV2Computer>()
            var count = 0
            var pages = 0
            do {
                val request = requests.operation("directory.request.v1")
                cursor?.let { request.put("cursor", it) }
                revision?.let { request.put("haveRevision", it) }
                val page = try { perform(run, request).getJSONObject("directory") }
                catch (error: IrohV2ServerFailure) {
                    if (error.code == "resync_required") { restart = true; break } else throw error
                }
                if (page.getString("teamId") != requests.teamId) throw IOException("Iroh directory team mismatch")
                val pageRevision = IrohV2Wire.integer(page, "revision")
                if (revision != null && pageRevision != revision) { restart = true; break }
                revision = pageRevision
                val permissionExpiry = IrohV2Wire.integer(page, "permissionExpiresAt")
                if (permissionExpiry <= now()) throw IOException("Iroh directory permission expired")
                expires = minOf(expires ?: permissionExpiry, permissionExpiry)
                val devices = page.getJSONArray("devices")
                if (devices.length() > 1024) throw IOException("Iroh directory page too large")
                count += devices.length()
                if (count > 4096 || ++pages > 4096) throw IOException("Iroh directory capacity exceeded")
                for (index in 0 until devices.length()) {
                    val record = devices.getJSONObject(index)
                    val id = record.getString("deviceRecordId")
                    val descriptor = record.getJSONObject("descriptor")
                    val endpoint = descriptor.getString("endpointId")
                    if (id.length !in 1..128 || !records.add(id) || !endpoints.add(endpoint)) throw IOException("Duplicate or invalid Iroh device")
                    val identity = descriptor.getJSONObject("identity")
                    val ours = requests.device().getJSONObject("identity")
                    for (field in listOf("environment", "projectId", "teamId"))
                        if (identity.getString(field) != ours.getString(field)) throw IOException("Iroh computer scope mismatch")
                    if (!endpoint.matches(Regex("[0-9a-f]{64}"))) throw IOException("Invalid Iroh endpoint key")
                    if (record.get("revoked") !is Boolean) throw IOException("Invalid Iroh revocation flag")
                    if (record.getBoolean("revoked")) continue
                    val metadata = descriptor.getJSONObject("metadata")
                    if (metadata.getString("platform") != "mac" || metadata.get("pairingEnabled") != true) continue
                    val relayUrls = metadata.getJSONArray("relayURLs").strings().map(::relayUrl)
                    computers += IrohV2Computer(id, endpoint, identity.getString("deviceId"), identity.getString("buildTag"),
                        metadata.getString("displayName"), relayUrls)
                }
                cursor = if (page.isNull("nextCursor")) null else page.getString("nextCursor")
                if (cursor != null && (cursor.length !in 1..128 || !cursors.add(cursor))) throw IOException("Iroh directory cursor cycle")
            } while (cursor != null)
            if (restart) return@repeat
            var committed = false
            mutate(run) {
                val floor = maxOf(wantedRevision, mutableState.value.directoryRevision ?: 0)
                if (revision != null && revision >= floor && (expires ?: 0) > now()) {
                    val acceptedRevision = revision
                    mutableState.value = mutableState.value.copy(computers = computers.filterNot {
                        (revokedRecords[it.recordId] ?: -1) >= acceptedRevision
                    },
                        directoryRevision = revision, permissionExpiresAt = expires, failure = null)
                    revokedRecords.entries.removeAll { it.value < acceptedRevision }
                    committed = true
                    lastDirectoryAt = now()
                }
            }
            if (committed) return
        }
        throw IrohV2ServerFailure("revision_conflict", true, 1000)
    }

    private suspend fun relays(run: Long) {
        val values = perform(run, requests.operation("relay.request.v1")).getJSONArray("credentials")
        if (values.length() !in 1..16) throw IOException("Invalid Iroh relay count")
        val parsed = (0 until values.length()).map { index ->
            val value = values.getJSONObject(index)
            val expires = IrohV2Wire.integer(value, "expiresAt")
            val refresh = IrohV2Wire.integer(value, "refreshAfter")
            val token = value.getString("token")
            if (expires <= now() || refresh > expires || token.length !in 1..8192) throw IOException("Invalid Iroh relay credential")
            IrohV2Relay(relayUrl(value.getString("relayURL")), token, expires, refresh)
        }
        if (parsed.map { it.url }.distinct().size != parsed.size) throw IOException("Duplicate Iroh relay")
        mutate(run) { mutableState.value = mutableState.value.copy(relays = parsed) }
    }

    private suspend fun perform(run: Long, body: JSONObject): JSONObject {
        checkCurrent(run)
        val active = synchronized(lock) {
            if (now() < cooldownUntil) throw IrohV2ServerFailure("rate_limited", true, (cooldownUntil - now()) * 1000)
            socket to checkNotNull(http)
        }
        try {
            val result = if (active.first != null) active.first!!.request(body) else {
                val request = requests.httpOperationRequest(origin, authorization(run), body, mutableState.value.directoryRevision)
                active.second.exchange(request, body.getString("requestId"), checkNotNull(IrohV2Wire.requestResponses[body.getString("schemaId")]))
            }
            checkCurrent(run)
            return result
        } catch (error: IrohV2ServerFailure) {
            if (error.code == "rate_limited") mutate(run) { cooldownUntil = now() + maxOf(1, ((error.retryAfterMs ?: 60_000) + 999) / 1000) }
            if (error.code in setOf("device_revoked", "team_access_revoked")) invalidate(run, error)
            throw error
        }
    }

    private suspend fun authorization(run: Long, force: Boolean = false): String {
        checkCurrent(run)
        val cached = synchronized(lock) { ticket }
        if (!force && cached != null && cached.expiresAt > now() + 30) return "IrohTicket ${cached.token}"
        val token = accessToken(force)
        checkCurrent(run)
        if (token.isBlank()) throw IOException("Sign in to cmux")
        return "Bearer $token"
    }

    private suspend fun receive(run: Long, incoming: IrohV2ControlSocket) {
        try {
            incoming.events.collect { event ->
                checkCurrent(run)
                if (event.getString("teamId") != requests.teamId) throw IOException("Iroh event team mismatch")
                val revision = IrohV2Wire.integer(event, "revision")
                var ownRevoked = false
                mutate(run) {
                    wantedRevision = maxOf(wantedRevision, revision)
                    if (event.getString("schemaId") == "device.revoked.v1") {
                        val id = event.getString("deviceRecordId")
                        if (id.length !in 1..128) throw IOException("Invalid Iroh revoked device ID")
                        ownRevoked = id == ownRecordId
                        revokedRecords[id] = maxOf(revokedRecords[id] ?: 0, revision)
                        if (revokedRecords.size > 4096) throw IOException("Iroh revocation capacity exceeded")
                        mutableState.value = mutableState.value.copy(computers = mutableState.value.computers.filterNot { it.recordId == id })
                    }
                }
                if (ownRevoked) throw IrohV2ServerFailure("device_revoked", false)
                directoryChanges.trySend(Unit)
            }
        } catch (error: Exception) {
            if (error is IrohV2Unavailable && isCurrent(run)) {
                // Pending calls fail once. Only subsequent requests use signed HTTP recovery.
                mutate(run) {
                    socket = null
                    mutableState.value = mutableState.value.copy(mode = "http", failure = "Connection interrupted")
                }
            } else if (error !is CancellationException) invalidate(run, error)
        }
    }

    private suspend fun drainDirectoryChanges(run: Long) {
        for (ignored in directoryChanges) {
            operations.withLock {
                if (!isCurrent(run)) return
                if ((mutableState.value.directoryRevision ?: -1) < synchronized(lock) { wantedRevision }) {
                    try { directory(run) } catch (error: Exception) { handleFailure(run, error) }
                }
            }
        }
    }

    private suspend fun watchAuthority(run: Long) {
        try {
            while (isCurrent(run)) {
                delay(250)
                mutate(run) {
                    val snapshot = mutableState.value
                    mutableState.value = snapshot.copy(
                        computers = if ((snapshot.permissionExpiresAt ?: Long.MAX_VALUE) <= now()) emptyList() else snapshot.computers,
                        relays = snapshot.relays.filter { it.expiresAt > now() })
                    if ((ticket?.expiresAt ?: Long.MAX_VALUE) <= now()) ticket = null
                }
            }
        } finally { invalidate(run, CancellationException("Iroh account session changed")) }
    }

    private suspend fun maintain(run: Long) {
        while (isCurrent(run)) {
            delay(1000)
            operations.withLock {
                if (!isCurrent(run)) return
                val snapshot = mutableState.value
                if ((snapshot.permissionExpiresAt ?: Long.MAX_VALUE) <= now())
                    mutate(run) { mutableState.value = mutableState.value.copy(computers = emptyList()) }
                if (now() < synchronized(lock) { cooldownUntil }) return@withLock
                val currentTicket = synchronized(lock) { ticket }
                if (currentTicket == null || currentTicket.refreshAfter <= now()) {
                    renew(run, "ticket") {
                        val token = accessToken(false); checkCurrent(run)
                        val response = perform(run, requests.operation("ticket.request.v1").put("stackAccessToken", token))
                        mutate(run) { ticket = parseTicket(response.getJSONObject("ticket")) }
                    }
                }
                if (snapshot.relays.isEmpty() || snapshot.relays.any { it.refreshAfter <= now() })
                    renew(run, "relays") { relays(run) }
                if ((snapshot.permissionExpiresAt ?: 0) - 300 <= now() ||
                    (snapshot.directoryRevision ?: -1) < synchronized(lock) { wantedRevision } ||
                    (snapshot.mode == "http" && now() - lastDirectoryAt >= 30))
                    renew(run, "directory") { directory(run) }
            }
        }
    }

    private suspend fun renew(run: Long, kind: String, action: suspend () -> Unit) {
        if (!isCurrent(run) || now() < synchronized(lock) { cooldownUntil } || now() < (renewalRetryAt[kind] ?: 0)) return
        try { action(); renewalRetryAt.remove(kind) }
        catch (error: Exception) {
            handleFailure(run, error)
            renewalRetryAt[kind] = now() + 30
        }
    }

    private fun parseTicket(value: JSONObject): Ticket {
        val token = value.getString("token")
        val expires = IrohV2Wire.integer(value, "expiresAt")
        val refresh = IrohV2Wire.integer(value, "refreshAfter")
        if (token.length !in 1..8192 || expires <= now() || refresh > expires) throw IOException("Invalid Iroh ticket")
        return Ticket(token, expires, refresh)
    }

    private fun currentRun(): Long = synchronized(lock) { checkCurrent(epoch); epoch }
    private fun isCurrent(run: Long) = synchronized(lock) { !closed && epoch == run && isScopeCurrent() }
    private fun checkCurrent(run: Long) { if (!isCurrent(run)) throw CancellationException("Iroh account session changed") }
    private fun mutate(run: Long, block: () -> Unit) = synchronized(lock) { checkCurrent(run); block() }

    private fun handleFailure(run: Long, error: Throwable) {
        if (!isCurrent(run)) { invalidate(run, error); return }
        if ((error is IrohV2ServerFailure && error.retryable) || error is IrohV2Unavailable ||
            error is TimeoutCancellationException || (error is IrohV2HttpFailure && error.status >= 500)) {
            mutate(run) { mutableState.value = mutableState.value.copy(failure = (error as? IrohV2ServerFailure)?.code ?: "Connection interrupted") }
        } else invalidate(run, error)
    }

    private fun invalidate(run: Long, error: Throwable) {
        val resources = synchronized(lock) {
            if (epoch != run || closed) return
            ++epoch
            ticket = null
            mutableState.value = IrohV2ControlState(failure = (error as? IrohV2ServerFailure)?.code ?: "Connection unavailable")
            (socket to http).also { socket = null; http = null }
        }
        resources.first?.close(); resources.second?.close()
        receiver?.cancel(); maintenance?.cancel(); directoryWorker?.cancel(); watchdog?.cancel()
    }

    override fun close() {
        val resources = synchronized(lock) {
            if (closed) return
            closed = true; ++epoch; ticket = null
            mutableState.value = IrohV2ControlState()
            (socket to http).also { socket = null; http = null }
        }
        resources.first?.close(); resources.second?.close(); scope.cancel()
    }

    private fun permitsHttp(error: Exception) = error is IrohV2Unavailable || error is TimeoutCancellationException ||
        (error is IrohV2HttpFailure && (error.status >= 500 || error.status == 426))

    private fun relayUrl(raw: String): String {
        if (raw.length > 2048) throw IOException("Invalid Iroh relay URL")
        val url = raw.toHttpUrl()
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null)
            throw IOException("Invalid Iroh relay URL")
        return url.toString()
    }
    private fun JSONArray.strings(): List<String> {
        if (length() > 16) throw IOException("Too many Iroh relay URLs")
        return (0 until length()).map { get(it) as? String ?: throw IOException("Invalid Iroh relay URL") }
    }
}
