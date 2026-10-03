package io.github.docmorphic.cmuxapp

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File

@Composable
internal fun NativeDiagnosticsSettings(recorder: DiagnosticRecorder? = MobileDiagnostics.recorder, share: ((File) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var verbose by remember(recorder) { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val storageFailed = recorder?.storageFailed?.collectAsState()?.value == true
    LaunchedEffect(recorder) {
        if (recorder != null) try { verbose = recorder.verbose() }
        catch (failure: Exception) { currentCoroutineContext().ensureActive(); message = "Couldn’t read diagnostic settings. Check available storage and reopen Settings." }
    }
    DisposableEffect(recorder) {
        recorder?.record(DebugOperation.SETTINGS_OPEN, DebugOutcome.SUCCESS)
        onDispose { recorder?.record(DebugOperation.SETTINGS_CLOSE, DebugOutcome.SUCCESS) }
    }
    fun act(failureMessage: String, action: suspend () -> Unit) {
        if (busy || recorder == null) return
        busy = true; message = null
        scope.launch(Dispatchers.Main.immediate) {
            try { withTimeout(30_000) { action() } }
            catch (failure: Exception) { currentCoroutineContext().ensureActive(); message = failureMessage }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text("DIAGNOSTICS", style = MaterialTheme.typography.labelSmall)
        TextButton(enabled = recorder != null && !busy, modifier = Modifier.testTag("diagnostics.export"), onClick = {
            act("Couldn’t export logs. Check available storage and try again.") {
                val file = checkNotNull(recorder).export()
                try {
                    currentCoroutineContext().ensureActive()
                    if (share != null) share(file) else context.startActivity(Intent.createChooser(artifactShareIntent(context, file, "application/zip"), "Export Logs"))
                } catch (failure: Throwable) { file.delete(); throw failure }
            }
        }) { Text(if (busy) "Working…" else "Export Logs") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Verbose Connection Log", Modifier.weight(1f))
            Switch(verbose == true, enabled = verbose != null && !busy, modifier = Modifier.testTag("diagnostics.verbose")
                .semantics { contentDescription = "Verbose Connection Log" }, onCheckedChange = { enabled ->
                act("Couldn’t change diagnostic logging. Check available storage and try again.") {
                    checkNotNull(recorder).setVerbose(enabled); verbose = enabled
                }
            })
        }
        if (verbose == true) Text("Records detailed connection activity to a file on this device for troubleshooting. Terminal contents and credentials are never written.", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = recorder != null && !busy, modifier = Modifier.testTag("diagnostics.clear"), onClick = { confirming = true }) {
            Text("Clear Logs", color = MaterialTheme.colorScheme.error)
        }
        Text("Export includes app events and networking diagnostics. Terminal contents and credentials are never written.", style = MaterialTheme.typography.bodySmall)
        if (recorder == null || storageFailed) Text("Diagnostic storage is unavailable. Check available storage and try again.", color = MaterialTheme.colorScheme.error)
        message?.let { Text(it, Modifier.testTag("diagnostics.message").semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("Clear all diagnostic logs?") },
        text = { Text("This permanently removes the diagnostic logs and cached log exports stored on this device.") },
        confirmButton = { TextButton(modifier = Modifier.testTag("diagnostics.confirm-clear"), onClick = {
            confirming = false
            act("Couldn’t clear every log generation. Check available storage and try again.") {
                checkNotNull(recorder).clear(); message = "Logs cleared"
            }
        }) { Text("Clear Logs", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } })
}
