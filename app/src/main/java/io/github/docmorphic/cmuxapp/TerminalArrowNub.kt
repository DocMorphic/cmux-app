package io.github.docmorphic.cmuxapp

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun TerminalArrowNub(owner: Any?, enabled: Boolean, onArrow: (TerminalToolbarButton) -> Unit) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptics = rememberNativeHaptics()
    val currentEnabled by rememberUpdatedState(enabled)
    val currentArrow by rememberUpdatedState(onArrow)
    val currentHaptics by rememberUpdatedState(haptics)
    val density = LocalDensity.current.density
    var offset by remember(owner, lifecycle) { mutableStateOf(Offset.Zero) }
    var gestureEpoch by remember(owner, lifecycle) { mutableIntStateOf(0) }
    val repeat = remember(owner, lifecycle) {
        TerminalArrowRepeat(scope, { currentEnabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }) {
            currentHaptics.perform(NativeHaptic.LIGHT)
            currentArrow(it.button)
        }
    }
    DisposableEffect(repeat, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                repeat.stop(); offset = Offset.Zero; gestureEpoch++
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); repeat.close() }
    }
    // Disable immediately at commit, rather than waiting for another repeat deadline.
    SideEffect { if (!enabled) { repeat.stop(); offset = Offset.Zero } }
    val shownOffset by animateOffsetAsState(offset, if (offset == Offset.Zero) tween(150) else snap(), label = "Arrow pad recenter")
    val foreground = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) .9f else .38f)
    Canvas(Modifier.size(48.dp)
        // The pinned pad sits in Android's Back edge. Exclude only this active hit area.
        .then(if (enabled) Modifier.systemGestureExclusion() else Modifier)
        .testTag("terminal-arrow-nub").semantics {
        contentDescription = "Terminal arrow pad"
        stateDescription = "Drag to move the cursor; hold to repeat"
        if (!enabled) disabled()
        customActions = TerminalArrowDirection.entries.map { direction ->
            CustomAccessibilityAction(direction.button.description) {
                if (!currentEnabled || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) false
                else {
                    repeat.stop()
                    currentHaptics.perform(NativeHaptic.LIGHT)
                    currentArrow(direction.button)
                    true
                }
            }
        }
    }.pointerInput(repeat, enabled, gestureEpoch, density) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown()
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@awaitEachGesture
            down.consume()
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                    val drag = (change.position - down.position) / density
                    offset = Offset(drag.x.coerceIn(-6f, 6f), drag.y.coerceIn(-6f, 6f))
                    repeat.move(TerminalArrowDirection.fromDrag(drag.x, drag.y))
                    change.consume()
                }
            } finally { repeat.stop(); offset = Offset.Zero }
        }
    }) {
        // Match the iOS 28-point circle/12-point dot; retain Android's 48dp touch area.
        drawCircle(foreground.copy(alpha = if (enabled) .16f else .08f), 14.dp.toPx())
        drawCircle(foreground, 6.dp.toPx(), center + Offset(shownOffset.x.dp.toPx(), shownOffset.y.dp.toPx()))
    }
}
