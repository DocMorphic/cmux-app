package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import java.io.EOFException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.atomic.AtomicBoolean

/** SOCKS5 CONNECT on app loopback only. Each relay retains its backend's borrowed lifetime. */
internal class BrowserSocksProxy private constructor(private val listener: AsynchronousServerSocketChannel,
    private val backend: BrowserTunnelBackend, private val maximumConnections: Int,
    private val handshakeTimeoutMillis: Long) : AutoCloseable {
    val port = (listener.localAddress as InetSocketAddress).port
    private val closed = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = mutableSetOf<NioBrowserSocket>()
    val activeConnectionCount: Int get() = synchronized(sockets) { sockets.size }
    val isListening: Boolean get() = !closed.get() && listener.isOpen

    init { scope.launch {
        try {
            while (isActive) {
                val channel = accept()
                val socket = NioBrowserSocket(channel)
                val accepted = synchronized(sockets) {
                    if (closed.get() || sockets.size >= maximumConnections) false else sockets.add(socket)
                }
                if (!accepted) { socket.close(); continue }
                launch {
                    try { serve(socket) }
                    catch (failure: Exception) { currentCoroutineContext().ensureActive() }
                    finally { socket.close(); synchronized(sockets) { sockets.remove(socket) } }
                }
            }
        } catch (failure: Exception) { currentCoroutineContext().ensureActive() }
        finally { close() }
    } }

    private suspend fun accept(): AsynchronousSocketChannel = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { listener.close() } }
        listener.accept(Unit, object : java.nio.channels.CompletionHandler<AsynchronousSocketChannel, Unit> {
            override fun completed(channel: AsynchronousSocketChannel, attachment: Unit) {
                continuation.resume(channel) { _, abandoned, _ -> runCatching { abandoned.close() }; Unit }
            }
            override fun failed(failure: Throwable, attachment: Unit) { continuation.resumeWith(Result.failure(failure)) }
        })
    }

    private class Rejected(val reply: Int) : Exception()
    private data class Request(val host: String, val port: Int)
    private suspend fun exact(socket: BrowserTunnelLane, count: Int): ByteArray {
        val result = ByteArray(count); var offset = 0
        while (offset < count) {
            val bytes = socket.read(count - offset) ?: throw EOFException("Incomplete SOCKS handshake")
            bytes.copyInto(result, offset); offset += bytes.size
        }
        return result
    }
    private suspend fun request(socket: BrowserTunnelLane): Request? {
        val greeting = exact(socket, 2)
        if (greeting[0] != 5.toByte()) return null
        val methods = exact(socket, greeting[1].toInt() and 255)
        val accepts = 0.toByte() in methods
        socket.write(byteArrayOf(5, if (accepts) 0 else -1))
        if (!accepts) return null
        val header = exact(socket, 4)
        if (header[0] != 5.toByte() || header[2] != 0.toByte()) return null
        if (header[1] != 1.toByte()) throw Rejected(7)
        val host = when (header[3].toInt()) {
            1 -> exact(socket, 4).joinToString(".") { (it.toInt() and 255).toString() }
            4 -> exact(socket, 16).toList().chunked(2).joinToString(":") {
                (((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255)).toString(16)
            }
            3 -> exact(socket, exact(socket, 1)[0].toInt() and 255).toString(Charsets.UTF_8)
            else -> throw Rejected(8)
        }
        val portBytes = exact(socket, 2)
        val port = ((portBytes[0].toInt() and 255) shl 8) or (portBytes[1].toInt() and 255)
        if (runCatching { BrowserTunnelProtocol.connect(host, port) }.isFailure) throw Rejected(4)
        return Request(host, port)
    }
    private suspend fun serve(socket: NioBrowserSocket) {
        var connected = false
        try {
            val destination = withTimeout(handshakeTimeoutMillis) { request(socket) } ?: return
            backend.use(destination.host, destination.port) { exit ->
                connected = true
                socket.write(reply(0)) // Never expose a successful SOCKS reply before the exit confirms.
                relayBrowser(socket, exit)
            }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (!connected) socket.write(reply(if (failure is Rejected) failure.reply else socksFailure(failure)))
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        synchronized(sockets) { sockets.forEach { it.close() }; sockets.clear() }
        scope.cancel()
    }
    suspend fun stop() { close(); scope.coroutineContext[Job]!!.join() }
    companion object {
        fun start(backend: BrowserTunnelBackend, port: Int = 0, maximumConnections: Int = 256,
            handshakeTimeoutMillis: Long = 15_000): BrowserSocksProxy {
            require(port in 0..65535 && maximumConnections in 1..256 && handshakeTimeoutMillis > 0)
            val listener = AsynchronousServerSocketChannel.open()
            try {
                listener.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port))
                return BrowserSocksProxy(listener, backend, maximumConnections, handshakeTimeoutMillis)
            } catch (failure: Exception) { listener.close(); throw failure }
        }
        private fun reply(code: Int) = byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)
        fun socksFailure(failure: Exception): Int = when (failure) {
            is BrowserTunnelProtocol.OpenFailure -> when (failure.status) {
                BrowserTunnelProtocol.Status.DENIED -> 2
                BrowserTunnelProtocol.Status.NETWORK_UNREACHABLE -> 3
                BrowserTunnelProtocol.Status.HOST_UNREACHABLE, BrowserTunnelProtocol.Status.UNRESOLVED -> 4
                BrowserTunnelProtocol.Status.REFUSED -> 5
                BrowserTunnelProtocol.Status.TIMED_OUT -> 6
                else -> 1
            }
            is NoRouteToHostException, is UnknownHostException -> 4
            is ConnectException -> 5
            is SocketTimeoutException, is TimeoutCancellationException -> 6
            else -> 1
        }
    }
}

/** One bounded chunk per direction; one side reaching EOF does not discard the other's tail. */
internal suspend fun relayBrowser(first: BrowserTunnelLane, second: BrowserTunnelLane) = coroutineScope {
    val abort = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { first.close(); second.close() }
    }
    suspend fun pump(from: BrowserTunnelLane, to: BrowserTunnelLane) {
        while (true) {
            val chunk = from.read() ?: break
            currentCoroutineContext().ensureActive()
            if (chunk.isNotEmpty()) to.write(chunk)
        }
        to.finishSending()
    }
    try {
        val forward = launch { pump(first, second) }
        pump(second, first)
        forward.join()
    } finally { abort.cancel() }
}
