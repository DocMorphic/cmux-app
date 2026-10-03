package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*

/** Owned by one connection and surface; all calls run on the UI thread. */
internal class TerminalKeyboardPresentation {
    private data class Report(val id: Long, val viewport: TerminalViewport, var confirmed: Boolean = false,
        var outputRevision: Int? = null)
    private var report: Report? = null
    private var freeze: KeyboardTransitionPresentationFreeze? = null
    private var target: TerminalViewport? = null
    private var keyboard: Int? = null
    private var wasEnabled = false
    private var token = 0L
    private var requiredRevision: Int? = null
    private var legEnded = false
    var silenceEpoch by mutableIntStateOf(0)
        private set
    val waitingAfterTransition get() = frozen && legEnded
    var frozen by mutableStateOf(false)
        private set
    var drawInvalidation by mutableIntStateOf(0)
        private set
    var visibleText by mutableStateOf<String?>(null)
        private set

    fun transition(enabled: Boolean, moving: Boolean, keyboard: Int, viewport: TerminalViewport?) {
        if (!enabled || viewport == null) {
            cancel(); this.keyboard = keyboard; wasEnabled = enabled; return
        }
        val changed = wasEnabled && this.keyboard != null && this.keyboard != keyboard
        val replaced = frozen && target != viewport
        if (changed || replaced) {
            freeze = KeyboardTransitionPresentationFreeze()
            target = viewport; requiredRevision = null; frozen = true; legEnded = false; silenceEpoch++
            report?.takeIf { it.viewport == viewport }?.let { current ->
                if (current.confirmed && current.outputRevision != null) {
                    freeze!!.reportUnneeded(token)
                    requiredRevision = current.outputRevision
                } else bind(current)
            }
            drawInvalidation++
        }
        this.keyboard = keyboard; wasEnabled = true
        if (!moving && freeze != null && !legEnded) {
            freeze!!.transitionEnded(); legEnded = true; silenceEpoch++; drawInvalidation++
        }
    }

    private fun bind(current: Report) {
        freeze?.reportPublished(current.id)
        if (current.confirmed) freeze?.reportConfirmed(current.id)
        requiredRevision = null
    }

    fun reportPublished(id: Long, viewport: TerminalViewport) {
        val current = Report(id, viewport); report = current
        if (target == viewport) bind(current)
    }

    fun reportConfirmed(id: Long) {
        report?.takeIf { it.id == id }?.let { current ->
            current.confirmed = true
            if (current.viewport == target) {
                freeze?.reportConfirmed(id)
                if (legEnded) silenceEpoch++
            }
        }
    }

    fun outputApplied(id: Long, revision: Int) {
        report?.takeIf { it.id == id && it.confirmed }?.let { current ->
            current.outputRevision = revision
            if (current.viewport == target) {
                requiredRevision = revision
                freeze?.outputApplied(token)
                if (legEnded) silenceEpoch++
            }
        }
    }

    fun reportFailed(id: Long) { if (report?.id == id) cancel() }

    /** Called only after recording this revision's live Canvas commands. */
    fun present(revision: Int): Boolean {
        token++
        val active = freeze ?: return true
        if (requiredRevision?.let { revision >= it } == true && active.canPresent(token)) {
            cancel(); return true
        }
        return false
    }

    fun painted(text: String) { visibleText = text }
    /** Five seconds of silence after the leg; valid ack/output restarts the wait. */
    fun expireSilence(epoch: Int) {
        if (epoch == silenceEpoch && waitingAfterTransition) cancel()
    }
    fun cancel() {
        if (freeze != null) { drawInvalidation++; silenceEpoch++ }
        legEnded = false
        freeze = null; target = null; requiredRevision = null; frozen = false
    }
}
