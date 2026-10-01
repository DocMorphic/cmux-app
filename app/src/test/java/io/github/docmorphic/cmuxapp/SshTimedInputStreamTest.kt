package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SshTimedInputStreamTest {
    @Test fun readTimeoutDoesNotDiscardBytesDeliveredAfterward() {
        val workers = Executors.newCachedThreadPool()
        val source = PipedInputStream(); val writer = PipedOutputStream(source)
        try {
            SshTimedInputStream(source, 80, workers).use { input ->
                assertThrows(SocketTimeoutException::class.java) { input.read() }
                writer.write(byteArrayOf(0, 128.toByte(), 255.toByte())); writer.flush()
                input.timeoutMillis = 1000
                assertEquals(0, input.read()); assertEquals(128, input.read()); assertEquals(255, input.read())
                writer.close(); assertEquals(-1, input.read()); assertEquals(-1, input.read())
            }
        } finally { writer.close(); workers.shutdownNow() }
    }
    @Test fun closeUnblocksPendingReaderWithoutWaitingForItsTimeout() {
        val workers = Executors.newCachedThreadPool()
        val source = PipedInputStream(); val writer = PipedOutputStream(source)
        val input = SshTimedInputStream(source, 10000, workers)
        try {
            val read = workers.submit<Int> { input.read() }
            input.close()
            assertEquals(-1, read.get(1, TimeUnit.SECONDS))
        } finally { input.close(); writer.close(); workers.shutdownNow() }
    }
    @Test fun boundedQueuePreservesLargeBinaryStreamAndEof() {
        val workers = Executors.newCachedThreadPool()
        val bytes = ByteArray(400000) { (it % 251).toByte() }
        try { SshTimedInputStream(bytes.inputStream(), 1000, workers).use { assertArrayEquals(bytes, it.readBytes()) } }
        finally { workers.shutdownNow() }
    }
    @Test fun underlyingFailureIsNotReportedAsSuccessfulEof() {
        val workers = Executors.newCachedThreadPool()
        try {
            val source = object : InputStream() { override fun read(): Int = throw IOException("fixture failure") }
            SshTimedInputStream(source, 1000, workers).use { input ->
                assertEquals("fixture failure", assertThrows(IOException::class.java) { input.read() }.message)
            }
        } finally { workers.shutdownNow() }
    }
}
