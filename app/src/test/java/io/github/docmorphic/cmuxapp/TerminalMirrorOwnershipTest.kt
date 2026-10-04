package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TerminalMirrorOwnershipTest {
    private class Tracked(private val delegate: VtTerminal, val failSnapshot: Boolean = false) : ByteTerminal by delegate {
        var closes = 0
        override val activeScreen: String get() {
            check(!failSnapshot) { "snapshot failed" }
            return delegate.activeScreen
        }
        override fun close() { closes++ }
    }
    private fun replay(text: String, seq: Int) = JSONObject().put("seq", seq)
        .put("snapshot_data_b64", Base64.getEncoder().encodeToString(text.toByteArray()))

    @Test fun replacementClosesPreviousOwnerAndCloseIsIdempotent() {
        val created = mutableListOf<Tracked>()
        val mirror = TerminalStreamMirror("s", TerminalTransport(TerminalOutputMode.BYTES, false), TerminalViewport(20, 4), terminalFactory = { c, r ->
            Tracked(VtTerminal(c, r)).also(created::add)
        })
        assertTrue(created.isEmpty())
        mirror.replay(replay("old", 1)); mirror.replay(replay("new", 2))
        assertEquals(listOf(1, 0), created.map { it.closes })
        mirror.close(); mirror.close()
        assertEquals(listOf(1, 1), created.map { it.closes })
        assertThrows(IllegalStateException::class.java) { mirror.replay(replay("late", 3)) }
        assertThrows(IllegalStateException::class.java) { mirror.beginReplay() }
        assertEquals(2, created.size)
    }

    @Test fun failedMaterializationClosesCandidateAndKeepsPreviousContent() {
        val created = mutableListOf<Tracked>()
        val mirror = TerminalStreamMirror("s", TerminalTransport(TerminalOutputMode.BYTES, false), TerminalViewport(20, 4), terminalFactory = { c, r ->
            Tracked(VtTerminal(c, r), failSnapshot = created.isNotEmpty()).also(created::add)
        })
        mirror.replay(replay("kept", 1))
        assertThrows(IllegalStateException::class.java) { mirror.replay(replay("failed", 2)) }
        assertEquals("kept", RenderGrid.plainText(mirror.display.visibleLines()))
        assertEquals(listOf(0, 1), created.map { it.closes })
        mirror.close()
        assertEquals(listOf(1, 1), created.map { it.closes })
    }

    @Test fun gridModeAndUnopenedClosedMirrorNeverAllocateNativeState() {
        val factory: (Int, Int) -> ByteTerminal = { _, _ -> error("must not create a VT engine") }
        TerminalStreamMirror("s", TerminalTransport(TerminalOutputMode.GRID, true), TerminalViewport(20, 4), factory).use {
            assertTrue(it.display is RenderGrid)
        }
        val unopened = TerminalStreamMirror("s", TerminalTransport(TerminalOutputMode.BYTES, false), TerminalViewport(20, 4), factory)
        unopened.close()
        assertThrows(IllegalStateException::class.java) { unopened.display }
    }
}
