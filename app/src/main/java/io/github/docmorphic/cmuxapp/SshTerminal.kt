package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.StateFlow

/** View contract shared by plain PTYs and persistent multiplexer panes. */
internal interface SshTerminal : AutoCloseable {
    val id: String
    val title: String
    val state: StateFlow<SshShellState>
    val display: GhosttyVtTerminal
    fun send(text: String, paste: Boolean = false): Boolean
    fun resize(columns: Int, rows: Int, cells: TerminalCellMetrics)
    fun visible(visible: Boolean) {}
}
