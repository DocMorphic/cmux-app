package io.github.docmorphic.cmuxapp

/** VT display. Network/PTY ownership belongs to its caller; Mac mirrors disable replies. */
interface ByteTerminal : TerminalDisplay, AutoCloseable {
    fun append(bytes: ByteArray)
}
