package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class IrxEventMultiplexerTest {
    private class Lane(override val resource: String?) : MobileEventLane {
        val chunks = Channel<ByteArray>(16)
        val readStarted = CompletableDeferred<Unit>()
        val readChunk = Channel<Unit>(16)
        val closed = AtomicBoolean()
        override suspend fun read(): ByteArray {
            readStarted.complete(Unit)
            return chunks.receiveCatching().getOrNull()?.also { readChunk.send(Unit) } ?: byteArrayOf()
        }
        override suspend fun stop() { chunks.close() }
        override fun close() { closed.set(true) }
    }
    private fun frame(text: String) = MobileFrameCodec.encode(text.toByteArray())

    @Test fun fragmentedIndependentStreamsCannotMixTheirFrameBytes() = runBlocking<Unit> {
        val accepts = Channel<MobileEventLane>(8)
        val first = Lane("terminal:a")
        val second = Lane("terminal:b")
        val shared = Lane(null)
        val result = async { IrxEventMultiplexer({ accepts.receive() }, { true }).frames.take(3).toList() }
        accepts.send(first); accepts.send(second); accepts.send(shared)
        val a = frame("first-terminal")
        first.chunks.send(a.copyOfRange(0, 5)); first.readChunk.receive()
        second.chunks.send(frame("second-terminal"))
        shared.chunks.send(frame("notification"))
        first.chunks.send(a.copyOfRange(5, a.size))
        val frames = withTimeout(2000) { result.await() }.map { it.toString(Charsets.UTF_8) }
        assertEquals(setOf("first-terminal", "second-terminal", "notification"), frames.toSet())
        assertTrue(listOf(first, second, shared).all { it.closed.get() })
    }

    @Test fun replacementDropsOnlyTheOldPartialFrameAndKeepsOtherLanesRunning() = runBlocking<Unit> {
        val accepts = Channel<MobileEventLane>(8)
        val old = Lane("terminal:a")
        val replacement = Lane("terminal:a")
        val other = Lane("terminal:b")
        val result = async { IrxEventMultiplexer({ accepts.receive() }, { true }).frames.take(2).toList() }
        accepts.send(old); accepts.send(other)
        old.chunks.send(frame("partial").copyOfRange(0, 6)); old.readChunk.receive()
        accepts.send(replacement)
        replacement.readStarted.await()
        replacement.chunks.send(frame("replacement"))
        other.chunks.send(frame("unaffected"))
        assertEquals(setOf("replacement", "unaffected"), withTimeout(2000) { result.await() }.map { it.toString(Charsets.UTF_8) }.toSet())
        assertTrue(old.closed.get())
    }

    @Test fun excessIncomingStreamsFailAndReleaseEveryAcceptedReader() = runBlocking<Unit> {
        val accepts = Channel<MobileEventLane>(20)
        val lanes = (0 until 18).map { Lane("terminal:$it") }
        val result = async { runCatching { IrxEventMultiplexer({ accepts.receive() }, { true }).frames.toList() } }
        lanes.forEach { accepts.send(it) }
        assertTrue(withTimeout(2000) { result.await() }.isFailure)
        assertTrue(lanes.all { it.closed.get() })
    }
}
