package io.github.docmorphic.cmuxapp

import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The pointer coroutine is keyed to the mounted view, never to changing font/viewport sizes. */
@Composable
internal fun Modifier.terminalPinchZoom(zoom: TerminalZoomState, sharedLayout: TerminalSharedGridLayout? = null,
    coordinateOffset: Offset = Offset.Zero,
    onSharedTransform: (TerminalGridTransform) -> Unit = {}): Modifier {
    val latestShared by rememberUpdatedState(sharedLayout)
    val latestTransform by rememberUpdatedState(onSharedTransform)
    val latestOffset by rememberUpdatedState(coordinateOffset)
    return pointerInput(zoom) {
        awaitPointerEventScope {
            var steps: TerminalPinchSteps? = null
            var pinching = false
            var sharedPinch = false
            var pinchLayout: TerminalSharedGridLayout? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val fingers = event.changes.count { it.pressed }
                if (fingers >= 2) {
                    if (!pinching) {
                        pinchLayout = latestShared?.takeIf { it.scaledMode }
                        sharedPinch = pinchLayout != null
                    }
                    pinching = true
                    if (sharedPinch) {
                        val layout = pinchLayout
                        val latest = latestShared
                        // A layout replacement during a gesture cancels display zoom;
                        // it must not fall through into a font/PTY resize mid-pinch.
                        if (layout != null && latest != null && layout.width == latest.width && layout.height == latest.height &&
                            layout.columns == latest.columns && layout.rows == latest.rows && layout.cells == latest.cells) {
                            val moved = layout.panned(event.calculatePan())
                            pinchLayout = moved.zoomed(layout.transform.magnification * event.calculateZoom(), event.calculateCentroid() + latestOffset)
                            latestTransform(pinchLayout!!.transform)
                        } else pinchLayout = null
                    } else {
                        val current = steps ?: TerminalPinchSteps().also { steps = it }
                        current.update(event.calculateZoom(), zoom::step)
                    }
                }
                // Suppress taps/scroll until both fingers lift, including the final up event.
                if (pinching) event.changes.forEach { it.consume() }
                if (fingers == 0) { steps = null; pinching = false; sharedPinch = false; pinchLayout = null }
            }
        }
    }
}

@Composable
internal fun TerminalZoomOverlay(zoom: TerminalZoomState, preferences: SharedPreferences,
    foreground: Color, background: Color, modifier: Modifier = Modifier) {
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(zoom, zoom.interaction, zoom.overlayVisible, accessibility) {
        if (zoom.overlayVisible) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(2500L, containsText = true, containsControls = true) ?: 2500L)
            zoom.hide()
        }
    }
    AnimatedVisibility(zoom.overlayVisible, modifier, enter = fadeIn(tween(180)), exit = fadeOut(tween(300))) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${zoom.size.roundToInt()} pt", color = foreground, fontSize = 22.sp,
                modifier = Modifier.testTag("terminal-zoom-size").background(background.copy(alpha = .92f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 18.dp, vertical = 9.dp))
            ZoomAction("Reset to default", foreground, background) {
                val saved = (preferences.all[TerminalFontSize.SAVED_KEY] as? Float)?.takeIf { it > 0 }?.let(TerminalFontSize::clamp)
                zoom.reset(saved)
            }
            ZoomAction("Set as default", foreground, background) {
                preferences.edit().putFloat(TerminalFontSize.SAVED_KEY, zoom.size).apply(); zoom.show()
            }
            ZoomAction("Restore built-in", foreground, background) {
                preferences.edit().remove(TerminalFontSize.SAVED_KEY).apply(); zoom.reset(null)
            }
        }
    }
}

@Composable
private fun ZoomAction(title: String, foreground: Color, background: Color, action: () -> Unit) {
    TextButton(onClick = action, colors = ButtonDefaults.textButtonColors(contentColor = foreground,
        containerColor = background.copy(alpha = .92f)), modifier = Modifier.semantics { contentDescription = title }) {
        Text(title, fontSize = 13.sp)
    }
}
