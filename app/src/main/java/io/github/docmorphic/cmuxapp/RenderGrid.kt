package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** A screen-anchored mirror of cmux.render-grid.v1 with producer order checks. */
class RenderGrid : TerminalDisplay {
    data class Style(
        val foreground: String?, val background: String?,
        val bold: Boolean, val italic: Boolean, val underline: Boolean,
        val inverse: Boolean, val invisible: Boolean,
        val faint: Boolean = false, val strikethrough: Boolean = false,
        val overline: Boolean = false, val blink: Boolean = false,
        val foregroundSource: String? = null, val backgroundSource: String? = null,
        val foregroundPaletteIndex: Int? = null, val backgroundPaletteIndex: Int? = null
    )
    data class Span(val column: Int, val width: Int, val text: String, val style: Style)
    data class Cursor(val row: Int, val column: Int, val visible: Boolean,
        val style: String, val blinking: Boolean = false)

    var surfaceId: String = ""
        private set
    override var columns: Int = 0
        private set
    override var rows: Int = 0
        private set
    var revision: Long = -1
        private set
    var epoch: String = ""
        private set
    override var background: String = "#111316"
        private set
    override var foreground: String = "#e0e5eb"
        private set
    override var cursorColor: String? = null
        private set
    override var reverseVideo: Boolean = false
        private set
    private var palette: List<String> = emptyList()
    override var cursor: Cursor? = null
        private set
    override var activeScreen: String = "primary"
        private set
    override var applicationCursorKeys: Boolean = false
        private set
    override var bracketedPaste: Boolean = false
        private set
    val lines: List<List<Span>> get() = content.map { it.toList() }
    override val historyLineCount: Int get() = history.size
    val scrollbackLines: List<List<Span>> get() = history.map { it.toList() }
    private var content = mutableListOf<MutableList<Span>>()
    private val history = mutableListOf<MutableList<Span>>()
    private var historyRows: Long? = null
    private var rowSpaceRevision: Long? = null
    internal val scrollAnchor get() = TerminalScrollAnchor(surfaceId, epoch, columns, rows, activeScreen, rowSpaceRevision, historyRows)

    /** Copy only the rows the viewport displays, including its local scroll position. */
    override fun visibleLines(scrollOffset: Int): List<List<Span>> {
        val offset = if (activeScreen == "alternate") 0 else scrollOffset.coerceIn(0, history.size)
        val first = (history.size + content.size - rows - offset).coerceAtLeast(0)
        return (first until first + rows).map { index ->
            (if (index < history.size) history[index] else content.getOrNull(index - history.size).orEmpty()).toList()
        }
    }

    override fun foreground(style: Style): String = resolveColor(style.foregroundSource, style.foregroundPaletteIndex, style.foreground,
        if (reverseVideo) background else foreground)
    override fun background(style: Style): String = resolveColor(style.backgroundSource, style.backgroundPaletteIndex, style.background,
        if (reverseVideo) foreground else background)
    private fun resolveColor(source: String?, index: Int?, rgb: String?, fallback: String): String = when (source) {
        "default" -> fallback
        "palette" -> palette.getOrNull(index ?: -1) ?: rgb ?: fallback
        else -> rgb ?: fallback
    }

    companion object {
        fun plainText(lines: List<List<Span>>): String = lines.joinToString("\n") { spans -> buildString {
            var column = 0
            for (span in spans) {
                if (span.column > column) append(" ".repeat(span.column - column))
                append(if (span.style.invisible) " ".repeat(span.width) else span.text)
                column = span.column + span.width
            }
        }.trimEnd() }.trimEnd()
    }

    /** Returns false when an out-of-order delta requires a full replay. */
    fun apply(frame: JSONObject): Boolean {
        require(frame.optString("format") == "cmux.render-grid.v1") { "Unknown render grid version" }
        val nextSurface = frame.getString("surface_id")
        val nextColumns = frame.getInt("columns")
        val nextRows = frame.getInt("rows")
        require(nextColumns in 1..1000 && nextRows in 1..1000) { "Invalid grid dimensions" }
        val nextRevision = frame.optLong("render_revision", 0)
        val nextEpoch = frame.optString("render_epoch")
        val full = frame.optBoolean("full", true)
        val nextScreen = frame.optString("active_screen", if (full) "primary" else activeScreen)
        val nextRowSpace = if (frame.has("row_space_revision")) frame.optLong("row_space_revision") else null
        if (nextSurface == surfaceId && nextEpoch == epoch && nextEpoch.isNotEmpty() &&
            nextRevision > 0 && nextRevision <= revision) return true
        if (!full) {
            if (nextSurface != surfaceId || nextEpoch != epoch ||
                nextColumns != columns || nextRows != rows || nextScreen != activeScreen ||
                (nextRowSpace != null && rowSpaceRevision != null && nextRowSpace != rowSpaceRevision) ||
                (frame.has("delta_base_render_revision") && nextRevision <= frame.optLong("delta_base_render_revision")) ||
                (frame.has("delta_base_render_revision") && frame.optLong("delta_base_render_revision") != revision) ||
                (frame.has("delta_base_history_rows") && historyRows != null &&
                    frame.optLong("delta_base_history_rows") != historyRows)) return false
        }
        val styles = stylesFor(frame)
        val scrollbackCount = frame.optInt("scrollback_rows").coerceIn(0, 10_000)
        val carriedLines = parseLines(frame.optJSONArray("scrollback_spans"), scrollbackCount, styles, nextColumns)
        val frameLines = parseLines(frame.optJSONArray("row_spans"), nextRows, styles, nextColumns)
        if (full || content.size != nextRows) {
            val preserveHistory = full && nextSurface == surfaceId && nextEpoch == epoch &&
                nextColumns == columns && nextRows == rows && activeScreen == "primary" &&
                nextRowSpace == rowSpaceRevision && frame.optString("anchor") == "screen" && frame.optInt("scrollback_rows") == 0 &&
                frame.optString("active_screen", "primary") == "primary"
            if (!preserveHistory) history.clear()
            content = MutableList(nextRows) { mutableListOf() }
        } else {
            val scrolled = frame.optInt("scrolled_rows").coerceIn(0, 10_000)
            if (activeScreen == "primary") {
                val carried = frame.optInt("scrollback_rows").coerceIn(0, scrolled)
                val hasCarriedSpans = frame.has("scrollback_spans")
                repeat(scrolled) { index ->
                    val removed = content.removeAt(0)
                    history.add(removed)
                    content.add(mutableListOf())
                    if (index < carried && hasCarriedSpans)
                        history[history.lastIndex] = carriedLines[index].orEmpty().toMutableList()
                }
                trimHistory()
            } else repeat(scrolled) { content.removeAt(0); content.add(mutableListOf()) }
            val cleared = frame.optJSONArray("cleared_rows")
            if (cleared != null) for (i in 0 until cleared.length()) {
                val row = cleared.optInt(i, -1)
                if (row in content.indices) content[row].clear()
            }
        }
        if (full && scrollbackCount > 0 && nextScreen != "alternate") {
            if (frame.has("scrollback_spans")) repeat(scrollbackCount) { history.add(carriedLines[it].orEmpty().toMutableList()) }
            trimHistory()
        }
        frameLines.forEach { (row, spans) -> content[row] = spans.toMutableList() }
        if (nextSurface != surfaceId || nextEpoch != epoch) {
            foreground = "#e0e5eb"; background = "#111316"; palette = emptyList(); cursorColor = null
        }
        if (full) frame.optJSONObject("terminal_theme")?.optJSONArray("palette")?.let { colors ->
            if (colors.length() == 16 || colors.length() == 256) {
                palette = (0 until colors.length()).map { colors.optString(it) }
            }
        }
        if (full || frame.has("terminal_cursor_color")) {
            cursorColor = frame.optString("terminal_cursor_color").takeIf { it.startsWith('#') }
                ?: frame.optJSONObject("terminal_theme")?.optString("cursor")?.takeIf { it.startsWith('#') }
        }
        val theme = frame.optJSONObject("terminal_theme")
        val nextBackground = frame.optString("terminal_background").takeIf { it.startsWith('#') }
            ?: theme?.optString("background")?.takeIf { it.startsWith('#') }
        val nextForeground = frame.optString("terminal_foreground").takeIf { it.startsWith('#') }
            ?: theme?.optString("foreground")?.takeIf { it.startsWith('#') }
        if (full || nextBackground != null) background = nextBackground ?: "#111316"
        if (full || nextForeground != null) foreground = nextForeground ?: "#e0e5eb"
        if (full || frame.has("cursor")) {
            cursor = frame.optJSONObject("cursor")?.let {
                Cursor(it.optInt("row"), it.optInt("column"), it.optBoolean("visible", true),
                    it.optString("style", "block"), it.optBoolean("blinking"))
            }
        }
        activeScreen = nextScreen
        if (full) {
            val modes = frame.optJSONArray("modes")
            fun decMode(code: Int): Boolean {
                if (modes == null) return false
                for (index in 0 until modes.length()) {
                    val mode = modes.optJSONObject(index) ?: continue
                    if (!mode.optBoolean("ansi") && mode.optInt("code") == code)
                        return mode.optBoolean("on")
                }
                return false
            }
            applicationCursorKeys = decMode(1)
            bracketedPaste = decMode(2004)
            reverseVideo = decMode(5)
        }
        surfaceId = nextSurface
        columns = nextColumns
        rows = nextRows
        revision = nextRevision
        epoch = nextEpoch
        rowSpaceRevision = nextRowSpace
        historyRows = if (frame.has("history_rows")) frame.optLong("history_rows") else null
        return true
    }

    private fun trimHistory() {
        if (history.size > 10_000) history.subList(0, history.size - 10_000).clear()
    }

    private fun stylesFor(frame: JSONObject): Map<Int, Style> {
        val result = mutableMapOf<Int, Style>()
        val array = frame.optJSONArray("styles") ?: return result
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            result[item.optInt("id")] = Style(
                item.optString("foreground").takeIf { it.startsWith('#') },
                item.optString("background").takeIf { it.startsWith('#') },
                item.optBoolean("bold"), item.optBoolean("italic"), item.optBoolean("underline"),
                item.optBoolean("inverse"), item.optBoolean("invisible"), item.optBoolean("faint"),
                item.optBoolean("strikethrough"), item.optBoolean("overline"), item.optBoolean("blink"),
                item.optString("foreground_source").takeIf { it.isNotEmpty() },
                item.optString("background_source").takeIf { it.isNotEmpty() },
                if (item.has("foreground_palette_index")) item.optInt("foreground_palette_index") else null,
                if (item.has("background_palette_index")) item.optInt("background_palette_index") else null
            )
        }
        return result
    }

    private fun parseLines(array: org.json.JSONArray?, rows: Int, styles: Map<Int, Style>, columns: Int): Map<Int, List<Span>> {
        val result = mutableMapOf<Int, MutableList<Span>>()
        val fallback = Style(null, null, false, false, false, false, false)
        if (array == null) return result
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val row = item.optInt("row", -1)
            val column = item.optInt("column", -1)
            val value = item.optString("text")
            val width = if (item.has("cell_width")) item.optInt("cell_width") else maxOf(1, TerminalGlyphLayout.estimatedWidth(value))
            if (row in 0 until rows && column in 0 until columns && width > 0 && width <= columns - column) {
                val line = result.getOrPut(row) { mutableListOf() }
                line.removeAll { it.column < column + width && column < it.column + it.width }
                line.add(Span(column, width, value, styles[item.optInt("style_id")] ?: fallback))
            }
        }
        result.values.forEach { it.sortBy { span -> span.column } }
        return result
    }
}
