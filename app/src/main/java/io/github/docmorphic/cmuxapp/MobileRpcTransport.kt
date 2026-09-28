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
    val independentEvents: Flow<ByteArray>? get() = null
    val surfaceEventLanes: Boolean get() = false
    val disconnections: Flow<Throwable>? get() = null
    val supportsControlRepair: Boolean get() = false
    suspend fun repairControl(): MobileControlRepair = MobileControlRepair.Unavailable
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
internal class SocketMobileRpcTransport(private val route: PairingCode.Route,
                                       private val factory: SocketFactory) : MobileRpcTransport {
    private val lock = Any()
    private val connecting = Mutex()
    private var socket: Socket? = null
    private var closed = false

    override suspend fun connect() = connecting.withLock {
        withContext(Dispatchers.IO) {
            val candidate = synchronized(lock) {
                check(!closed) { "Connection closed" }
                if (socket?.isConnected == true) return@withContext
                factory.createSocket().also { socket = it }
            }
            try {
                candidate.connect(InetSocketAddress(route.host, route.port), 15_000)
                candidate.tcpNoDelay = true
                synchronized(lock) { check(!closed && socket === candidate) { "Connection closed" } }
            } catch (error: Throwable) { close(); throw error }
        }
    }
    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        val bytes = ByteArray(64 * 1024)
        val count = active().getInputStream().read(bytes)
        if (count < 0) null else bytes.copyOf(count)
    }
    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        active().getOutputStream().apply { write(bytes); flush() }
        Unit
    }
    private fun active() = synchronized(lock) { check(!closed); checkNotNull(socket) { "Not connected" } }
    override fun close() {
        val previous = synchronized(lock) { closed = true; socket.also { socket = null } }
        previous?.close()
    }
}
