package io.github.docmorphic.cmuxapp.iroh

import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.RelayConfig
import computer.iroh.RelayMap
import computer.iroh.RelayMode
import computer.iroh.presetMinimal
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.URI

class IrxRelayCredential(val url: String, val token: String, val expiresAt: Long) {
    init {
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(token.isNotBlank() && token.length <= 8192)
    }
    override fun toString() = "IrxRelayCredential(url=$url, expiresAt=$expiresAt)"
}

/** One endpoint for one enrolled installation. Mobile clients advertise no initial remote streams. */
class IrxEndpointRuntime private constructor(private val endpoint: Endpoint,
                                           credentials: List<IrxRelayCredential>,
                                           private val now: () -> Long) : AutoCloseable {
    private val lock = Any()
    private val relays = Mutex()
    private var closed = false
    private var closing: Job? = null
    private val sessions = mutableSetOf<IrxClientSession>()
    private val installed = credentials.associateBy { it.url }.toMutableMap()

    /** Upsert changed tokens before retiring expired URLs; live relay links survive token rotation. */
    suspend fun updateCredentials(credentials: List<IrxRelayCredential>) = relays.withLock {
        synchronized(lock) { check(!closed) { "Irx endpoint closed" } }
        val usable = credentials.filter { it.expiresAt > now() }
        for (credential in usable) {
            if (installed[credential.url]?.token != credential.token) {
                endpoint.insertRelay(RelayConfig(credential.url, authToken = credential.token))
            }
            synchronized(lock) { check(!closed) { "Irx endpoint closed" } }
            installed[credential.url] = credential
        }
        // Omitted but still-valid credentials can carry an existing relay connection until expiry.
        for (url in installed.filterValues { it.expiresAt <= now() }.keys.toList()) {
            endpoint.removeRelay(url)
            installed.remove(url)
        }
    }

    suspend fun dial(peerHex: String, relayUrl: String, permits: () -> Boolean,
                     directAddresses: List<String> = emptyList()): IrxClientSession {
        require(directAddresses.size <= 8)
        require(peerHex.matches(Regex("[0-9a-f]{64}")))
        require(URI(relayUrl).scheme == "https")
        requireAuthority(permits)
        val peer = peerHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val connection = withTimeout(20_000) {
            EndpointId.fromBytes(peer).use { id ->
                EndpointAddr(id, relayUrl, directAddresses).use { address -> endpoint.connect(address, IrxWire.ALPN.toByteArray()) }
            }
        }
        // Admission takes ownership of the connection, including failed admission cleanup.
        var admissionOwnsConnection = false
        val admitted = try {
            requireAuthority(permits)
            admissionOwnsConnection = true
            IrxClientSession.admit(connection, peer)
        } catch (failure: Throwable) {
            // If authority changed before admit took ownership, close this candidate explicitly.
            if (!admissionOwnsConnection) {
                runCatching { connection.close(0, IrxWire.CloseCode.USER_REQUESTED.reason()) }
                connection.close()
            }
            throw failure
        }
        try {
            requireAuthority(permits)
            admitted.authorizeDirectPaths()
            requireAuthority(permits)
            synchronized(lock) {
                check(!closed)
                sessions.removeAll { it.isClosed }
                check(sessions.size < 64) { "Too many active Mac sessions" }
                sessions += admitted
            }
            return admitted
        } catch (failure: Throwable) { admitted.close(); throw failure }
    }

    fun status(): IrxEndpointStatus = synchronized(lock) {
        if (closed) IrxEndpointStatus(false, null) else IrxEndpointStatus.read(endpoint)
    }

    private fun requireAuthority(permits: () -> Boolean) {
        if (!permits() || synchronized(lock) { closed }) throw CancellationException("Irx account or computer access changed")
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            sessions.forEach { runCatching { it.close() } }
            sessions.clear()
            closing = cleanup.launch {
                try { endpoint.shutdown() } finally { endpoint.close() }
            }
        }
    }

    suspend fun awaitClosed() { synchronized(lock) { closing }?.join() }

    companion object {
        private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        suspend fun bind(key: IrohInstallationKey, credentials: List<IrxRelayCredential>,
                         now: () -> Long = { System.currentTimeMillis() / 1000 }): IrxEndpointRuntime {
            val usable = credentials.filter { it.expiresAt > now() }
            if (usable.isEmpty()) throw IOException("No valid Iroh relay credentials")
            require(usable.size <= 16 && usable.map { it.url }.distinct().size == usable.size)
            val seed = key.seedForEndpoint()
            val bound = try {
                RelayMap.empty().use { map ->
                    usable.forEach { map.insert(RelayConfig(it.url, authToken = it.token)) }
                    RelayMode.custom(map).use { mode ->
                        Endpoint.bind(EndpointOptions(preset = presetMinimal(), secretKey = seed,
                            alpns = listOf(IrxWire.ALPN.toByteArray()), relayMode = mode,
                            portMappingEnabled = false, deferNatTraversalUntilAuthorized = true,
                            initialMaxConcurrentBiStreams = 0uL, initialMaxConcurrentUniStreams = 0uL))
                    }
                }
            } finally { seed.fill(0) }
            try {
                withTimeout(20_000) { bound.online() }
                return IrxEndpointRuntime(bound, usable, now)
            } catch (failure: Throwable) {
                withContext(NonCancellable) { try { bound.shutdown() } finally { bound.close() } }
                throw failure
            }
        }
    }
}
