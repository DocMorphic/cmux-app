package io.github.docmorphic.cmuxapp

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Gesture owner around only the balloon. Quotes remain in the outer row, stationary.
 * The source's thread row travel/release-offset unwind remains separate motion work. */
@Composable
internal fun NativeChiefReplySwipe(messageID: String, outgoing: Boolean, enabled: Boolean,
    tailReserve: Dp, onReply: (Float) -> Unit, content: @Composable (Modifier) -> Unit) {
    val density = LocalDensity.current.density
    val reserve = with(LocalDensity.current) { tailReserve.toPx() }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val reply by rememberUpdatedState(onReply)
    var width by remember { mutableIntStateOf(0) }
    var body by remember { mutableStateOf(IntSize.Zero) }
    var offset by remember { mutableFloatStateOf(0f) }
    var progress by remember { mutableFloatStateOf(0f) }
    var arrowAlpha by remember { mutableFloatStateOf(0f) }
    var arrowScale by remember { mutableFloatStateOf(.4f) }
    var arrowDrift by remember { mutableFloatStateOf(0f) }
    var sharp by remember { mutableFloatStateOf(0f) }
    var blurred by remember { mutableFloatStateOf(0f) }
    var committed by remember { mutableStateOf(false) }
    var resetJob by remember { mutableStateOf<Job?>(null) }
    var pulseJob by remember { mutableStateOf<Job?>(null) }
    fun haptic() { view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP) }
    fun resetIndicator() { arrowAlpha = 0f; arrowScale = .4f; arrowDrift = 0f; progress = 0f; sharp = 0f; blurred = 0f }
    Box(Modifier.fillMaxWidth().testTag("ChiefReplySwipe:$messageID").onSizeChanged { width = it.width }
        .pointerInput(messageID, enabled, density, reserve) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                resetJob?.cancel(); pulseJob?.cancel(); offset = 0f; committed = false; resetIndicator()
                val tracker = VelocityTracker(); tracker.addPosition(down.uptimeMillis, down.position)
                var claimed = false
                var ended = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) { ended = true; if (claimed) change.consume(); break }
                        if (event.changes.count { it.pressed } != 1) break
                        if (!change.positionChanged()) continue
                        val travel = change.position - down.position
                        val measuredVelocity = tracker.calculateVelocity()
                        // Compose's fitted tracker can return zero with only two
                        // samples. UIPan has a direction at recognition already;
                        // use this event's measured movement until the fit exists.
                        val elapsed = change.uptimeMillis - change.previousUptimeMillis
                        val velocity = if (!claimed && measuredVelocity == Velocity.Zero && elapsed > 0) {
                            val step = (change.position - change.previousPosition) * (1000f / elapsed)
                            Velocity(step.x, step.y)
                        } else measuredVelocity
                        if (!claimed) {
                            if (travel.getDistance() < viewConfiguration.touchSlop) continue
                            val left = if (outgoing) width - body.width else 0
                            val box = NativeChiefReplySwipePolicy.Area(left / density.toDouble(), 0.0,
                                body.width / density.toDouble(), (body.height - reserve).coerceAtLeast(0f) / density.toDouble())
                            if (!NativeChiefReplySwipePolicy.canBegin(true, (travel.x / density).toDouble(),
                                    (velocity.x / density).toDouble(), (velocity.y / density).toDouble(),
                                    change.position.x / density.toDouble(), change.position.y / density.toDouble(), box, outgoing)) break
                            claimed = true
                        }
                        change.consume()
                        offset = NativeChiefReplySwipePolicy.bubbleOffset((travel.x / density).toDouble()).toFloat()
                        if (!committed) {
                            progress = NativeChiefReplySwipePolicy.progress(offset.toDouble()).toFloat()
                            arrowAlpha = if (progress > 0) 1f else 0f
                            arrowScale = NativeChiefReplySwipePolicy.scale(progress.toDouble()).toFloat()
                            arrowDrift = if (outgoing) -12f * progress else 0f
                            sharp = progress * progress; blurred = progress * (1 - progress)
                        }
                        when (NativeChiefReplySwipePolicy.crossing(offset.toDouble(), velocity.x.toDouble(), committed)) {
                            NativeChiefReplySwipePolicy.Crossing.COMMIT -> {
                                committed = true; sharp = 1f; blurred = 0f; arrowAlpha = 1f
                                val start = arrowScale
                                pulseJob?.cancel()
                                // Source's timer survives a quick release/reset of the arrow.
                                scope.launch { delay(75); haptic() }
                                pulseJob = scope.launch {
                                    animate(0f, 1f, animationSpec = tween(300, easing = CubicBezierEasing(.42f, 0f, .58f, 1f))) { t, _ ->
                                        arrowScale = if (t < .25f) start + (1.15f - start) * t / .25f else 1.15f - .15f * (t - .25f) / .75f
                                        arrowDrift = if (outgoing) -12f else 0f
                                    }
                                }
                            }
                            NativeChiefReplySwipePolicy.Crossing.UNCOMMIT -> { committed = false; haptic() }
                            NativeChiefReplySwipePolicy.Crossing.NONE -> Unit
                        }
                    }
                } finally {
                    val commit = claimed && committed && ended
                    val release = offset
                    if (commit) { pulseJob?.cancel(); resetIndicator(); reply(release) }
                    val fromScale = arrowScale; val fromAlpha = arrowAlpha
                    resetJob = scope.launch {
                        launch { animate(release, 0f, animationSpec = tween(250, easing = CubicBezierEasing(0f, 0f, .58f, 1f))) { value, _ -> offset = value } }
                        if (!commit) animate(0f, 1f, animationSpec = tween(400, easing = CubicBezierEasing(.42f, 0f, .58f, 1f))) { t, _ ->
                            arrowScale = fromScale + (.4f - fromScale) * t; arrowAlpha = fromAlpha * (1 - t)
                        }
                    }
                    committed = false
                }
            }
        }) {
        val left = if (outgoing) (width - body.width).toFloat() else 0f
        val centerX = left + 13f * density
        val centerY = body.height / 2f
        val indicatorModifier = Modifier.offset { IntOffset((centerX - 13f * density).roundToInt(), (centerY - 13f * density).roundToInt()) }
            .size(26.dp).graphicsLayer {
                scaleX = arrowScale; scaleY = arrowScale; translationX = arrowDrift * arrowScale * density; alpha = arrowAlpha
            }.clearAndSetSemantics {}
        Box(indicatorModifier) {
            val ink = if (androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFF636366) else Color(0xFFAEAEB2)
            Icon(painterResource(R.drawable.ic_chief_reply), null, Modifier.size(26.dp).blur(4.5.dp).graphicsLayer { alpha = blurred }, tint = ink)
            Icon(painterResource(R.drawable.ic_chief_reply), null, Modifier.size(26.dp).graphicsLayer { alpha = sharp }, tint = ink)
        }
        content(Modifier.align(if (outgoing) AbsoluteAlignment.TopRight else AbsoluteAlignment.TopLeft)
            .onSizeChanged { body = it }.graphicsLayer { translationX = offset * density })
    }
}
