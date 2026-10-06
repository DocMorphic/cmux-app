package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.StateFlow

internal typealias SshImageUpload = suspend (ByteArray, String) -> String

/** View contract shared by plain PTYs and persistent multiplexer panes. */
internal interface SshTerminal : AutoCloseable {
    val id: String
    val title: String
    val state: StateFlow<SshShellState>
    val bells: TerminalBellSignal? get() = null
    val display: GhosttyVtTerminal
    val composer: SshComposerPool.Draft? get() = null
    val imageUpload: SshImageUpload? get() = null
    val transportLabel: String get() = "SSH"
    val acceptsInputWhileOpening: Boolean get() = false
    fun send(text: String, paste: Boolean = false): Boolean
    /** Composer submissions can wait for transport admission without blocking raw keyboard input. */
    suspend fun submitText(text: String): Boolean = send(text)
    /** Copies caller-owned bytes. Mouse protocols are not necessarily valid UTF-8. */
    fun sendBytes(bytes: ByteArray): Boolean
    fun resize(columns: Int, rows: Int, cells: TerminalCellMetrics)
    fun visible(visible: Boolean) {}
    suspend fun currentDirectory(): String? = null
}
