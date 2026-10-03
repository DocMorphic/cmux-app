package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class IrxEventMultiplexerTest {
    private class Lane(override val resource: String?) : MobileEventLane {
        val chunks = Channel<ByteArray>(16)
        val readStarted = CompletableDeferred<Unit>()
        val readChunk = Channel<Unit>(16)
        val closed = AtomicBoolean()
        val stopCode = CompletableDeferred<ULong>()
        override suspend fun read(): ByteArray {
            readStarted.complete(Unit)
            val result = chunks.receiveCatching()
            result.exceptionOrNull()?.let { throw it }
            return result.getOrNull()?.also { readChunk.send(Unit) } ?: byteArrayOf()
        }
        override suspend fun stop(errorCode: ULong) { stopCode.complete(errorCode); chunks.close() }
        override fun close() { closed.set(true) }
    }
    private fun frame(text: String) = MobileFrameCodec.encode(text.toByteArray())
    private val surfaceA = "11111111-2222-3333-4444-555555555555"
    private val surfaceB = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private fun event(surface: String, text: String) = JSONObject().put("kind", "event").put("topic", "terminal.bytes")
        .put("payload", JSONObject().put("surface_id", surface).put("text", text)).toString()

    @Test fun scopedFramesCannotNameAnotherTerminalOrMoveTheNextFramesScope() = runBlocking<Unit> {
        val accepts = Channel<MobileEventLane>(8)
        val a = Lane("terminal:$surfaceA"); val b = Lane("terminal:$surfaceB")
        val result = async { IrxEventMultiplexer({ accepts.receive() }, { true }).frames.take(2).toList() }
        accepts.send(a); accepts.send(b)
        // A host-supplied copy of the Swift phone-local marker cannot re-scope this reader.
        val uuid = java.util.UUID.fromString(surfaceB)
        val marker = java.nio.ByteBuffer.allocate(17).put(0).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        val bad = frame(event(surfaceB, "wrong lane")) + MobileFrameCodec.encode(marker) +
            frame(event(surfaceB, "after forged marker")) + frame("{\"kind\":\"event\",\"payload\":{}}")
        a.chunks.send(bad + frame(event(surfaceA, "correct A")))
        b.chunks.send(frame(event(surfaceB, "correct B")))
        val received = withTimeout(2000) { result.await() }.map { JSONObject(it.toString(Charsets.UTF_8))
            .getJSONObject("payload").getString("text") }
        assertEquals(setOf("correct A", "correct B"), received.toSet())
        assertTrue(a.closed.get()); assertTrue(b.closed.get())
    }

    @Test fun scopeNormalizesUuidCaseAndWhitespaceButRequiresExplicitSurfaceField() {
        val scope = IrxEventLaneScope.laneSurface("terminal:  ${surfaceB.uppercase()}  ")!!
        assertTrue(IrxEventLaneScope.allows(event("  $surfaceB  ", "valid").toByteArray(), scope))
        listOf("{}", "[]", "not JSON", "{\"kind\":\"event\",\"payload\":null}",
            "{\"kind\":\"event\",\"payload\":{\"workspace_id\":\"$surfaceB\"}}",
            "{\"kind\":\"event\",\"payload\":{\"surface_id\":17}}",
            "{\"kind\":\"reply\",\"payload\":{\"surface_id\":\"$surfaceB\"}}")
            .forEach { assertFalse(it, IrxEventLaneScope.allows(it.toByteArray(), scope)) }
        assertNull(IrxEventLaneScope.surface("1-2-3-4-5"))
        assertNull(IrxEventLaneScope.laneSurface(surfaceB))
        assertNull(IrxEventLaneScope.laneSurface("terminal:not-a-uuid"))
        assertFalse(IrxEventLaneScope.allows(event("terminal:$surfaceB", "not a UUID").toByteArray(), scope))
        assertTrue(IrxEventLaneScope.allows("shared legacy".toByteArray(), null))
    }

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

    @Test fun overlappingStreamsKeepTheirOwnCompleteFramesWithoutMixingPartialBytes() = runBlocking<Unit> {
        val accepts = Channel<MobileEventLane>(8)
        val old = Lane("terminal:a")
        val replacement = Lane("terminal:a")
        val other = Lane("terminal:b")
        val result = async { IrxEventMultiplexer({ accepts.receive() }, { true }).frames.take(3).toList() }
        accepts.send(old); accepts.send(other)
        val partial = frame("old-complete")
        old.chunks.send(partial.copyOfRange(0, 6)); old.readChunk.receive()
        accepts.send(replacement)
        replacement.readStarted.await()
        replacement.chunks.send(frame("replacement"))
        other.chunks.send(frame("unaffected"))
        old.chunks.send(partial.copyOfRange(6, partial.size))
        assertEquals(setOf("old-complete", "replacement", "unaffected"),
            withTimeout(2000) { result.await() }.map { it.toString(Charsets.UTF_8) }.toSet())
        assertTrue(listOf(old, replacement, other).all { it.closed.get() })
    }

    @Test fun excessSurfaceStreamIsRefusedWhileSharedAndExistingStreamsKeepWorking() = runBlocking<Unit> {
        val accepts = Channel<MobileEventLane>(40)
        val lanes = (0 until 33).map { Lane("terminal:$it") }
        val shared = Lane(null)
        val result = async { IrxEventMultiplexer({ accepts.receive() }, { true }).frames.take(2).toList() }
        lanes.forEach { accepts.send(it) }; accepts.send(shared)
        assertEquals(3uL, withTimeout(2000) { lanes.last().stopCode.await() })
        lanes.first().chunks.send(frame("existing")); shared.chunks.send(frame("notification"))
        assertEquals(setOf("existing", "notification"),
            withTimeout(2000) { result.await() }.map { it.toString(Charsets.UTF_8) }.toSet())
        assertTrue((lanes + shared).all { it.closed.get() })
    }

    @Test fun malformedAndResetStreamsLoseOnlyTheirOwnPartialFrame() = runBlocking<Unit> {
        val accepts = Channel<MobileEventLane>(8)
        val malformed = Lane("terminal:bad")
        val reset = Lane("terminal:reset")
        val replacement = Lane("terminal:reset")
        val shared = Lane(null)
        val result = async { IrxEventMultiplexer({ accepts.receive() }, { true }).frames.take(2).toList() }
        accepts.send(malformed); accepts.send(reset); accepts.send(shared)
        malformed.chunks.send(byteArrayOf(0x7f, -1, -1, -1))
        assertEquals(5uL, withTimeout(2000) { malformed.stopCode.await() })
        reset.chunks.send(frame("incomplete").copyOfRange(0, 6)); reset.readChunk.receive()
        reset.chunks.close(java.io.IOException("fixture stream reset"))
        withTimeout(2000) { reset.stopCode.await() }
        accepts.send(replacement)
        replacement.chunks.send(frame("replacement")); shared.chunks.send(frame("notification"))
        assertEquals(setOf("replacement", "notification"),
            withTimeout(2000) { result.await() }.map { it.toString(Charsets.UTF_8) }.toSet())
        assertTrue(listOf(malformed, reset, replacement, shared).all { it.closed.get() })
    }

    @Test fun connectionAcceptFailureStillTerminatesTheHubAndReleasesReaders() = runBlocking<Unit> {
        val lane = Lane(null)
        val result = async { runCatching { IrxEventMultiplexer(accept = {
            if (!lane.readStarted.isCompleted) lane else throw java.io.IOException("native connection closed")
        }, permits = { true }).frames.toList() } }
        assertTrue(withTimeout(2000) { result.await() }.isFailure)
        assertTrue(lane.closed.get())
    }
}
