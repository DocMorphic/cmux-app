package io.github.docmorphic.cmuxapp

import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Single-reader channel-to-stream adapter with socket-like read timeouts.
 * A bounded blocking queue provides backpressure without polling an idle channel.
 * JSch uses InterruptedIOException to drive keepalive even when Proxy has no Socket. */
internal class SshTimedInputStream(private val source: InputStream, timeoutMillis: Int, workers: ExecutorService) : InputStream() {
    private data class Part(val bytes: ByteArray? = null, val error: IOException? = null)
    @Volatile var timeoutMillis = timeoutMillis.also { require(it > 0) }
        set(value) { require(value > 0); field = value }
    private val closed = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<Part>(32)
    private var current = ByteArray(0)
    private var position = 0
    private var ended = false
    private val pump = workers.submit {
        try {
            val buffer = ByteArray(8192)
            while (!closed.get()) {
                val count = source.read(buffer)
                if (count < 0) { queue.put(Part()); break }
                if (count > 0) queue.put(Part(buffer.copyOf(count)))
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        catch (failure: IOException) {
            if (!closed.get()) try { queue.put(Part(error = failure)) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        if (length == 0) return 0
        if (closed.get() || ended) return -1
        if (position == current.size) {
            val next = try { queue.poll(timeoutMillis.toLong(), TimeUnit.MILLISECONDS) }
            catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("SSH channel read interrupted").apply { initCause(interrupted) }
            }
            if (closed.get()) return -1
            if (next == null) throw SocketTimeoutException("SSH jump channel read timed out")
            next.error?.let { ended = true; throw it }
            if (next.bytes == null) { ended = true; return -1 }
            current = next.bytes; position = 0
        }
        val count = minOf(length, current.size - position)
        current.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }
    override fun available(): Int = if (closed.get() || ended) 0 else
        current.size - position + queue.sumOf { it.bytes?.size ?: 0 }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pump.cancel(true)
        queue.clear(); queue.offer(Part())
        source.close()
    }
}
