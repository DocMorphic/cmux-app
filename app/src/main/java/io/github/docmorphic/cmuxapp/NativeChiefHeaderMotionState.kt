package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** UI-thread owner of admitted header transitions. The model sees every action,
 * even when Compose conflates several state changes before the next frame. */
internal class NativeChiefHeaderMotionState(initialReply: Long? = null) {
    private val motion = NativeChiefHeaderMotion(initialReply)
    var generation by mutableStateOf(initialReply)
        private set
    var pose by mutableStateOf(motion.frame(0.0))
        private set
    private var scale = 1f
    private var time = 0.0
    private var frameOrigin = 0.0
    private var awaitingFirstFrame = false

    fun admit(next: Long?) {
        if (generation == next) return
        motion.transition(next, time, scale)
        generation = next
        pose = motion.frame(time)
        // A long idle interval is not elapsed animation time. Rebase the first
        // display frame without discarding any same-frame input transitions.
        awaitingFirstFrame = true
    }

    fun durationScale(value: Float) {
        if (scale == value) return
        scale = value
        motion.transition(generation, time, value)
        pose = motion.frame(time)
    }

    fun frame(nanos: Long) {
        val now = nanos / 1e6
        if (awaitingFirstFrame) { frameOrigin = now - time; awaitingFirstFrame = false }
        time = (now - frameOrigin).coerceAtLeast(time)
        pose = motion.frame(time)
    }
}
