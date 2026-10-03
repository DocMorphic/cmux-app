package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Separate composition boundary for the size sheet, bounds and detached state. */
@Composable
internal fun TerminalSizingSurfaceView(snapshot: NativeTerminalSizingSession.Snapshot?, surface: String,
    display: TerminalDisplay, cells: TerminalCellMetrics, geometry: TerminalGeometry?, viewport: TerminalViewport?,
    confirmed: Boolean, ready: Boolean, zoomVisible: Boolean, participantId: String?, showSheet: Boolean,
    onSheet: (Boolean) -> Unit, onAction: suspend (TerminalSizingAction) -> Unit,
    onReattach: suspend (Boolean) -> Unit) {
    val state = snapshot?.state
    if (snapshot?.allowsTraffic != false && state != null) {
        val presentation = TerminalSizingPresentation(state, snapshot.selfId ?: participantId)
        if (display.columns > 0 && display.rows > 0 && !zoomVisible &&
            TerminalSizingChrome.settled(state, presentation.selfId, viewport, confirmed,
                SharedTerminalGrid(display.columns, display.rows), ready && !snapshot.reconnecting))
            TerminalSizingOverlay(presentation, display, cells, geometry) { onSheet(true) }
        if (showSheet) TerminalSizeSheet(presentation, enabled = ready,
            onDismiss = { onSheet(false) }, change = onAction)
    }
    snapshot?.detached?.let { detached ->
        var reattaching by remember(surface, detached) { mutableStateOf(false) }
        var failure by remember(surface, detached) { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        fun reattach(viewer: Boolean) {
            if (reattaching || !ready) return
            reattaching = true; failure = null
            scope.launch {
                try { onReattach(viewer) }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failure = error.message ?: "Could not reattach terminal"
                } finally { reattaching = false }
            }
        }
        Column(Modifier.fillMaxSize().background(Color(0xFF191B1F)).padding(24.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Terminal disconnected", color = Color.White, fontSize = 20.sp)
            Text(listOfNotNull(detached.byName, detached.byDevice, detached.at).joinToString(" · ")
                .ifBlank { "Reattach to continue using this terminal." }, color = Color(0xFF9B9FA8))
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = ready && !reattaching, onClick = { reattach(false) }) { Text("Reattach") }
            TextButton(enabled = ready && !reattaching, onClick = { reattach(true) }) { Text("Reattach as viewer") }
        }
    }
}
