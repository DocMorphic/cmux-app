package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** A screen-anchored mirror of cmux.render-grid.v1 with producer order checks. */
class RenderGrid {
    data class Style(
        val foreground: String?, val background: String?,
        val bold: Boolean, val italic: Boolean, val underline: Boolean,
        val inverse: Boolean, val invisible: Boolean,
        val faint: Boolean = false, val strikethrough: Boolean = false,
        val overline: Boolean = false, val blink: Boolean = false
    )
    data class Span(val column: Int, val width: Int, val text: String, val style: Style)
    data class Cursor(val row: Int, val column: Int, val visible: Boolean,
        val style: String, val blinking: Boolean = false)

    var surfaceId: String = ""
        private set
    var columns: Int = 0
        private set
    var rows: Int = 0
        private set
    var revision: Long = -1
        private set
    var epoch: String = ""
        private set
    var background: String = "#111316"
        private set
    var foreground: String = "#e0e5eb"
        private set
    var cursor: Cursor? = null
        private set
    var activeScreen: String = "primary"
        private set
    val lines: List<List<Span>> get() = content.map { it.toList() }
    val scrollbackLines: List<List<Span>> get() = history.map { it.toList() }
    private var content = mutableListOf<MutableList<Span>>()
    private val history = mutableListOf<MutableList<Span>>()
    private var historyRows: Long? = null

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
        if (nextSurface == surfaceId && nextEpoch == epoch && nextRevision < revision) return true
        if (!full) {
            if (nextSurface != surfaceId || nextEpoch != epoch ||
                nextColumns != columns || nextRows != rows ||
                (frame.has("delta_base_render_revision") && frame.optLong("delta_base_render_revision") != revision) ||
                (frame.has("delta_base_history_rows") && historyRows != null &&
                    frame.optLong("delta_base_history_rows") != historyRows)) return false
        }
        if (nextSurface == surfaceId && nextEpoch == epoch && nextRevision <= revision && !full) return true
        if (full || content.size != nextRows) {
            val preserveHistory = full && nextSurface == surfaceId && nextEpoch == epoch &&
                frame.optString("anchor") == "screen" && frame.optInt("scrollback_rows") == 0 &&
                frame.optString("active_screen", "primary") == "primary"
            if (!preserveHistory) history.clear()
            content = MutableList(nextRows) { mutableListOf() }
        } else {
            val scrolled = frame.optInt("scrolled_rows").coerceIn(0, 10_000)
            if (activeScreen == "primary") {
                val carried = frame.optInt("scrollback_rows").coerceIn(0, scrolled)
                val carriedSpans = frame.optJSONArray("scrollback_spans")
                val carriedStyles = if (carriedSpans != null) stylesFor(frame) else emptyMap()
                repeat(scrolled) { index ->
                    val removed = content.removeAt(0)
                    history.add(removed)
                    content.add(mutableListOf())
                    if (index < carried && carriedSpans != null)
                        history[history.lastIndex] = parseLine(carriedSpans, index, carriedStyles, nextColumns)
                }
                trimHistory()
            } else repeat(scrolled) { content.removeAt(0); content.add(mutableListOf()) }
            val cleared = frame.optJSONArray("cleared_rows")
            if (cleared != null) for (i in 0 until cleared.length()) {
                val row = cleared.optInt(i, -1)
                if (row in content.indices) content[row].clear()
            }
        }
        val styles = mutableMapOf<Int, Style>()
        val styleArray = frame.optJSONArray("styles")
        if (styleArray != null) for (i in 0 until styleArray.length()) {
            val style = styleArray.optJSONObject(i) ?: continue
            styles[style.optInt("id")] = Style(
                style.optString("foreground").takeIf { it.startsWith('#') },
                style.optString("background").takeIf { it.startsWith('#') },
                style.optBoolean("bold"), style.optBoolean("italic"),
                style.optBoolean("underline"), style.optBoolean("inverse"),
                style.optBoolean("invisible"), style.optBoolean("faint"),
                style.optBoolean("strikethrough"), style.optBoolean("overline"),
                style.optBoolean("blink")
            )
        }
        val defaultStyle = Style(null, null, false, false, false, false, false)
        if (full && frame.optInt("scrollback_rows") > 0 &&
            frame.optString("active_screen", "primary") != "alternate") {
            val count = frame.optInt("scrollback_rows").coerceIn(0, 10_000)
            val scrollback = frame.optJSONArray("scrollback_spans")
            if (scrollback != null) repeat(count) { history.add(parseLine(scrollback, it, styles, nextColumns)) }
            trimHistory()
        }
        val spans = frame.optJSONArray("row_spans")
        if (!full && spans != null) for (i in 0 until spans.length()) {
            val row = spans.optJSONObject(i)?.optInt("row", -1) ?: -1
            if (row in content.indices) content[row].clear()
        }
        if (spans != null) for (i in 0 until spans.length()) {
            val item = spans.optJSONObject(i) ?: continue
            val row = item.optInt("row", -1)
            val column = item.optInt("column", -1)
            val text = item.optString("text")
            val width = item.optInt("cell_width", text.codePointCount(0, text.length))
            if (row !in content.indices || column !in 0 until nextColumns ||
                width < 1 || column + width > nextColumns) continue
            content[row].removeAll { existing ->
                existing.column < column + width && column < existing.column + existing.width
            }
            content[row].add(Span(column, width, text, styles[item.optInt("style_id")] ?: defaultStyle))
        }
        for (line in content) line.sortBy { it.column }
        frame.optString("terminal_background").takeIf { it.startsWith('#') }?.let { background = it }
        frame.optString("terminal_foreground").takeIf { it.startsWith('#') }?.let { foreground = it }
        if (full || frame.has("cursor")) {
            cursor = frame.optJSONObject("cursor")?.let {
                Cursor(it.optInt("row"), it.optInt("column"), it.optBoolean("visible", true),
                    it.optString("style", "block"), it.optBoolean("blinking"))
            }
        }
        if (frame.has("active_screen")) activeScreen = frame.optString("active_screen", "primary")
        surfaceId = nextSurface
        columns = nextColumns
        rows = nextRows
        revision = nextRevision
        epoch = nextEpoch
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
                item.optBoolean("strikethrough"), item.optBoolean("overline"), item.optBoolean("blink")
            )
        }
        return result
    }

    private fun parseLine(array: org.json.JSONArray, row: Int, styles: Map<Int, Style>, columns: Int): MutableList<Span> {
        val result = mutableListOf<Span>()
        val fallback = Style(null, null, false, false, false, false, false)
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optInt("row", -1) != row) continue
            val column = item.optInt("column", -1)
            val value = item.optString("text")
            val width = item.optInt("cell_width", value.codePointCount(0, value.length))
            if (column in 0 until columns && width > 0 && column + width <= columns)
                result.add(Span(column, width, value, styles[item.optInt("style_id")] ?: fallback))
        }
        result.sortBy { it.column }
        return result
    }
}
