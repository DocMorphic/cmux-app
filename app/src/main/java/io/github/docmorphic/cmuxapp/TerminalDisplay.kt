package io.github.docmorphic.cmuxapp

/** Common read surface for the authoritative render grid and compatibility VT engine. */
interface TerminalDisplay {
    val columns: Int
    val rows: Int
    val foreground: String
    val background: String
    val cursorColor: String?
    val reverseVideo: Boolean
    val cursor: RenderGrid.Cursor?
    val activeScreen: String
    val applicationCursorKeys: Boolean
    val bracketedPaste: Boolean
    val historyLineCount: Int
    fun visibleLines(scrollOffset: Int = 0): List<List<RenderGrid.Span>>
    fun foreground(style: RenderGrid.Style): String
    fun background(style: RenderGrid.Style): String
}
