package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class SshTmuxProtocolTest {
    @Test fun splitUtf8AndOctalBytesReachTerminalWithoutStringConversion() {
        val parser = SshTmuxParser()
        val wire = "%output %7 \\033[32m".toByteArray() + byteArrayOf(0xe4.toByte()) + "\n%output %7 ".toByteArray() +
            byteArrayOf(0xb8.toByte(), 0xad.toByte()) + "\\134\\015\\012\n".toByteArray()
        val messages = wire.flatMap { parser.feed(byteArrayOf(it)) }.filterIsInstance<TmuxMessage.Output>()
        assertEquals(listOf(7, 7), messages.map { it.pane })
        assertArrayEquals("\u001b[32m中\\\r\n".toByteArray(), messages.flatMap { it.bytes.toList() }.toByteArray())
        assertArrayEquals("\\777\\x".toByteArray(), SshTmuxParser.unescape("\\777\\x".toByteArray()))
    }
    @Test fun capturedRowsLookingLikeProtocolRemainDataUntilExactGuard() {
        val parser = SshTmuxParser()
        val wire = "%begin 10 22 1\n\n%end 9 22 1\n%end 10 23 1\n%error 10 22 0\n%output %7 literal\n%end 10 22 1\n"
        val reply = parser.feed(wire.toByteArray()).single() as TmuxMessage.Reply
        assertEquals(22L, reply.number); assertEquals(1, reply.flags); assertFalse(reply.error)
        assertEquals(listOf("", "%end 9 22 1", "%end 10 23 1", "%error 10 22 0", "%output %7 literal"), reply.lines.map { it.toString(Charsets.UTF_8) })
    }
    @Test fun overflowFailsClosedInsteadOfParsingTruncatedCommands() {
        val line = SshTmuxParser(maxLineBytes = 10)
        assertThrows(IllegalStateException::class.java) { line.feed("x".repeat(11).toByteArray()) }
        assertThrows(IllegalStateException::class.java) { line.feed("\n%exit\n".toByteArray()) }
        val block = SshTmuxParser(maxBlockBytes = 2)
        assertThrows(IllegalStateException::class.java) { block.feed("%begin 1 1 1\naa\nb\n%end 1 1 1\n".toByteArray()) }
        val lines = SshTmuxParser(maxBlockLines = 2)
        assertThrows(IllegalStateException::class.java) { lines.feed("%begin 1 1 1\n\n\n\n".toByteArray()) }
    }
    @Test fun nestedGeometryPreservesPaneOrderAndRejectsPartialTrees() {
        assertEquals(listOf(TmuxLeaf(4, 39, 24, 0, 0), TmuxLeaf(8, 40, 11, 40, 0), TmuxLeaf(9, 40, 12, 40, 12)),
            SshTmuxLayout.parse("abcd,80x24,0,0{39x24,0,0,4,40x24,40,0[40x11,40,0,8,40x12,40,12,9]}"))
        for (bad in listOf("abcd,80x24,0,0{39x24,0,0,4", "80x24,0,0,4garbage", "0x24,0,0,4", "80x24,0,0{39x24,0,0,4,40x24,40,0,4}"))
            assertThrows(Exception::class.java) { SshTmuxLayout.parse(bad) }
    }
    @Test fun splitTitleStringsNeverAppearAsPaneText() {
        val filter = SshTmuxTitleFilter()
        val wire = "a\u001bkhidden\u001b\\b\u001bk${"x".repeat(2000)}\u0007c\u001b[32md\u001b\u001b[0m"
        val actual = wire.toByteArray().flatMap { filter.feed(byteArrayOf(it)).toList() }.toByteArray()
        assertArrayEquals("abc\u001b[32md\u001b\u001b[0m".toByteArray(), actual)
    }
    @Test fun stateSeedRestoresOriginAndCursorLastAndQuotesHostNames() {
        val seed = SshTmuxEncoding.stateSequence(mapOf("scroll_region_upper" to "4", "scroll_region_lower" to "10",
            "pane_height" to "24", "origin_flag" to "1", "cursor_x" to "2", "cursor_y" to "6", "wrap_flag" to "1", "mouse_sgr_flag" to "1", "bracket_paste_flag" to "1" )).toString(Charsets.UTF_8)
        assertTrue(seed.contains("\u001b[5;11r")); assertTrue(seed.endsWith("\u001b[?6h\u001b[3;3H"))
        assertTrue(seed.contains("\u001b[?1006h"))
        assertTrue(seed.contains("\u001b[?2004h"))
        assertEquals("'a'\\''b'", SshTmuxEncoding.shellQuote("a'b"))
        assertEquals("\"a\\\"\\\$b\\\\c\"", SshTmuxEncoding.quote("a\"\$b\\c"))
        assertThrows(IllegalArgumentException::class.java) { SshTmuxEncoding.quote("a\nkill-server") }
        assertNull(SshTmuxParser.id("%-1", '%')); assertNull(SshTmuxParser.id("%2147483648", '%'))
    }
}
