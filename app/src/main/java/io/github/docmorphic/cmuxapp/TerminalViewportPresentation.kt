package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize

/** Read the platform's announced target without installing a second insets callback. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberTerminalViewportReport(owner: Any?, surface: String?, measurement: TerminalViewportMeasurement,
    keepGrid: Boolean): IntSize {
    val density = LocalDensity.current
    val source = WindowInsets.imeAnimationSource.getBottom(density)
    val target = WindowInsets.imeAnimationTarget.getBottom(density)
    val navigation = WindowInsets.navigationBars.getBottom(density)
    return rememberTerminalViewportReport(owner, surface, measurement, keepGrid,
        (target - navigation).coerceAtLeast(0), source != target)
}

@Composable
internal fun rememberTerminalViewportReport(owner: Any?, surface: String?, measurement: TerminalViewportMeasurement,
    keepGrid: Boolean, targetKeyboard: Int, transitionActive: Boolean): IntSize {
    val fence = remember(owner, surface) { TerminalViewportGeometryFence() }
    var committed by remember(fence) { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(fence, measurement, keepGrid, targetKeyboard, transitionActive) {
        val desired = if (keepGrid) measurement.reportSize(true)
            else if (transitionActive) measurement.targetSize(targetKeyboard) else measurement.visible
        fence.snapshotForApply(desired)?.let { committed = it }
        if (!keepGrid && transitionActive) {
            // Every animation frame has the same predicted final size. A reversal
            // replaces that target immediately; never report the intermediate pane.
            fence.prepareTarget(desired)
            fence.committed?.let { committed = it }
        } else {
            fence.invalidateCandidate()
            repeat(3) {
                withFrameNanos { }
                if (fence.sample(desired, false)) {
                    fence.committed?.let { committed = it }
                    return@LaunchedEffect
                }
            }
        }
    }
    return committed
}
