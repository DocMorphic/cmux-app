package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** One connection incarnation. Independent events are complete, unframed JSON payloads. */
internal interface MobileRpcTransport : AutoCloseable {
    fun diagnostics(): MobileTransportDiagnostics? = null
    val independentEvents: Flow<ByteArray>? get() = null
    val surfaceEventLanes: Boolean get() = false
    val disconnections: Flow<Throwable>? get() = null
    val supportsControlRepair: Boolean get() = false
    suspend fun repairControl(silentSinceNanos: Long): MobileControlRepair = MobileControlRepair.Unavailable
    suspend fun openTerminalInput(surfaceId: String): TerminalInputLane? = null
    suspend fun openTerminalOutput(surfaceId: String, cursor: ULong?): TerminalOutputLane? = null
    val supportsArtifactLanes: Boolean get() = false
    suspend fun openArtifact(resource: String): ArtifactLane? = null
    val supportsSimulatorLanes: Boolean get() = false
    val supportsBrowserTunnels: Boolean get() = false
    suspend fun openBrowserTunnel(host: String, port: Int): BrowserTunnelLane? = null
    suspend fun browserListeningPorts(): BrowserTunnelProtocol.ListeningPorts? = null
    suspend fun openSimulator(panelId: String): SimStreamLane? = null
    suspend fun writeWithGeneration(bytes: ByteArray): Long { write(bytes); return 0 }
    suspend fun connect()
    suspend fun read(): ByteArray?
    suspend fun write(bytes: ByteArray)
}

internal sealed interface MobileControlRepair {
    data class Repaired(val generation: Long) : MobileControlRepair
    data object Unavailable : MobileControlRepair
    data object Closed : MobileControlRepair
}

/** Legacy TCP remains available for existing hosts and protocol fixtures. */
internal interface MobileSocketAuthority : AutoCloseable {
    fun diagnostics(socket: Socket): MobileTransportDiagnostics? = null
    fun start(onInvalidated: () -> Unit)
    fun validate(socket: Socket? = null)
}

internal class SocketMobileRpcTransport(private val route: PairingCode.Route,
                                       private val factory: SocketFactory,
                                       private val authority: MobileSocketAuthority? = null) : MobileRpcTransport {
    private val lock = Any()
    private val connecting = Mutex()
    private var socket: Socket? = null
    private var closed = false

    override fun diagnostics(): MobileTransportDiagnostics = try {
        val current = active()
        check(current.isConnected && !current.isClosed) { "Not connected" }
        authority?.validate(current)
        val result = authority?.diagnostics(current) ?: MobileTransportDiagnostics.tcp()
        authority?.validate(current)
        synchronized(lock) { check(!closed && socket === current) { "Connection closed" } }
        result
    } catch (failure: Throwable) { close(); throw failure }

    override suspend fun connect() = connecting.withLock {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                check(!closed) { "Connection closed" }
                if (socket?.isConnected == true) return@withContext
            }
            try {
                authority?.start(::close)
                authority?.validate()
                val candidate = synchronized(lock) {
                    check(!closed) { "Connection closed" }
                    factory.createSocket().also { socket = it }
                }
                candidate.connect(InetSocketAddress(route.host, route.port), 15_000)
                candidate.tcpNoDelay = true
                authority?.validate(candidate)
                synchronized(lock) { check(!closed && socket === candidate) { "Connection closed" } }
            } catch (error: Throwable) { close(); throw error }
        }
    }
    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val current = active()
            authority?.validate(current)
            val bytes = ByteArray(64 * 1024)
            val count = current.getInputStream().read(bytes)
            authority?.validate(current)
            if (count < 0) null else bytes.copyOf(count)
        } catch (error: Throwable) { close(); throw error }
    }
    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val current = active()
            authority?.validate(current)
            current.getOutputStream().apply { write(bytes); flush() }
            Unit
        } catch (error: Throwable) { close(); throw error }
    }
    private fun active() = synchronized(lock) { check(!closed); checkNotNull(socket) { "Not connected" } }
    override fun close() {
        val previous = synchronized(lock) { closed = true; socket.also { socket = null } }
        try { previous?.close() } finally { authority?.close() }
    }
}
