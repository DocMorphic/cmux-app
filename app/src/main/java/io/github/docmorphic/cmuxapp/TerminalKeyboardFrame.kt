package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import kotlinx.coroutines.delay

private class TerminalFrameLayers { var held = 0; var valid = false }

/** Two display lists: keep the last accepted frame while recording its replacement. */
@Composable
internal fun Modifier.terminalKeyboardFrame(presentation: TerminalKeyboardPresentation?, revision: Int,
    text: String): Modifier {
    if (presentation == null) return this
    val first = rememberGraphicsLayer()
    val second = rememberGraphicsLayer()
    val state = remember(presentation) { TerminalFrameLayers() }
    val silenceEpoch = presentation.silenceEpoch
    LaunchedEffect(presentation, silenceEpoch) {
        if (presentation.waitingAfterTransition) {
            delay(5_000)
            presentation.expireSilence(silenceEpoch)
        }
    }
    return drawWithContent {
        presentation.drawInvalidation
        val live = if (state.held == 0) second else first
        live.record { this@drawWithContent.drawContent() }
        if (!state.valid || presentation.present(revision)) {
            // A first mount has no old drawable to retain.
            if (!state.valid) presentation.cancel()
            drawLayer(live)
            state.held = 1 - state.held; state.valid = true
            presentation.painted(text)
        } else {
            val held = if (state.held == 0) first else second
            // iOS overlays the captured frame in the stationary clipping pane.
            // Clip at the moving dock; never move/reflow/stretch the held pixels.
            drawLayer(held)
        }
    }
}
