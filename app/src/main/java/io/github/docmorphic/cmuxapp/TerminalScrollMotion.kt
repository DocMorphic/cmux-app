package io.github.docmorphic.cmuxapp

import android.view.ViewConfiguration
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Whole wheel rows with a separate fractional carry, matching the iOS line path. */
internal class TerminalScrollRemainder {
    private var pixels = 0f
    fun reset() { pixels = 0f }
    fun take(delta: Float, cellHeight: Float): Int {
        if (!delta.isFinite() || !cellHeight.isFinite() || cellHeight <= 0) { reset(); return 0 }
        pixels += delta
        val rows = (pixels / cellHeight).toInt()
        pixels -= rows * cellHeight
        return rows
    }
}

/** Main-thread motion owner. Input, a new touch or a replaced surface stops it immediately. */
internal class TerminalScrollMotion(private val scope: CoroutineScope, private val decay: DecayAnimationSpec<Float>,
    private val minVelocity: Float, private val maxVelocity: Float) {
    private var job: Job? = null
    private var generation = 0L
    private val remainder = TerminalScrollRemainder()
    fun stop() { generation++; job?.cancel(); job = null; remainder.reset() }
    fun move(pixels: Float, height: Float, cell: TerminalGeometry.Cell, linePath: Boolean = true,
        send: (Double, TerminalGeometry.Cell) -> Boolean): Boolean {
        if (!pixels.isFinite() || !height.isFinite() || height <= 0) { stop(); return false }
        val rows = if (linePath) remainder.take(pixels, height).toDouble() else pixels.toDouble() / height
        if (rows == 0.0) return true
        if (send(rows, cell)) return true
        stop(); return false
    }
    fun fling(velocity: Float, height: Float, cell: TerminalGeometry.Cell, linePath: Boolean,
        send: (Double, TerminalGeometry.Cell) -> Boolean) {
        if (!velocity.isFinite() || abs(velocity) < minVelocity) { remainder.reset(); return }
        val token = generation
        job?.cancel()
        job = scope.launch {
            var previous = 0f
            var firstFrame: Long? = null
            AnimationState(0f, velocity.coerceIn(-maxVelocity, maxVelocity)).animateDecay(decay) {
                val first = firstFrame ?: lastFrameTimeNanos.also { firstFrame = it }
                if (generation != token || (linePath && lastFrameTimeNanos - first >= LINE_PATH_BUDGET_NANOS)) {
                    cancelAnimation()
                } else {
                    val delta = value - previous; previous = value
                    if (!move(delta, height, cell, linePath, send)) cancelAnimation()
                }
            }
            if (generation == token) remainder.reset()
        }
    }
    companion object { const val LINE_PATH_BUDGET_NANOS = 450_000_000L }
}

@Composable
internal fun rememberTerminalScrollMotion(target: Any?, connection: Any?): TerminalScrollMotion {
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val configuration = ViewConfiguration.get(LocalContext.current)
    val motion = remember(target, connection, decay) { TerminalScrollMotion(scope, decay,
        configuration.scaledMinimumFlingVelocity.toFloat(), configuration.scaledMaximumFlingVelocity.toFloat()) }
    DisposableEffect(motion) { onDispose { motion.stop() } }
    return motion
}

/** Geometry/mode changes cancel a gesture; ordinary output frames do not restart it. */
@Composable
internal fun Modifier.terminalScrollGestures(motion: TerminalScrollMotion, geometry: TerminalGeometry?,
    surfaceGeneration: Int, activeScreen: String, linePath: Boolean, enabled: Boolean,
    onScroll: (Double, TerminalGeometry.Cell) -> Boolean): Modifier {
    val latestSend by rememberUpdatedState(onScroll)
    val tracker = remember(motion) { VelocityTracker() }
    var multiTouch by remember(motion) { mutableStateOf(false) }
    var cell by remember(motion) { mutableStateOf(TerminalGeometry.Cell(0, 0)) }
    DisposableEffect(motion, geometry, surfaceGeneration, activeScreen, linePath, enabled) { onDispose { motion.stop() } }
    return this.pointerInput(motion, geometry, surfaceGeneration, activeScreen, linePath, enabled) {
        // Observe down/up before the drag detector: touch-down stops momentum even
        // when it becomes a tap, and a held finger's up event clears stale velocity.
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
                    geometry?.let { cell = it.cell(change.position.x, change.position.y) }
                }
                if (event.changes.none { it.pressed }) primary = null
            }
        }
    }.pointerInput(motion, geometry, surfaceGeneration, activeScreen, linePath, enabled) {
        try {
            detectVerticalDragGestures(onDragStart = { motion.stop() }, onDragCancel = { motion.stop() },
                onDragEnd = {
                    if (enabled && !multiTouch && geometry != null) motion.fling(tracker.calculateVelocity().y,
                        geometry.cellHeight, cell, linePath) { rows, at -> latestSend(rows, at) }
                }, onVerticalDrag = { change, pixels ->
                    if (enabled && !multiTouch && geometry != null) {
                        motion.move(pixels, geometry.cellHeight, geometry.cell(change.position.x, change.position.y), linePath) { rows, at -> latestSend(rows, at) }
                        change.consume()
                    }
                })
        } finally { motion.stop() }
    }
}
