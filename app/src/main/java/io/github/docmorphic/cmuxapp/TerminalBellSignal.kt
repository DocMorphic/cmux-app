package io.github.docmorphic.cmuxapp

/** Main-dispatcher transient effects: no replay when a view appears or resumes. */
internal class TerminalBellSignal {
    private val listeners = linkedSetOf<() -> Unit>()
    fun listen(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
    fun ring() { listeners.toList().forEach { it() } }
}
