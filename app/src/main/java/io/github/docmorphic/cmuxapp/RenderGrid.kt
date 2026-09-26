package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** A screen-anchored mirror of cmux.render-grid.v1 with producer order checks. */
class RenderGrid {
    data class Style(
        val foreground: String?, val background: String?,
        val bold: Boolean, val italic: Boolean, val underline: Boolean,
        val inverse: Boolean, val invisible: Boolean
    )
    data class Span(val column: Int, val width: Int, val text: String, val style: Style)
    data class Cursor(val row: Int, val column: Int, val visible: Boolean, val style: String)

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
    private var content = mutableListOf<MutableList<Span>>()

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
        if (!full) {
            if (nextSurface != surfaceId || nextEpoch != epoch ||
                nextColumns != columns || nextRows != rows ||
                (frame.has("delta_base_render_revision") && frame.optLong("delta_base_render_revision") != revision)) return false
        }
        if (nextSurface == surfaceId && nextEpoch == epoch && nextRevision <= revision && !full) return true
        if (full || content.size != nextRows) {
            content = MutableList(nextRows) { mutableListOf() }
        } else {
            val scrolled = frame.optInt("scrolled_rows").coerceIn(0, nextRows)
            repeat(scrolled) { content.removeAt(0); content.add(mutableListOf()) }
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
                style.optBoolean("invisible")
            )
        }
        val defaultStyle = Style(null, null, false, false, false, false, false)
        val spans = frame.optJSONArray("row_spans")
        if (spans != null) for (i in 0 until spans.length()) {
            val item = spans.optJSONObject(i) ?: continue
            val row = item.optInt("row", -1)
            val column = item.optInt("column", -1)
            val text = item.optString("text")
            val width = item.optInt("cell_width", text.codePointCount(0, text.length))
            if (row !in content.indices || column !in 0 until nextColumns ||
                width < 1 || column + width > nextColumns) continue
            content[row].add(Span(column, width, text, styles[item.optInt("style_id")] ?: defaultStyle))
        }
        for (line in content) line.sortBy { it.column }
        frame.optString("terminal_background").takeIf { it.startsWith('#') }?.let { background = it }
        frame.optString("terminal_foreground").takeIf { it.startsWith('#') }?.let { foreground = it }
        frame.optJSONObject("cursor")?.let {
            cursor = Cursor(it.optInt("row"), it.optInt("column"), it.optBoolean("visible", true), it.optString("style", "block"))
        } ?: run { cursor = null }
        activeScreen = frame.optString("active_screen", "primary")
        surfaceId = nextSurface
        columns = nextColumns
        rows = nextRows
        revision = nextRevision
        epoch = nextEpoch
        return true
    }
}
