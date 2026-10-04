package io.github.docmorphic.cmuxapp

/** VT display. Network/PTY ownership belongs to its caller; Mac mirrors disable replies. */
interface ByteTerminal : TerminalDisplay, AutoCloseable {
    fun append(bytes: ByteArray)
    /** Drain parser effects separately so historical replay can discard them. */
    fun takeBell(): Boolean = false
}
