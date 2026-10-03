package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.StateFlow

/** View contract shared by plain PTYs and persistent multiplexer panes. */
internal interface SshTerminal : AutoCloseable {
    val id: String
    val title: String
    val state: StateFlow<SshShellState>
    val display: GhosttyVtTerminal
    fun send(text: String, paste: Boolean = false): Boolean
    /** Copies caller-owned bytes. Mouse protocols are not necessarily valid UTF-8. */
    fun sendBytes(bytes: ByteArray): Boolean
    fun resize(columns: Int, rows: Int, cells: TerminalCellMetrics)
    fun visible(visible: Boolean) {}
    suspend fun currentDirectory(): String? = null
}
