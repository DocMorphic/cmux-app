package io.github.docmorphic.cmuxapp

/** A remote VT mirror; it never owns a PTY or sends terminal-generated replies. */
interface ByteTerminal : TerminalDisplay, AutoCloseable {
    fun append(bytes: ByteArray)
}
