package io.github.docmorphic.cmuxapp.ghostty

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Immutable owned render snapshot. Colors are RGB; -1 means no explicit color. */
data class GhosttyFrame(
    val columns: Int, val rows: Int, val foreground: Int, val background: Int,
    val cursorColor: Int, val flags: Int, val cursorColumn: Int, val cursorRow: Int,
    val cursorStyle: Int, val historyRows: Int, val scrollOffset: Int,
    val lines: List<List<Cell>>
) {
    val reverseVideo get() = flags and 1 != 0
    val applicationCursorKeys get() = flags and 2 != 0
    val bracketedPaste get() = flags and 4 != 0
    val alternateScreen get() = flags and 8 != 0
    val cursorVisible get() = flags and 16 != 0
    val cursorBlinking get() = flags and 32 != 0

    data class Cell(val column: Int, val width: Int, val foreground: Int, val background: Int,
        val underlineColor: Int, val attributes: Int, val underlineStyle: Int, val text: String) {
        val bold get() = attributes and 1 != 0
        val italic get() = attributes and 2 != 0
        val faint get() = attributes and 4 != 0
        val blink get() = attributes and 8 != 0
        val inverse get() = attributes and 16 != 0
        val invisible get() = attributes and 32 != 0
        val strikethrough get() = attributes and 64 != 0
        val overline get() = attributes and 128 != 0
    }

    companion object {
        internal const val MAX_BYTES = 16 * 1024 * 1024
        internal fun decode(bytes: ByteArray): GhosttyFrame {
            require(bytes.size in 52..MAX_BYTES) { "Invalid Ghostty snapshot size" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            fun integer(): Int {
                require(buffer.remaining() >= 4) { "Truncated Ghostty snapshot" }
                return buffer.int
            }
            fun rgb(value: Int): Int { require(value in -1..0xffffff); return value }
            require(integer() == 0x47565431) { "Unknown Ghostty snapshot version" }
            val columns = integer().also { require(it in 1..1000) }
            val rows = integer().also { require(it in 1..1000) }
            val fg = rgb(integer()); val bg = rgb(integer()); val cursor = rgb(integer())
            require(fg >= 0 && bg >= 0)
            val flags = integer().also { require(it and 63 == it) }
            val cx = integer().also { require(it in 0 until columns) }
            val cy = integer().also { require(it in 0 until rows) }
            val cursorStyle = integer().also { require(it in 0..3) }
            val history = integer().also { require(it >= 0) }
            val offset = integer().also { require(it in 0..history) }
            require(integer() == rows)
            val lines = List(rows) {
                val count = integer().also { require(it in 0..columns) }
                var end = 0
                List(count) {
                    val column = integer().also { require(it in end until columns) }
                    val width = integer().also { require(it in 1..2 && column + it <= columns) }
                    end = column + width
                    val cellFg = rgb(integer()); val cellBg = rgb(integer()); val underline = rgb(integer())
                    val attributes = integer().also { require(it and 255 == it) }
                    val underlineStyle = integer().also { require(it in 0..5) }
                    val length = integer().also { require(it in 0..16384 && it <= buffer.remaining()) }
                    val text = ByteArray(length).also { buffer.get(it) }.toString(Charsets.UTF_8)
                    Cell(column, width, cellFg, cellBg, underline, attributes, underlineStyle, text)
                }
            }
            require(!buffer.hasRemaining()) { "Trailing Ghostty snapshot data" }
            return GhosttyFrame(columns, rows, fg, bg, cursor, flags, cx, cy, cursorStyle, history, offset, lines)
        }
    }
}
