package io.github.docmorphic.cmuxapp

/** Port of iOS's resize acknowledgement / redraw / presentation transaction. */
internal class KeyboardTransitionPresentationFreeze {
    private var transitionEnded = false
    private var awaitedReport: Long? = null
    private var reportPending = true
    private var reportConfirmed = false
    private var outputApplied = false
    private var revealFloor = 0L

    fun transitionEnded() { transitionEnded = true }
    fun reportPublished(id: Long) {
        awaitedReport = id; reportPending = false; reportConfirmed = false; outputApplied = false
    }
    fun reportUnneeded(lastToken: Long) {
        if (awaitedReport != null || !reportPending) return
        reportPending = false; reportConfirmed = true; outputApplied = true
        revealFloor = maxOf(revealFloor, lastToken)
    }
    fun reportConfirmed(id: Long) { if (id == awaitedReport) reportConfirmed = true }
    fun outputApplied(lastToken: Long) {
        if (!reportConfirmed) return
        outputApplied = true; revealFloor = maxOf(revealFloor, lastToken)
    }
    fun canPresent(token: Long) = transitionEnded && !reportPending && reportConfirmed && outputApplied && token > revealFloor
}
