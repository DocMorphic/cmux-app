package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class IrxControlChannelTest {
    private class Lane : MobileControlLane {
        val bytes = Channel<ByteArray>(16)
        val readCount = AtomicInteger()
        val retired = CompletableDeferred<Unit>()
        var readAction: (suspend () -> ByteArray)? = null
        override suspend fun read(): ByteArray { readCount.incrementAndGet(); return readAction?.invoke() ?: bytes.receiveCatching().getOrNull() ?: byteArrayOf() }
        override suspend fun write(bytes: ByteArray) { }
        override suspend fun retire() { retired.complete(Unit); close() }
        override fun close() { bytes.close() }
    }
    private fun frame(text: String) = MobileFrameCodec.encode(text.toByteArray())

    @Test fun replacementDropsOldPartialFrameAndContinuesParkedReader() = runBlocking<Unit> {
        val old = Lane(); val next = Lane()
        IrxControlChannel(old, { next }, { true }, { false }).use { channel ->
            val waiting = async { channel.read() }
            old.bytes.send(frame("old").copyOfRange(0, 2))
            withTimeout(2000) { while (old.readCount.get() < 2) delay(1) }
            assertEquals(MobileControlRepair.Repaired(1), channel.repair())
            next.bytes.send(frame("new").copyOfRange(0, 3))
            next.bytes.send(frame("new").copyOfRange(3, 7))
            assertArrayEquals(frame("new"), withTimeout(2000) { waiting.await() })
            withTimeout(2000) { old.retired.await() }
        }
    }

    @Test fun lateOldNativeReadCannotAnswerOnReplacement() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val old = Lane().apply { readAction = { entered.complete(Unit); withContext(NonCancellable) { finish.await() }; frame("late") } }
        val next = Lane()
        IrxControlChannel(old, { next }, { true }, { false }).use { channel ->
            withTimeout(2000) { entered.await() }
            assertEquals(MobileControlRepair.Repaired(1), channel.repair())
            finish.complete(Unit)
            next.bytes.send(frame("current"))
            assertArrayEquals(frame("current"), withTimeout(2000) { channel.read() })
        }
    }

    @Test fun resetOnOldStreamDuringRepairDoesNotCloseAcknowledgedReplacement() = runBlocking<Unit> {
        val old = Lane(); val next = Lane(); val open = CompletableDeferred<Unit>(); val ack = CompletableDeferred<Unit>()
        IrxControlChannel(old, { open.complete(Unit); ack.await(); next }, { true }, { false }).use { channel ->
            val read = async { channel.read() }
            val repair = async { channel.repair() }
            open.await(); old.bytes.close(IOException("old stream reset")); ack.complete(Unit)
            assertEquals(MobileControlRepair.Repaired(1), repair.await())
            next.bytes.send(frame("answer"))
            assertArrayEquals(frame("answer"), withTimeout(2000) { read.await() })
        }
    }

    @Test fun unavailableRepairPreservesConnectionAndLateAckAfterCloseCannotInstall() = runBlocking<Unit> {
        val old = Lane()
        IrxControlChannel(old, { awaitCancellation() }, { true }, { false }, 30).use { channel ->
            assertEquals(MobileControlRepair.Unavailable, channel.repair())
            old.bytes.send(frame("still live"))
            assertArrayEquals(frame("still live"), channel.read())
        }
        val entered = CompletableDeferred<Unit>(); val ack = CompletableDeferred<Unit>(); val next = Lane()
        val channel = IrxControlChannel(Lane(), { entered.complete(Unit); withContext(NonCancellable) { ack.await() }; next }, { true }, { false })
        val repair = async { channel.repair() }
        entered.await(); channel.close(); ack.complete(Unit)
        assertEquals(MobileControlRepair.Closed, withTimeout(2000) { repair.await() })
        withTimeout(2000) { next.retired.await() }
    }

    @Test fun acknowledgementAfterDeadlineIsRetiredWithoutReplacingLiveStream() = runBlocking<Unit> {
        val old = Lane(); val late = Lane()
        IrxControlChannel(old, { withContext(NonCancellable) { delay(80) }; late }, { true }, { false }, 20).use { channel ->
            assertEquals(MobileControlRepair.Unavailable, channel.repair())
            withTimeout(2000) { late.retired.await() }
            old.bytes.send(frame("original"))
            assertArrayEquals(frame("original"), channel.read())
        }
    }
}
