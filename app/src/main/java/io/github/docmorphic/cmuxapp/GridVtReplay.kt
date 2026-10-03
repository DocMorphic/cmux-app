package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Seed a compatibility VT parser from the same authoritative grid used by iOS. */
object GridVtReplay {
    fun replacement(frame: JSONObject): ByteArray {
        require(frame.optBoolean("full", true)) { "A terminal replay must contain a full frame" }
        val grid = RenderGrid().also { require(it.apply(frame)) }
        return buildString {
            append("\u001bc")
            append(theme(frame))
            if (grid.activeScreen == "alternate") append("\u001b[?1049h")
            append("\u001b[?7l\u001b[?6l\u001b[0m")
            val lines = (if (grid.activeScreen == "primary") grid.scrollbackLines else emptyList()) + grid.lines
            lines.forEachIndexed { index, spans ->
                if (index > 0) append("\r\n")
                spans.forEach { span ->
                    append(style(span.style))
                    TerminalGlyphLayout.layout(span.text, span.column, span.width).forEach { glyph ->
                        append("\u001b[${glyph.column + 1}G")
                        // Render-grid cells contain glyphs, never executable control strings.
                        append(glyph.text.filter { it >= ' ' && it !in '\u007f'..'\u009f' })
                    }
                }
                append("\u001b[0m")
            }
            append("\u001b[?7h")
            frame.optJSONArray("modes")?.let { modes ->
                for (i in 0 until modes.length()) {
                    val mode = modes.optJSONObject(i) ?: continue
                    val code = mode.optInt("code", -1)
                    val ansi = mode.optBoolean("ansi")
                    if (code < 0 || (!ansi && code in setOf(3, 12, 25, 47, 1047, 1048, 1049, 2026, 2031, 2048))) continue
                    append("\u001b[${if (ansi) "" else "?"}$code${if (mode.optBoolean("on")) "h" else "l"}")
                }
            }
            grid.cursor?.let { cursor ->
                append("\u001b[${cursor.row + 1};${cursor.column + 1}H")
                append("\u001b[${when (cursor.style) { "underline" -> 4; "bar" -> 6; else -> 2 }} q")
                append("\u001b[?25${if (cursor.visible) "h" else "l"}")
            }
        }.toByteArray(Charsets.UTF_8)
    }

    fun theme(frame: JSONObject): String = buildString {
        val theme = frame.optJSONObject("terminal_theme")
        fun color(key: String, fallback: String?, code: Int) {
            val value = frame.optString(key).takeIf(::validColor) ?: fallback?.takeIf(::validColor)
            if (value != null) append("\u001b]$code;$value\u001b\\")
        }
        color("terminal_foreground", theme?.optString("foreground"), 10)
        color("terminal_background", theme?.optString("background"), 11)
        color("terminal_cursor_color", theme?.optString("cursor"), 12)
        theme?.optJSONArray("palette")?.let { palette ->
            if (palette.length() == 16 || palette.length() == 256) for (i in 0 until palette.length()) {
                val value = palette.optString(i)
                if (validColor(value)) append("\u001b]4;$i;$value\u001b\\")
            }
        }
    }

    private fun style(style: RenderGrid.Style): String {
        val codes = mutableListOf("0")
        if (style.bold) codes += "1"
        if (style.faint) codes += "2"
        if (style.italic) codes += "3"
        if (style.underline) codes += "4"
        if (style.blink) codes += "5"
        if (style.inverse) codes += "7"
        if (style.invisible) codes += "8"
        if (style.strikethrough) codes += "9"
        if (style.overline) codes += "53"
        fun color(source: String?, index: Int?, value: String?, prefix: Int) {
            if (source == "default") return
            if (source == "palette" && index != null && index in 0..255) codes += "$prefix;5;$index"
            else if (value != null && validColor(value)) {
                val components = listOf(1, 3, 5).map { value.substring(it, it + 2).toInt(16) }
                codes += "$prefix;2;${components.joinToString(";")}"
            }
        }
        color(style.foregroundSource, style.foregroundPaletteIndex, style.foreground, 38)
        color(style.backgroundSource, style.backgroundPaletteIndex, style.background, 48)
        return "\u001b[${codes.joinToString(";")}m"
    }
    private fun validColor(value: String) = value.length == 7 && value[0] == '#' && value.drop(1).all { it.digitToIntOrNull(16) != null }
}
