package io.github.docmorphic.cmuxapp

import io.github.docmorphic.cmuxapp.iroh.IrxWire
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal interface BrowserTunnelLane : AutoCloseable {
    suspend fun read(maximumBytes: Int = 64 * 1024): ByteArray?
    suspend fun write(bytes: ByteArray)
    suspend fun finishSending()
}

/** Independent framed handshake followed by unframed TCP bytes, never control RPC bytes. */
internal interface BrowserTunnelWire : AutoCloseable {
    suspend fun read(maximumBytes: Int): ByteArray
    suspend fun write(bytes: ByteArray)
    suspend fun finishSending()
}

internal object BrowserTunnelProtocol {
    const val CAPABILITY = "browser.tunnel.v1"
    enum class Status(val wire: String) {
        CONNECTED("connected"), DENIED("denied"), REFUSED("refused"), HOST_UNREACHABLE("host_unreachable"),
        NETWORK_UNREACHABLE("network_unreachable"), TIMED_OUT("timed_out"), UNRESOLVED("unresolved"), BUSY("busy"), FAILED("failed")
    }
    class OpenFailure(val status: Status) : IOException("Browser tunnel: ${status.wire}")
    data class ListeningPort(val port: Int, val address: String)
    data class ListeningPorts(val ports: List<ListeningPort>, val allowsNonLoopbackHosts: Boolean)

    fun connect(host: String, port: Int): IrxWire.Descriptor {
        require(port in 1..65535) { "Invalid tunnel port" }
        require(host.isNotEmpty() && host.toByteArray(Charsets.UTF_8).size <= 253 &&
            host.none { it <= ' ' || it == '\u007f' }) { "Invalid tunnel host" }
        return IrxWire.Descriptor(IrxWire.Lane.TCP_CONNECT, host = host, port = port)
    }
    fun status(value: JSONObject): Status {
        IrxWire.requireVersion(value)
        val text = value.opt("status") as? String ?: throw IOException("Missing tunnel status")
        return Status.entries.singleOrNull { it.wire == text } ?: throw IOException("Unknown tunnel status")
    }
    fun ports(value: JSONObject): ListeningPorts {
        IrxWire.requireVersion(value)
        val policy = value.opt("allowsNonLoopbackHosts") as? Boolean ?: throw IOException("Missing tunnel policy")
        val values = value.optJSONArray("ports") ?: throw IOException("Missing tunnel ports")
        val ports = (0 until values.length()).map { index ->
            val item = values.optJSONObject(index) ?: throw IOException("Invalid listening port")
            val raw = item.opt("port")
            if (raw !is Int && raw !is Long) throw IOException("Invalid listening port number")
            val port = (raw as Number).toLong()
            if (port !in 1..65535) throw IOException("Invalid listening port number")
            val address = item.opt("address") as? String ?: throw IOException("Missing listening address")
            if (!isListedLoopback(address)) throw IOException("Invalid listening loopback address")
            ListeningPort(port.toInt(), address)
        }
        return ListeningPorts(ports, policy)
    }
    // The host scanner reports canonical loopback literals, never names requiring phone DNS.
    private fun isListedLoopback(address: String): Boolean {
        if (address == "::1") return true
        val octets = address.split('.')
        return octets.size == 4 && octets[0] == "127" && octets.all {
            it.isNotEmpty() && it.length <= 3 && it.all { char -> char in '0'..'9' } &&
                (it.length == 1 || it[0] != '0') && it.toInt() in 0..255
        }
    }
}

internal class IrxBrowserTunnel private constructor(private val wire: BrowserTunnelWire,
    private val permits: () -> Unit) : BrowserTunnelLane {
    private val closed = AtomicBoolean()
    private val writing = Mutex()
    private val reading = Mutex()
    private var sendingFinished = false
    private fun checkCurrent() { check(!closed.get()) { "Browser tunnel closed" }; permits() }
    override suspend fun read(maximumBytes: Int): ByteArray? = reading.withLock {
        require(maximumBytes in 1..64 * 1024)
        try {
            checkCurrent()
            val bytes = wire.read(maximumBytes)
            currentCoroutineContext().ensureActive(); checkCurrent()
            require(bytes.size <= maximumBytes) { "Tunnel reader exceeded requested size" }
            bytes.takeIf { it.isNotEmpty() }
        } catch (failure: Throwable) { close(); throw failure }
    }
    override suspend fun write(bytes: ByteArray) {
        require(bytes.size <= 64 * 1024) { "Tunnel write too large" }
        val snapshot = bytes.copyOf()
        writing.withLock {
            try {
                checkCurrent(); check(!sendingFinished) { "Tunnel sending side finished" }
                wire.write(snapshot)
                currentCoroutineContext().ensureActive(); checkCurrent()
            } catch (failure: Throwable) { close(); throw failure }
        }
    }
    override suspend fun finishSending() = writing.withLock {
        try {
            checkCurrent()
            if (!sendingFinished) { sendingFinished = true; wire.finishSending() }
            currentCoroutineContext().ensureActive(); checkCurrent()
        } catch (failure: Throwable) { close(); throw failure }
    }
    override fun close() { if (closed.compareAndSet(false, true)) wire.close() }

    companion object {
        private suspend fun reply(wire: BrowserTunnelWire, timeoutMillis: Long): JSONObject? = coroutineScope {
            // Also closes a provider whose started native read is slow to observe cancellation.
            val deadline = launch(start = CoroutineStart.UNDISPATCHED) { delay(timeoutMillis); wire.close() }
            try { withTimeout(timeoutMillis) { IrxWire.read(wire::read) } }
            finally { deadline.cancel() }
        }
        suspend fun open(wire: BrowserTunnelWire, permits: () -> Unit, replyTimeoutMillis: Long = 15_000): IrxBrowserTunnel {
            try {
                permits()
                val reply = reply(wire, replyTimeoutMillis)
                    ?: throw EOFException("Missing tunnel reply")
                currentCoroutineContext().ensureActive(); permits()
                val status = BrowserTunnelProtocol.status(reply)
                if (status != BrowserTunnelProtocol.Status.CONNECTED) throw BrowserTunnelProtocol.OpenFailure(status)
                return IrxBrowserTunnel(wire, permits)
            } catch (failure: Throwable) { wire.close(); throw failure }
        }
        suspend fun listeningPorts(wire: BrowserTunnelWire, permits: () -> Unit,
            replyTimeoutMillis: Long = 15_000): BrowserTunnelProtocol.ListeningPorts {
            try {
                permits()
                val reply = reply(wire, replyTimeoutMillis)
                    ?: throw EOFException("Missing listening ports reply")
                currentCoroutineContext().ensureActive(); permits()
                return BrowserTunnelProtocol.ports(reply)
            } finally { wire.close() }
        }
    }
}
