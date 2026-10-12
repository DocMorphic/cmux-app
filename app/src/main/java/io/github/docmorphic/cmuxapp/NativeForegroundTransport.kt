package io.github.docmorphic.cmuxapp

/** Reservation has no effect until the verified foreground holder accepts this exact handle. */
internal interface NativeForegroundTransport : AutoCloseable {
    /** Reject before transferring ownership; a successful transfer must not throw afterward. */
    fun activate(stillCurrent: () -> Boolean)
}
