package io.github.docmorphic.cmuxapp

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth

/** Parser state survives byte boundaries; the Mac remains the sole owner of the PTY. */
class VtTerminal(columns: Int, rows: Int) : TerminalDisplay {
    private val output = object : TerminalOutput() {
        // Device replies belong to the Mac's Ghostty instance. Mirroring them back
        // would duplicate replies and could turn output into unsolicited input.
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }
    private val engine = TerminalEmulator(output, columns.coerceIn(2, 1000), rows.coerceIn(2, 1000),
        10, 20, 10_000, SilentTerminalClient)

    init {
        engine.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = 0xffe0e5eb.toInt()
        engine.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = 0xff111316.toInt()
    }

    fun append(bytes: ByteArray) { engine.append(bytes, bytes.size) }
    override val columns get() = engine.columns
    override val rows get() = engine.rows
    override val foreground get() = hex(engine.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND])
    override val background get() = hex(engine.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
    override val cursorColor get() = hex(engine.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR])
    override val reverseVideo get() = engine.isReverseVideo
    override val cursor get() = RenderGrid.Cursor(engine.cursorRow, engine.cursorCol, engine.isCursorEnabled,
        when (engine.cursorStyle) { 1 -> "underline"; 2 -> "bar"; else -> "block" })
    override val activeScreen get() = if (engine.isAlternateBufferActive) "alternate" else "primary"
    override val applicationCursorKeys get() = engine.isCursorKeysApplicationMode
    override val bracketedPaste get() = engine.isBracketedPasteMode
    override val historyLineCount get() = if (engine.isAlternateBufferActive) 0 else engine.screen.activeTranscriptRows

    override fun visibleLines(scrollOffset: Int): List<List<RenderGrid.Span>> {
        val offset = scrollOffset.coerceIn(0, historyLineCount)
        val screen = engine.screen
        return (-offset until rows - offset).map { row ->
            val line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))
            buildList {
                var column = 0
                while (column < columns) {
                    val start = line.findStartOfColumn(column)
                    val point = Character.codePointAt(line.mText, start, line.spaceUsed)
                    val width = WcWidth.width(point).coerceIn(1, columns - column)
                    val end = line.findStartOfColumn(column + width)
                    val attributes = line.getStyle(column)
                    // Image protocols are retained by the engine, but are not text cells.
                    val text = if (TextStyle.isTerminalBitmap(attributes)) " " else String(line.mText, start, end - start)
                    add(RenderGrid.Span(column, width, text, style(attributes)))
                    column += width
                }
            }
        }
    }

    private fun style(value: Long): RenderGrid.Style {
        val effect = TextStyle.decodeEffect(value)
        fun flag(bit: Int) = effect and bit != 0
        fun source(color: Int, default: Int) = if (color == default) "default" else "rgb"
        val fg = TextStyle.decodeForeColor(value)
        val bg = TextStyle.decodeBackColor(value)
        val brightForeground = if (flag(TextStyle.CHARACTER_ATTRIBUTE_BOLD) && fg in 0..7) fg + 8 else fg
        fun color(index: Int) = hex(if (index in engine.mColors.mCurrentColors.indices) engine.mColors.mCurrentColors[index] else index)
        return RenderGrid.Style(color(brightForeground), color(bg),
            flag(TextStyle.CHARACTER_ATTRIBUTE_BOLD), flag(TextStyle.CHARACTER_ATTRIBUTE_ITALIC),
            flag(TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE), flag(TextStyle.CHARACTER_ATTRIBUTE_INVERSE),
            flag(TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE), flag(TextStyle.CHARACTER_ATTRIBUTE_DIM),
            flag(TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH), blink = flag(TextStyle.CHARACTER_ATTRIBUTE_BLINK),
            foregroundSource = source(fg, TextStyle.COLOR_INDEX_FOREGROUND),
            backgroundSource = source(bg, TextStyle.COLOR_INDEX_BACKGROUND))
    }

    override fun foreground(style: RenderGrid.Style) = if (style.foregroundSource == "default") {
        if (reverseVideo) background else foreground
    } else style.foreground ?: foreground
    override fun background(style: RenderGrid.Style) = if (style.backgroundSource == "default") {
        if (reverseVideo) foreground else background
    } else style.background ?: background

    private fun hex(color: Int) = "#" + (color and 0xffffff).toString(16).padStart(6, '0')
}

/** Avoid logging terminal content or invoking Android sessions from a mirror parser. */
private object SilentTerminalClient : TerminalSessionClient {
    override fun onTextChanged(session: TerminalSession) = Unit
    override fun onTitleChanged(session: TerminalSession) = Unit
    override fun onSessionFinished(session: TerminalSession) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}
