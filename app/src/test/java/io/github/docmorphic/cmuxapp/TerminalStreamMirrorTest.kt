package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TerminalStreamMirrorTest {
    private val viewport = TerminalViewport(32, 8)
    private fun mirror(mode: TerminalOutputMode = TerminalOutputMode.BYTES) = TerminalStreamMirror("s", TerminalTransport(mode, false), viewport)
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun event(bytes: ByteArray, sequence: Long) = JSONObject().put("surface_id", "s").put("seq", sequence).put("data_b64", b64(bytes))
    private fun replay(text: String, sequence: Long) = JSONObject().put("snapshot_data_b64", b64(text.toByteArray())).put("seq", sequence)
    private fun text(mirror: TerminalStreamMirror) = RenderGrid.plainText(mirror.display.visibleLines())
    private fun frame(text: String, seq: Long, revision: Long, screen: String = "primary", full: Boolean = true) = JSONObject()
        .put("format", "cmux.render-grid.v1").put("surface_id", "s").put("columns", 32).put("rows", 8)
        .put("full", full).put("render_epoch", "epoch").put("render_revision", revision).put("state_seq", seq)
        .put("active_screen", screen).put("row_spans", JSONArray().put(JSONObject().put("row", 0)
            .put("column", 0).put("text", text).put("cell_width", text.length)))
        .put("cursor", JSONObject().put("row", 0).put("column", text.length).put("visible", true))

    @Test fun hostNegotiationMatchesIosPriorities() {
        val grid = "terminal.render_grid.v1"; val bytes = "terminal.bytes.v1"
        assertEquals(TerminalOutputMode.BYTES, TerminalTransport.resolve(emptySet()).mode)
        assertEquals(TerminalOutputMode.GRID, TerminalTransport.resolve(emptySet(), "render_grid").mode)
        assertEquals(TerminalOutputMode.HYBRID, TerminalTransport.resolve(setOf(grid, bytes)).mode)
        val anchored = TerminalTransport.resolve(setOf(grid, bytes, "terminal.render_grid.screen_anchor.v1"))
        assertEquals(TerminalOutputMode.GRID, anchored.mode); assertTrue(anchored.screenAnchor)
    }

    @Test fun chunkedUtf8AnsiAndOverlapsAreAppliedExactlyOnce() {
        val mirror = mirror()
        mirror.replay(replay("ready\r\n", 100))
        val output = "\u001b[38;2;20;200;100m中 café\u001b[0m".toByteArray()
        output.forEachIndexed { index, byte -> mirror.bytes(event(byteArrayOf(byte), 100L + index)) }
        assertTrue(text(mirror).contains("中 café"))
        mirror.bytes(event(output, 100))
        assertEquals(1, Regex("中 café").findAll(text(mirror)).count())
        val end = 100L + output.size
        mirror.bytes(event(output.takeLast(2).toByteArray() + "!".toByteArray(), end - 2))
        assertTrue(text(mirror).contains("中 café!"))
        val line = mirror.display.visibleLines()[1]
        assertEquals("#14c864", mirror.display.foreground(line.first().style))
        assertEquals(2, line.first().width)
    }

    @Test fun fullScreenModesEraseAndRestorePrimaryWithoutLeakingAlternateHistory() {
        val terminal = VtTerminal(20, 5)
        fun write(value: String) = terminal.append(value.toByteArray())
        write("shell\r\n\u001b[?1049h\u001b[2J\u001b[Hvim\u001b[3;4Hstatus\u001b[?1h\u001b[?2004h")
        assertEquals("alternate", terminal.activeScreen)
        assertEquals("vim", RenderGrid.plainText(terminal.visibleLines()).lineSequence().first())
        assertTrue(terminal.applicationCursorKeys); assertTrue(terminal.bracketedPaste)
        assertEquals(0, terminal.historyLineCount)
        write("\u001b[3;4H\u001b[Knew")
        assertTrue(RenderGrid.plainText(terminal.visibleLines()).contains("   new"))
        assertFalse(RenderGrid.plainText(terminal.visibleLines()).contains("status"))
        write("\u001b[?1049l\u001b[?1l\u001b[?2004l")
        assertEquals("primary", terminal.activeScreen)
        assertTrue(RenderGrid.plainText(terminal.visibleLines()).startsWith("shell"))
        assertFalse(terminal.applicationCursorKeys); assertFalse(terminal.bracketedPaste)
    }

    @Test fun replayBuffersPostBaselineBytesAndRecoversGapsWithoutDuplicatingOutput() {
        val mirror = mirror()
        mirror.bytes(event("covered".toByteArray(), 90))
        mirror.bytes(event("B".toByteArray(), 100))
        assertEquals(TerminalStreamMirror.Result.APPLIED, mirror.replay(replay("A", 100)))
        assertEquals("AB", text(mirror))
        assertEquals(TerminalStreamMirror.Result.REPLAY, mirror.bytes(event("Z".toByteArray(), 120)))
        assertEquals("AB", text(mirror))
        assertEquals(TerminalStreamMirror.Result.APPLIED, mirror.replay(replay("recovered Z", 121)))
        assertEquals("recovered Z", text(mirror))
        mirror.bytes(event("!".toByteArray(), 121))
        assertEquals("recovered Z!", text(mirror))
        mirror.beginReplay()
        assertEquals(TerminalStreamMirror.Result.REPLAY, mirror.replay(replay("old", 90)))
        assertEquals("recovered Z!", text(mirror))
    }

    @Test fun hybridUsesBytesForPrimaryAndGridForAlternateThenReseedsPrimary() {
        val mirror = mirror(TerminalOutputMode.HYBRID)
        mirror.replay(JSONObject().put("render_grid", frame("prompt", 50, 1)))
        mirror.bytes(event("!".toByteArray(), 50))
        mirror.grid(frame("advisory", 51, 2))
        assertEquals("prompt!", text(mirror))
        assertEquals(TerminalStreamMirror.Result.REPLAY, mirror.grid(frame("alt", 60, 3, "alternate", false)))
        assertEquals(TerminalStreamMirror.Result.APPLIED, mirror.grid(frame("vim grid", 60, 4, "alternate")))
        mirror.bytes(event("must not duplicate".toByteArray(), 60))
        assertEquals("vim grid", text(mirror))
        mirror.grid(frame("back", 100, 5))
        mirror.bytes(event("!".toByteArray(), 100))
        assertEquals("back!", text(mirror))
        mirror.grid(frame("old alt", 60, 4, "alternate"))
        assertEquals("back!", text(mirror))
    }

    @Test fun gridReplaySeedsScrollbackAndPreservesStylesAndInputModes() {
        val value = frame("visible", 1, 1).put("scrollback_rows", 2)
            .put("scrollback_spans", JSONArray().put(JSONObject().put("row", 0).put("column", 0).put("text", "old one"))
                .put(JSONObject().put("row", 1).put("column", 0).put("text", "old two")))
            .put("modes", JSONArray().put(JSONObject().put("code", 1).put("on", true)))
            .put("styles", JSONArray().put(JSONObject().put("id", 0).put("foreground", "#abcdef").put("underline", true)))
        val mirror = mirror(); mirror.replay(JSONObject().put("render_grid", value))
        assertEquals("visible", text(mirror))
        assertEquals(2, mirror.historyLineCount)
        assertTrue(RenderGrid.plainText(mirror.display.visibleLines(2)).startsWith("old one\nold two"))
        assertTrue(mirror.display.applicationCursorKeys)
        val style = mirror.display.visibleLines()[0][0].style
        assertTrue(style.underline); assertEquals("#abcdef", mirror.display.foreground(style))
    }

    @Test fun malformedBytesNeverChangeTheVisibleTerminal() {
        val mirror = mirror(); mirror.replay(replay("safe", 10))
        for (value in listOf(JSONObject().put("surface_id", "s").put("data_b64", "!bad"),
            event("x".toByteArray(), -1))) {
            assertThrows(IllegalArgumentException::class.java) { mirror.bytes(value) }
            assertEquals("safe", text(mirror))
        }
        assertThrows(IllegalArgumentException::class.java) {
            mirror.bytes(event("x".toByteArray(), 0).put("seq", java.math.BigInteger(ULong.MAX_VALUE.toString())))
        }
        assertEquals("safe", text(mirror))
    }

    @Test fun capturedVimSessionRendersEditsAndReturnsToTheShell() {
        val fixture = JSONObject(javaClass.getResource("/terminal/vim-session.json")!!.readText())
        val terminal = VtTerminal(fixture.getInt("columns"), fixture.getInt("rows"))
        terminal.append("shell baseline".toByteArray())
        fun feed(key: String) {
            val bytes = Base64.getDecoder().decode(fixture.getString(key))
            // Include boundaries inside CSI commands and multi-byte characters.
            bytes.toList().chunked(7).forEach { terminal.append(it.toByteArray()) }
        }
        feed("opened_b64")
        assertEquals("alternate", terminal.activeScreen)
        assertTrue(RenderGrid.plainText(terminal.visibleLines()).contains("cmux terminal fixture"))
        assertTrue(RenderGrid.plainText(terminal.visibleLines()).contains("日本語"))
        feed("edited_b64")
        assertTrue(RenderGrid.plainText(terminal.visibleLines()).startsWith("Edited cmux terminal fixture"))
        feed("exited_b64")
        assertEquals("primary", terminal.activeScreen)
        assertTrue(RenderGrid.plainText(terminal.visibleLines()).contains("shell baseline"))
    }

    @Test fun boundedPendingOutputRequiresFreshReplayAfterOverflow() {
        val mirror = mirror()
        val chunk = ByteArray(1_200_000) { 'x'.code.toByte() }
        mirror.bytes(event(chunk, 0))
        mirror.bytes(event(chunk, 1_200_000))
        assertEquals(TerminalStreamMirror.Result.REPLAY, mirror.replay(replay("caught up", 2_400_000)))
        assertEquals(TerminalStreamMirror.Result.APPLIED, mirror.replay(replay("fresh", 2_400_000)))
        assertEquals("fresh", text(mirror))
        val many = mirror()
        repeat(257) { many.bytes(event(byteArrayOf('x'.code.toByte()), it.toLong())) }
        assertEquals(TerminalStreamMirror.Result.REPLAY, many.replay(replay("bounded", 257)))
        assertEquals(TerminalStreamMirror.Result.APPLIED, many.replay(replay("fresh", 257)))
    }

    @Test fun hybridSnapshotFallbackReplacesOldAlternateGridAndAcceptsLiveBytes() {
        val mirror = mirror(TerminalOutputMode.HYBRID)
        mirror.replay(JSONObject().put("render_grid", frame("old grid", 10, 1, "alternate")))
        mirror.beginReplay()
        mirror.replay(replay("\u001b[?1049h\u001b[Hnew VT", 50))
        assertEquals("new VT", text(mirror))
        mirror.bytes(event("!".toByteArray(), 50))
        assertEquals("new VT!", text(mirror))
        assertThrows(IllegalArgumentException::class.java) {
            mirror.replay(JSONObject().put("render_grid", frame("wrong", 70, 2).put("surface_id", "another")))
        }
        assertEquals("new VT!", text(mirror))
    }
}
