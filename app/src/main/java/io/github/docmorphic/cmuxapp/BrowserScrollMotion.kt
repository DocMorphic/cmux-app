package io.github.docmorphic.cmuxapp

import android.view.ViewConfiguration
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Preserve the native drag/momentum boundaries used by the iOS browser mirror. */
internal class BrowserScrollSequence(private val send: (BrowserInput.Scroll) -> Boolean, private val discard: () -> Boolean) {
    private enum class State { IDLE, DRAG, MOMENTUM }
    private var state = State.IDLE
    private var x = 0.0
    private var y = 0.0
    private fun emit(phase: String, dx: Double = 0.0, dy: Double = 0.0): Boolean {
        if (send(BrowserInput.Scroll(dx, dy, x, y, phase))) return true
        state = State.IDLE; return false
    }
    fun begin(x: Double, y: Double): Boolean {
        cancel(); this.x = x; this.y = y; state = State.DRAG
        return emit("began")
    }
    fun drag(dx: Double, dy: Double, x: Double, y: Double): Boolean {
        if (state != State.DRAG) return false
        this.x = x; this.y = y
        return emit("changed", dx, dy)
    }
    fun end(momentum: Boolean): Boolean {
        if (state != State.DRAG) return false
        state = State.IDLE
        if (!emit("ended") || !momentum) return false
        state = State.MOMENTUM
        return emit("momentum_began")
    }
    fun momentum(dx: Double, dy: Double): Boolean = state == State.MOMENTUM && emit("momentum_changed", dx, dy)
    fun finishMomentum() { if (state == State.MOMENTUM) { state = State.IDLE; emit("momentum_ended") } }
    fun cancel() {
        val active = state != State.IDLE
        state = State.IDLE
        val removed = discard()
        if (active || removed) emit("cancelled")
    }
}

/** Android spline decay in view pixels; every emitted delta is mapped to Mac page points. */
internal class BrowserScrollMotion(private val scope: CoroutineScope, private val decay: DecayAnimationSpec<Offset>,
    private val minimumVelocity: Float, private val maximumVelocity: Float, private val sequence: BrowserScrollSequence) {
    private var job: Job? = null
    private var generation = 0L
    fun stop() { generation++; job?.cancel(); job = null; sequence.cancel() }
    fun begin(point: Offset) { stop(); sequence.begin(point.x.toDouble(), point.y.toDouble()) }
    fun drag(delta: Offset, point: Offset) {
        if (!sequence.drag(delta.x.toDouble(), delta.y.toDouble(), point.x.toDouble(), point.y.toDouble())) stop()
    }
    fun end(velocity: Offset, pagePerPixel: Offset) {
        val fling = velocity.x.isFinite() && velocity.y.isFinite() && velocity.getDistance() >= minimumVelocity
        if (!sequence.end(fling)) return
        val epoch = generation
        job = scope.launch {
            var previous = Offset.Zero
            AnimationState(initialValue = Offset.Zero, typeConverter = Offset.VectorConverter,
                initialVelocityVector = AnimationVector2D(velocity.x.coerceIn(-maximumVelocity, maximumVelocity),
                    velocity.y.coerceIn(-maximumVelocity, maximumVelocity))).animateDecay(decay) {
                if (generation != epoch) cancelAnimation()
                else {
                    val delta = value - previous; previous = value
                    // iOS negates UIScrollView content-offset deltas. Touch displacement
                    // already has that sign, so Android must not negate it again.
                    if (delta != Offset.Zero && !sequence.momentum(delta.x.toDouble() * pagePerPixel.x,
                            delta.y.toDouble() * pagePerPixel.y)) cancelAnimation()
                }
            }
            if (generation == epoch) sequence.finishMomentum()
        }
    }
}

@Composable
internal fun rememberBrowserScrollMotion(queue: BrowserInputQueue): BrowserScrollMotion {
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Offset>()
    val config = ViewConfiguration.get(LocalContext.current)
    val motion = remember(queue, decay) {
        BrowserScrollMotion(scope, decay, config.scaledMinimumFlingVelocity.toFloat(), config.scaledMaximumFlingVelocity.toFloat(),
            BrowserScrollSequence(send = { queue.offer(it) }, discard = queue::discardPendingScroll))
    }
    DisposableEffect(motion, queue) {
        queue.onNonScrollInput = motion::stop
        onDispose { queue.onNonScrollInput = null; motion.stop() }
    }
    return motion
}

/** A new touch cancels momentum before deciding whether it is a tap or drag. */
@Composable
internal fun Modifier.browserScrollGestures(motion: BrowserScrollMotion, pageWidth: Double, pageHeight: Double,
    viewportSize: IntSize, generation: Long, enabled: Boolean): Modifier {
    val tracker = remember(motion) { VelocityTracker() }
    var multiTouch by remember(motion) { mutableStateOf(false) }
    DisposableEffect(motion, pageWidth, pageHeight, viewportSize, generation, enabled) { onDispose { motion.stop() } }
    return pointerInput(motion, pageWidth, pageHeight, viewportSize, generation, enabled) {
        awaitPointerEventScope {
            var primary: PointerId? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (primary == null) event.changes.firstOrNull { it.pressed }?.let {
                    primary = it.id; multiTouch = false; motion.stop(); tracker.resetTracking()
                }
                if (event.changes.count { it.pressed } > 1) { multiTouch = true; motion.stop() }
                event.changes.firstOrNull { it.id == primary }?.let { change ->
                    change.historical.forEach { tracker.addPosition(it.uptimeMillis, it.position) }
                    tracker.addPosition(change.uptimeMillis, change.position)
                }
                if (event.changes.none { it.pressed }) primary = null
            }
        }
    }.pointerInput(motion, pageWidth, pageHeight, viewportSize, generation, enabled) {
        if (!enabled) return@pointerInput
        fun scale() = Offset((pageWidth / size.width).toFloat(), (pageHeight / size.height).toFloat())
        fun point(position: Offset) = scale().let { Offset(position.x * it.x, position.y * it.y) }
        try {
            detectDragGestures(onDragStart = { if (enabled && !multiTouch) motion.begin(point(it)) },
                onDragCancel = { motion.stop() }, onDragEnd = {
                    if (enabled && !multiTouch) motion.end(tracker.calculateVelocity().let { Offset(it.x, it.y) }, scale())
                }) { change, delta ->
                if (enabled && !multiTouch) {
                    val factor = scale()
                    motion.drag(Offset(delta.x * factor.x, delta.y * factor.y), point(change.position))
                    change.consume()
                }
            }
        } finally { motion.stop() }
    }
}
