package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

@Composable
internal fun NativeComputerForgetSection(name: String, buildTag: String, flow: NativeComputerForgetFlow,
    enabled: Boolean, onFinished: () -> Unit) {
    var confirm by remember(flow) { mutableStateOf(false) }
    val state by flow.state.collectAsState()
    val scope = rememberCoroutineScope()
    val currentFinished by rememberUpdatedState(onFinished)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        TextButton(onClick = { confirm = true }, enabled = (enabled || state.remoteConfirmed) && !state.busy) {
            Text(if (state.remoteConfirmed) "Finish forgetting this computer" else "Forget This Computer", color = Color(0xFFFF9999))
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { if (!state.busy) confirm = false },
        properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy),
        title = { Text(if (state.remoteConfirmed) "Finish removing $name?" else "Forget $name?") },
        text = { Column {
            Text(if (state.remoteConfirmed) "The server has already confirmed removal. Finish clearing the saved pairing on this phone."
                else "Removes this computer’s $buildTag registration from the selected team on all your devices. An online Mac may reappear when it registers again.")
            state.error?.let { Text(it, color = Color(0xFFFF9999), modifier = Modifier.padding(top = 12.dp)) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
        } },
        confirmButton = { TextButton(enabled = !state.busy, onClick = {
            scope.launch {
                if (flow.confirm()) { confirm = false; currentFinished() }
            }
        }) { Text(when {
            state.busy -> "Forgetting…"
            state.remoteConfirmed -> "Retry local cleanup"
            state.error != null -> "Retry"
            else -> "Forget Computer"
        }, color = Color(0xFFFF9999)) } },
        dismissButton = { TextButton(onClick = { confirm = false }, enabled = !state.busy) { Text("Cancel") } }
    )
}
