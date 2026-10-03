package io.github.docmorphic.cmuxapp

import androidx.compose.ui.unit.IntSize

/** Retain settled capacity across transient layout passes (iOS uses three quiet frames). */
internal class TerminalViewportGeometryFence {
    var committed: IntSize? = null
        private set
    private var candidate: IntSize? = null
    private var stableFrames = 0

    fun invalidateCandidate() { candidate = null; stableFrames = 0 }

    fun prepareTarget(size: IntSize) {
        if (!valid(size)) return
        committed = size
        invalidateCandidate()
    }

    fun snapshotForApply(size: IntSize): IntSize? {
        if (committed == null && valid(size)) committed = size
        return committed
    }

    fun sample(size: IntSize, transitionActive: Boolean): Boolean {
        if (transitionActive || !valid(size)) { invalidateCandidate(); return false }
        if (committed == size) { invalidateCandidate(); return true }
        if (candidate == size) stableFrames++ else { candidate = size; stableFrames = 1 }
        if (stableFrames < 3) return false
        prepareTarget(size)
        return true
    }

    private fun valid(size: IntSize) = size.width > 0 && size.height > 0
}
