package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SshTmuxControlTest {
    private class Pipe : SshTmuxPipe {
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        override val output = input.receiveAsFlow()
        val sent = mutableListOf<String>()
        var closed = false
        var failWrite = false
        override suspend fun write(bytes: ByteArray) { if (failWrite) error("fixture write failure"); sent += bytes.toString(Charsets.UTF_8) }
        override fun close() { closed = true; input.close() }
        fun feed(text: String) { check(input.trySend(text.toByteArray()).isSuccess) }
        fun reply(n: Int, rows: String = "", flags: Int = 1, error: Boolean = false) {
            feed("%begin 10 $n $flags\n" + (if (rows.isEmpty()) "" else "$rows\n") + "%${if (error) "error" else "end"} 10 $n $flags\n")
        }
    }
    @Test fun startupAndCanceledCommandCannotConsumeNextReply() = runTest {
        val pipe = Pipe(); val client = SshTmuxControl("test", pipe, backgroundScope)
        val canceled = async { client.command("first") }; runCurrent(); canceled.cancelAndJoin()
        val next = async { client.command("second") }; runCurrent()
        pipe.reply(1, flags = 0); pipe.reply(2, "discarded"); runCurrent(); assertFalse(next.isCompleted)
        pipe.reply(3, "wanted"); runCurrent()
        assertEquals("wanted", next.await().single().toString(Charsets.UTF_8)); assertFalse(client.isClosed)
        client.close()
    }
    @Test fun snapshotIsDeliveredBeforeBufferedLiveOutputAndLateSeedsCannotReviveDetachedPane() = runTest {
        val pipe = Pipe(); val client = SshTmuxControl("test", pipe, backgroundScope)
        val events = mutableListOf<TmuxPaneEvent>()
        client.attach(7, 2, events::add); runCurrent()
        pipe.feed("%output %7 already captured\n"); pipe.reply(1, error = true) // old tmux pause unsupported
        pipe.reply(2, "1"); pipe.reply(3, "screen row")
        pipe.feed("%output %7 later\\015\\012\n"); runCurrent(); assertTrue(events.isEmpty())
        pipe.reply(4, "pane_width=80,pane_height=24,cursor_x=0,cursor_y=0,wrap_flag=1")
        pipe.reply(5); runCurrent()
        assertEquals(3, events.size); assertEquals(TmuxPaneEvent.Grid(80, 24), events[0])
        val snapshot = (events[1] as TmuxPaneEvent.Snapshot).bytes.toString(Charsets.UTF_8)
        assertTrue(snapshot.startsWith("\u001b[?1049h\u001b[H\u001b[2Jscreen row")); assertFalse(snapshot.contains("already captured"))
        assertArrayEquals("later\r\n".toByteArray(), (events[2] as TmuxPaneEvent.Output).bytes)
        client.detach(7); client.attach(7, 2, events::add); client.detach(7)
        (6..10).forEach { pipe.reply(it, "stale") }; runCurrent(); assertEquals(3, events.size)
        client.close()
    }
    @Test fun layoutChangesFixPaneGridAndClosingWindowEndsPaneOnce() = runTest {
        val pipe = Pipe(); val client = SshTmuxControl("test", pipe, backgroundScope)
        val init = async { client.initialize() }; runCurrent(); pipe.reply(1, "@2 %7 80x24"); runCurrent(); init.await()
        val events = mutableListOf<TmuxPaneEvent>(); client.attach(7, 2, events::add)
        pipe.reply(2); pipe.reply(3, "0"); pipe.reply(4, "seed"); pipe.reply(5, "pane_width=80,pane_height=24"); pipe.reply(6); runCurrent()
        var changes = 0; client.onTopologyChange = { changes++ }
        pipe.feed("%layout-change @2 abcd,80x24,0,0{39x24,0,0,7,40x24,40,0,8} abcd,80x24,0,0,7 *\n%window-renamed @2 label\n")
        runCurrent(); assertEquals(1, changes) // one relist per delivered event burst
        pipe.feed("%window-close @2\n%window-close @2\n"); runCurrent()
        pipe.reply(7, "@2 %7 80x24"); runCurrent()
        assertEquals(0, events.count { it == TmuxPaneEvent.Ended }) // another group's unlink
        pipe.feed("%window-close @2\n"); runCurrent(); pipe.reply(8); runCurrent()
        assertEquals(1, events.count { it == TmuxPaneEvent.Ended }); assertEquals(0, client.attachedPaneCount)
        client.close()
    }
    @Test fun commandTimeoutAndWriteFailureRetirePipeAndPendingInput() = runTest {
        val pipe = Pipe(); val client = SshTmuxControl("test", pipe, backgroundScope, 100)
        val result = async { runCatching { client.command("uncertain mutation") } }; runCurrent()
        advanceTimeBy(100); runCurrent(); assertTrue(result.await().isFailure); assertTrue(pipe.closed)
        assertThrows(IllegalStateException::class.java) { client.resize(80, 24) }
        val broken = Pipe().apply { failWrite = true }; val other = SshTmuxControl("other", broken, backgroundScope)
        val failure = async { runCatching { other.command("no retry") } }; runCurrent()
        assertTrue(failure.await().isFailure); assertTrue(other.isClosed); assertTrue(broken.sent.isEmpty())
    }
    @Test fun unrelatedWindowLayoutCannotEndANewWindowAwaitingItsFirstLayoutNotice() = runTest {
        val pipe = Pipe(); val client = SshTmuxControl("test", pipe, backgroundScope)
        val init = async { client.initialize() }; runCurrent(); pipe.reply(1, "@2 %7 80x24"); runCurrent(); init.await()
        val events = mutableListOf<TmuxPaneEvent>(); client.attach(8, 3, events::add)
        pipe.reply(2); pipe.reply(3, "0"); pipe.reply(4, "new window"); pipe.reply(5, "pane_width=80,pane_height=24"); pipe.reply(6); runCurrent()
        pipe.feed("%layout-change @2 abcd,80x24,0,0,7 abcd,80x24,0,0,7 *\n"); runCurrent()
        assertFalse(events.contains(TmuxPaneEvent.Ended))
        pipe.feed("%output %8 still alive\n"); runCurrent()
        assertEquals("still alive", (events.last() as TmuxPaneEvent.Output).bytes.toString(Charsets.UTF_8))
        client.close()
    }
    @Test fun inventoryRequestedBeforeAnAttachmentCannotRetireItButAFreshInventoryCan() = runTest {
        val pipe = Pipe(); val client = SshTmuxControl("test", pipe, backgroundScope)
        val oldInventory = async { client.initialize() }; runCurrent()
        val events = mutableListOf<TmuxPaneEvent>(); client.attach(8, 3, events::add); runCurrent()
        pipe.reply(1, "@2 %7 80x24"); runCurrent(); oldInventory.await()
        assertFalse(events.contains(TmuxPaneEvent.Ended))
        pipe.reply(2); pipe.reply(3, "0"); pipe.reply(4, "new window"); pipe.reply(5, "pane_width=80,pane_height=24"); pipe.reply(6); runCurrent()
        val freshInventory = async { client.initialize() }; runCurrent(); pipe.reply(7, "@2 %7 80x24"); runCurrent(); freshInventory.await()
        assertEquals(1, events.count { it == TmuxPaneEvent.Ended })
        client.close()
    }
    @Test fun chunkedInputCannotContinueIntoReplacementAttachment() = runTest {
        val pipe = Pipe(); val client = SshTmuxControl("test", pipe, backgroundScope)
        client.attach(7, 2) {}; runCurrent()
        pipe.reply(1); pipe.reply(2, "0"); pipe.reply(3, "seed")
        pipe.reply(4, "pane_width=80,pane_height=24"); pipe.reply(5); runCurrent()
        val writing = async { runCatching { client.write(7, ByteArray(512) { 65 }) } }; runCurrent()
        assertEquals(1, pipe.sent.count { it.startsWith("send-keys") })
        client.detach(7); client.attach(7, 3) {}; runCurrent()
        pipe.reply(6); runCurrent()
        assertTrue(writing.await().isFailure)
        assertEquals(1, pipe.sent.count { it.startsWith("send-keys") })
        client.close()
    }
    @Test fun legacyMouseBytesReachTheNamedPaneWithoutUtf8Conversion() = runTest {
        val pipe = Pipe(); val client = SshTmuxControl("test", pipe, backgroundScope)
        client.attach(7, 2) {}; runCurrent()
        pipe.reply(1); pipe.reply(2, "0"); pipe.reply(3, "seed")
        pipe.reply(4, "pane_width=200,pane_height=24"); pipe.reply(5); runCurrent()
        val writing = async { client.write(7, byteArrayOf(27, 91, 77, 32, 183.toByte(), 35)) }
        runCurrent()
        val command = pipe.sent.single { it.startsWith("send-keys") }
        assertTrue(command.contains("=test:@2.%7"))
        assertTrue(command.endsWith(" -H 1b 5b 4d 20 b7 23\n"))
        pipe.reply(6); runCurrent(); writing.await()
        client.close()
    }
    @Test fun gracefulCloseWaitsForKillAndOwnerCancellationClosesImmediately() = runTest {
        val pipe = Pipe(); val owner = CoroutineScope(backgroundScope.coroutineContext + Job())
        val client = SshTmuxControl("group", pipe, owner)
        val close = async { runCatching { client.detachSession() } }; runCurrent()
        assertEquals("kill-session -t \"=group\"\n", pipe.sent.single()); assertFalse(pipe.closed)
        pipe.feed("%exit\n"); runCurrent(); runCatching { close.await() }; assertTrue(pipe.closed)
        owner.cancel()
        val nextOwner = CoroutineScope(backgroundScope.coroutineContext + Job())
        val nextPipe = Pipe(); val next = SshTmuxControl("next", nextPipe, nextOwner)
        nextOwner.cancel(); runCurrent(); assertTrue(nextPipe.closed); assertTrue(next.isClosed)
    }
}
