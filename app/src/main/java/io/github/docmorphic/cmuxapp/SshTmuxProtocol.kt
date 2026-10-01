package io.github.docmorphic.cmuxapp

import java.io.ByteArrayOutputStream

/** tmux -C over a non-PTY exec channel. Protocol/seed behavior follows cmux
 * MobileSSHTmuxControl{Parser,Client} at 204a11d (see NOTICE.md).
 * Never decode pane data as text before Ghostty: UTF-8 may span notifications. */
internal sealed interface TmuxMessage {
    data class Output(val pane: Int, val bytes: ByteArray) : TmuxMessage
    data class Reply(val number: Long, val flags: Int, val lines: List<ByteArray>, val error: Boolean) : TmuxMessage
    data class Notice(val kind: String, val arguments: List<String>) : TmuxMessage
}

internal class SshTmuxParser(private val maxLineBytes: Int = 4 * 1024 * 1024,
    private val maxBlockBytes: Int = 8 * 1024 * 1024, private val maxBlockLines: Int = 10000) {
    private val line = ByteArrayOutputStream()
    private data class Block(val time: Long, val number: Long, val flags: Int,
        val lines: MutableList<ByteArray> = mutableListOf(), var bytes: Int = 0)
    private var block: Block? = null
    private var failed = false
    init { require(maxLineBytes > 0 && maxBlockBytes > 0 && maxBlockLines > 0) }
    fun feed(bytes: ByteArray): List<TmuxMessage> {
        check(!failed) { "tmux parser is closed after a protocol limit failure" }
        try {
            val result = mutableListOf<TmuxMessage>()
            for (byte in bytes) {
                if (byte == 10.toByte()) {
                    var row = line.toByteArray(); line.reset()
                    if (row.lastOrNull() == 13.toByte()) row = row.copyOf(row.size - 1)
                    parse(row)?.let(result::add)
                } else {
                    check(line.size() < maxLineBytes) { "tmux control line exceeded its limit" }
                    line.write(byte.toInt())
                }
            }
            return result
        } catch (failure: Exception) { failed = true; line.reset(); block = null; throw failure }
    }
    private fun parse(bytes: ByteArray): TmuxMessage? {
        val text by lazy { bytes.toString(Charsets.UTF_8) }
        val fields by lazy { text.split(' ') }
        block?.let { open ->
            if ((fields.firstOrNull() == "%end" || fields.firstOrNull() == "%error") &&
                fields.getOrNull(1)?.toLongOrNull() == open.time && fields.getOrNull(2)?.toLongOrNull() == open.number &&
                fields.getOrNull(3)?.toIntOrNull() == open.flags) {
                block = null
                return TmuxMessage.Reply(open.number, open.flags, open.lines.toList(), fields[0] == "%error")
            }
            check(bytes.size <= maxBlockBytes - open.bytes && open.lines.size < maxBlockLines) { "tmux reply exceeded its limit" }
            open.bytes += bytes.size; open.lines += bytes
            return null
        }
        if (bytes.isEmpty()) return null
        val prefix = "%output ".toByteArray(Charsets.US_ASCII)
        if (bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }) {
            val separator = (prefix.size until bytes.size).firstOrNull { bytes[it] == 32.toByte() }
            if (separator != null) {
                val pane = id(bytes.copyOfRange(prefix.size, separator).toString(Charsets.US_ASCII), '%')
                if (pane != null) return TmuxMessage.Output(pane, unescape(bytes.copyOfRange(separator + 1, bytes.size)))
            }
            error("Malformed tmux pane output")
        }
        if (fields[0] == "%begin") {
            val time = fields.getOrNull(1)?.toLongOrNull(); val number = fields.getOrNull(2)?.toLongOrNull()
            val flags = fields.getOrNull(3)?.toIntOrNull()
            check(time != null && time >= 0 && number != null && number >= 0 && flags != null && flags >= 0) { "Malformed tmux reply" }
            block = Block(time, number, flags); return null
        }
        return TmuxMessage.Notice(fields[0], fields.drop(1))
    }
    companion object {
        fun id(token: String, sigil: Char): Int? = token.takeIf { it.firstOrNull() == sigil }
            ?.drop(1)?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()
        fun unescape(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(bytes.size); var i = 0
            while (i < bytes.size) {
                if (bytes[i] == 92.toByte() && i + 3 < bytes.size && (1..3).all { bytes[i + it] in 48..55 }) {
                    val value = (bytes[i + 1] - 48) * 64 + (bytes[i + 2] - 48) * 8 + bytes[i + 3] - 48
                    if (value <= 255) { out.write(value); i += 4; continue }
                }
                out.write(bytes[i].toInt()); i++
            }
            return out.toByteArray()
        }
    }
}

internal data class TmuxLeaf(val pane: Int, val columns: Int, val rows: Int, val x: Int, val y: Int)
internal object SshTmuxLayout {
    /** Parse whole trees, including nested splits; reject malformed/oversized geometry. */
    fun parse(layout: String): List<TmuxLeaf> {
        require(layout.length <= 65536)
        val text = if (layout.length > 5 && layout[4] == ',' && layout.take(4).all { it in "0123456789abcdefABCDEF" }) layout.drop(5) else layout
        var index = 0; val result = mutableListOf<TmuxLeaf>()
        fun number(): Int {
            val start = index
            while (index < text.length && text[index] in '0'..'9') index++
            return text.substring(start, index).toIntOrNull()?.takeIf { it >= 0 } ?: error("Invalid tmux layout number")
        }
        fun expect(c: Char) { check(text.getOrNull(index++) == c) { "Malformed tmux layout" } }
        fun cell(depth: Int) {
            check(depth <= 64 && result.size < 2048) { "tmux layout exceeded its limit" }
            val width = number(); expect('x'); val height = number(); expect(',')
            val x = number(); expect(','); val y = number()
            check(width in 1..65535 && height in 1..65535 && x <= 65535 && y <= 65535)
            when (text.getOrNull(index++)) {
                ',' -> result += TmuxLeaf(number(), width, height, x, y)
                '{', '[' -> {
                    val end = if (text[index - 1] == '{') '}' else ']'
                    cell(depth + 1)
                    while (text.getOrNull(index) == ',') { index++; cell(depth + 1) }
                    expect(end)
                }
                else -> error("Malformed tmux layout cell")
            }
        }
        cell(0); check(index == text.length && result.map { it.pane }.distinct().size == result.size)
        return result
    }
}

/** Screen title strings are consumed by tmux; Ghostty must not print their text. */
internal class SshTmuxTitleFilter {
    private var state = 0
    fun feed(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        fun consume(value: Int) {
            when (state) {
                0 -> if (value == 27) state = 1 else out.write(value)
                1 -> when (value) { 107 -> state = 2; 27 -> out.write(27); else -> { out.write(27); out.write(value); state = 0 } }
                2 -> when (value) { 27 -> state = 3; 7 -> state = 0 }
                3 -> { state = 0; if (value != 92) { state = 1; consume(value) } }
            }
        }
        bytes.forEach { consume(it.toInt() and 255) }; return out.toByteArray()
    }
}

internal object SshTmuxEncoding {
    const val GROUP_MARKER = "-cmux-android-"
    const val HISTORY_LINES = 2000
    val stateKeys = listOf("cursor_x", "cursor_y", "scroll_region_upper", "scroll_region_lower", "cursor_flag", "insert_flag",
        "keypad_cursor_flag", "keypad_flag", "wrap_flag", "origin_flag", "bracket_paste_flag", "pane_width", "pane_height",
        "mouse_all_flag", "mouse_button_flag", "mouse_standard_flag", "mouse_sgr_flag", "mouse_utf8_flag")
    val stateFormat = stateKeys.joinToString(",") { "$it=#{${it}}" }
    fun shellQuote(word: String): String { require('\u0000' !in word); return "'" + word.replace("'", "'\\''") + "'" }
    fun quote(word: String): String {
        require(word.none { it == '\n' || it == '\r' || it == '\u0000' })
        return "\"" + word.flatMap { if (it in "\\\"$") listOf('\\', it) else listOf(it) }.joinToString("") + "\""
    }
    fun startCommand(tmux: String, session: String, grouped: String) =
        "$tmux -C new-session -t ${shellQuote("=$session")} -s ${shellQuote(grouped)} \\; set-option -t ${shellQuote("=$grouped:")} destroy-unattached off"
    fun fields(line: ByteArray?) = line?.toString(Charsets.UTF_8)?.split(',')?.mapNotNull {
        val index = it.indexOf('='); if (index < 1) null else it.take(index) to it.drop(index + 1)
    }?.toMap().orEmpty()
    fun stateSequence(fields: Map<String, String>): ByteArray {
        fun on(key: String) = fields[key] == "1"
        fun num(key: String) = fields[key]?.toIntOrNull()?.takeIf { it in 0..65535 }
        val s = StringBuilder("\u001b[m"); val upper = num("scroll_region_upper"); val lower = num("scroll_region_lower")
        val restricted = upper != null && lower != null && lower >= upper && !(upper == 0 && lower == num("pane_height")?.minus(1))
        if (restricted) s.append("\u001b[${upper!! + 1};${lower!! + 1}r")
        fun mode(number: Int, key: String, dec: Boolean = true) { s.append("\u001b[${if (dec) "?" else ""}$number${if (on(key)) 'h' else 'l'}") }
        mode(7, "wrap_flag"); mode(25, "cursor_flag"); mode(4, "insert_flag", false); mode(1, "keypad_cursor_flag")
        mode(2004, "bracket_paste_flag")
        s.append(if (on("keypad_flag")) "\u001b=" else "\u001b>")
        s.append("\u001b[?1000l\u001b[?1002l\u001b[?1003l\u001b[?1005l\u001b[?1006l")
        when { on("mouse_all_flag") -> s.append("\u001b[?1003h"); on("mouse_button_flag") -> s.append("\u001b[?1002h"); on("mouse_standard_flag") -> s.append("\u001b[?1000h") }
        when { on("mouse_sgr_flag") -> s.append("\u001b[?1006h"); on("mouse_utf8_flag") -> s.append("\u001b[?1005h") }
        mode(6, "origin_flag")
        val x = num("cursor_x"); val y = num("cursor_y")
        if (x != null && y != null) s.append("\u001b[${(if (on("origin_flag") && restricted) (y - upper!!).coerceAtLeast(0) else y) + 1};${x + 1}H")
        return s.toString().toByteArray(Charsets.UTF_8)
    }
}
