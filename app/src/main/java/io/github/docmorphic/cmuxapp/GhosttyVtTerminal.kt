package io.github.docmorphic.cmuxapp

import io.github.docmorphic.cmuxapp.ghostty.GhosttyFrame
import io.github.docmorphic.cmuxapp.ghostty.GhosttyTerminal

/** Android VT path. Cached owned frames can finish painting after native close. */
class GhosttyVtTerminal(columns: Int, rows: Int, private val onReply: ((ByteArray) -> Unit)? = null) : ByteTerminal, TerminalGraphicsDisplay {
    private val engine = GhosttyTerminal(columns, rows, replyToQueries = onReply != null)
    private var closed = false
    private var dirty = false
    private var cellWidth = 0
    private var cellHeight = 0
    private var heldScrollPosition = 0.0
    private val graphicsFrames = LinkedHashMap<Int, TerminalGraphicsDisplay.Snapshot>()
    private var live: GhosttyFrame
    private var liveLines: List<List<RenderGrid.Span>>
    init {
        try {
            live = engine.snapshot()
            liveLines = lines(live)
        } catch (failure: Throwable) { engine.close(); throw failure }
    }
    private var historyOffset = -1
    private var historyLines: List<List<RenderGrid.Span>> = emptyList()
    private val frame: GhosttyFrame get() {
        if (dirty && !closed) {
            val next = engine.snapshot()
            val nextLines = lines(next)
            live = next; liveLines = nextLines; dirty = false
            historyOffset = -1; historyLines = emptyList()
        }
        return live
    }

    override fun append(bytes: ByteArray) {
        check(!closed) { "Ghostty terminal is closed" }
        val replies = engine.append(bytes)
        dirty = true; graphicsFrames.clear()
        if (replies.isNotEmpty()) onReply?.invoke(replies)
    }
    override fun takeBell(): Boolean = engine.takeBell()

    fun setCellMetrics(cells: TerminalCellMetrics) {
        require(cells.widthPx.isFinite() && cells.heightPx.isFinite())
        val width = cells.widthPx.toInt().coerceIn(1, 4096)
        val height = cells.heightPx.toInt().coerceIn(1, 4096)
        if (closed || (width == cellWidth && height == cellHeight)) return
        resize(columns, rows, width, height)
    }

    fun inputModes() = engine.inputModes()
    fun holdScrollback(position: Double): Double {
        require(position.isFinite() && position >= 0)
        heldScrollPosition = if (closed) position.coerceAtMost(historyLineCount.toDouble())
            else engine.holdScrollback(position)
        return heldScrollPosition
    }
    fun scrollbackPosition(): Double {
        if (!closed) heldScrollPosition = engine.scrollbackPosition()
        return heldScrollPosition
    }
    fun mouse(action: Int, button: Int, cell: TerminalGeometry.Cell): ByteArray =
        engine.mouse(action, button, cell.column.coerceIn(0, columns - 1), cell.row.coerceIn(0, rows - 1))

    /** SSH owns terminal dimensions; Mac mirrors continue using replay replacement. */
    fun resize(columns: Int, rows: Int, width: Int, height: Int) {
        check(!closed) { "Ghostty terminal is closed" }
        val replies = engine.resize(columns.coerceIn(1, 1000), rows.coerceIn(1, 1000), width, height)
        cellWidth = width; cellHeight = height; dirty = true
        graphicsFrames.clear()
        if (replies.isNotEmpty()) onReply?.invoke(replies)
    }

    override fun graphicsSnapshot(scrollOffset: Int, cells: TerminalCellMetrics): TerminalGraphicsDisplay.Snapshot? {
        setCellMetrics(cells)
        val offset = scrollOffset.coerceIn(0, frame.historyRows)
        graphicsFrames[offset]?.let { return it }
        if (closed) return null
        val snapshot = TerminalGraphicsDisplay.Snapshot(engine.graphicsSnapshot(offset), cellWidth, cellHeight)
        graphicsFrames[offset] = snapshot
        // Fractional scrolling may ask for two neighboring viewports.
        while (graphicsFrames.size > 2) graphicsFrames.remove(graphicsFrames.keys.first())
        return snapshot
    }

    /** Capture the owner on its UI thread; native reads then run off-main under
     * the engine's lifetime lock. Closed displays expose only retained paint data. */
    internal fun textReader(): () -> String {
        if (!closed) return engine::copyText
        val retained = RenderGrid.plainText(liveLines)
        return { retained }
    }

    override fun close() {
        if (closed) return
        scrollbackPosition()
        closed = true
        engine.close()
    }
    override val columns get() = frame.columns
    override val rows get() = frame.rows
    override val foreground get() = hex(frame.foreground)!!
    override val background get() = hex(frame.background)!!
    override val cursorColor get() = hex(frame.cursorColor)
    override val reverseVideo get() = frame.reverseVideo
    override val activeScreen get() = if (frame.alternateScreen) "alternate" else "primary"
    override val applicationCursorKeys get() = frame.applicationCursorKeys
    override val bracketedPaste get() = frame.bracketedPaste
    override val historyLineCount get() = frame.historyRows
    override val cursor get() = frame.let { RenderGrid.Cursor(it.cursorRow, it.cursorColumn,
        it.cursorVisible, when (it.cursorStyle) { 0 -> "bar"; 2 -> "underline"; 3 -> "hollow"; else -> "block" },
        it.cursorBlinking) }

    override fun visibleLines(scrollOffset: Int): List<List<RenderGrid.Span>> {
        val offset = scrollOffset.coerceIn(0, frame.historyRows)
        if (offset == 0) return liveLines
        if (offset == historyOffset) return historyLines
        // Composition may finish painting the previous viewport during a replay.
        // Closed owners expose copied content only; they never reenter JNI.
        if (closed) return liveLines
        val next = lines(engine.snapshot(offset))
        historyLines = next; historyOffset = offset
        return next
    }

    override fun foreground(style: RenderGrid.Style) = style.foreground
        ?: if (reverseVideo) background else foreground
    override fun background(style: RenderGrid.Style) = style.background
        ?: if (reverseVideo) foreground else background

    private fun lines(frame: GhosttyFrame) = frame.lines.map { row -> row.map { cell ->
        RenderGrid.Span(cell.column, cell.width, cell.text, RenderGrid.Style(
            foreground = hex(cell.foreground), background = hex(cell.background),
            bold = cell.bold, italic = cell.italic, underline = cell.underlineStyle != 0,
            inverse = cell.inverse, invisible = cell.invisible, faint = cell.faint,
            strikethrough = cell.strikethrough, overline = cell.overline, blink = cell.blink,
            foregroundSource = if (cell.foreground == -1) "default" else "rgb",
            backgroundSource = if (cell.background == -1) "default" else "rgb",
            underlineStyle = cell.underlineStyle, underlineColor = hex(cell.underlineColor)))
    } }

    private fun hex(value: Int): String? = if (value < 0) null else "#" + value.toString(16).padStart(6, '0')
}
