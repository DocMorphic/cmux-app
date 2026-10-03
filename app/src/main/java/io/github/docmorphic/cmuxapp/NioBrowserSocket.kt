package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** API 26 asynchronous sockets: one pending read and write, with no thread parked per stream. */
internal class NioBrowserSocket(private val channel: AsynchronousSocketChannel) : BrowserTunnelLane {
    private val reading = Mutex()
    private val writing = Mutex()
    private var finished = false
    override suspend fun read(maximumBytes: Int): ByteArray? = reading.withLock {
        require(maximumBytes in 1..64 * 1024)
        val buffer = ByteBuffer.allocate(maximumBytes)
        val count = browserAsync<Int>(::close) { channel.read(buffer, Unit, it) }
        if (count < 0) null else buffer.array().copyOf(count)
    }
    override suspend fun write(bytes: ByteArray) {
        require(bytes.size <= 64 * 1024)
        val buffer = ByteBuffer.wrap(bytes.copyOf())
        writing.withLock {
            check(!finished) { "Browser socket sending side finished" }
            while (buffer.hasRemaining()) browserAsync<Int>(::close) { channel.write(buffer, Unit, it) }
        }
    }
    override suspend fun finishSending() = writing.withLock {
        if (!finished) { finished = true; channel.shutdownOutput() }
        Unit
    }
    override fun close() { runCatching { channel.close() } }

    companion object {
        // DNS may be slow to observe interruption on some providers. Keep it off coroutine
        // lifetime completion, bounded in both threads and queued work, and never use it for Mac lanes.
        private val dns = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(256),
            { task -> Thread(task, "cmux-browser-dns").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
        private suspend fun addresses(host: String): Array<InetAddress> = suspendCancellableCoroutine { continuation ->
            try {
                val task = dns.submit {
                    try { continuation.resume(InetAddress.getAllByName(host)) }
                    catch (failure: Exception) { continuation.resumeWithException(failure) }
                }
                continuation.invokeOnCancellation { task.cancel(true); dns.remove(task as Runnable) }
            } catch (failure: Exception) { continuation.resumeWithException(failure) }
        }
        private suspend fun connect(host: String, port: Int): NioBrowserSocket {
            var candidate: NioBrowserSocket? = null
            try { return withTimeout(15_000) {
            BrowserTunnelProtocol.connect(host, port)
            var last: Exception? = null
            for (address in addresses(host)) {
                currentCoroutineContext().ensureActive()
                val channel = AsynchronousSocketChannel.open().setOption(StandardSocketOptions.TCP_NODELAY, true)
                val stream = NioBrowserSocket(channel)
                candidate = stream
                try {
                    browserAsync<Void?>(stream::close) { channel.connect(InetSocketAddress(address, port), Unit, it) }
                    return@withTimeout stream
                } catch (failure: Exception) {
                    stream.close(); currentCoroutineContext().ensureActive(); last = failure
                }
            }
            throw last ?: java.net.UnknownHostException(host)
            } } catch (failure: Throwable) { candidate?.close(); throw failure }
        }
        val direct = BrowserTunnelBackend { host, port, use -> connect(host, port).use { use(it) } }
    }
}

internal suspend fun <T> browserAsync(cancel: () -> Unit,
    start: (CompletionHandler<T, Unit>) -> Unit): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    val handler = object : CompletionHandler<T, Unit> {
        override fun completed(result: T, attachment: Unit) { continuation.resume(result) { _, _, _ -> cancel() } }
        override fun failed(failure: Throwable, attachment: Unit) { continuation.resumeWithException(failure) }
    }
    try { start(handler) } catch (failure: Exception) { continuation.resumeWithException(failure) }
}
