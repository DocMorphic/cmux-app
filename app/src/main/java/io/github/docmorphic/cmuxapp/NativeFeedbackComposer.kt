package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

internal val LocalNativeFeedback = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun NativeFeedbackHost(owner: Any?, email: String? = null,
    submit: (suspend (String, String, NativeFeedbackStamp) -> Unit)? = null, content: @Composable () -> Unit) {
    val client = remember { NativeFeedbackClient() }
    val context = LocalContext.current
    val stamp = remember {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        NativeFeedbackStamp(info.versionName.orEmpty(), androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info).toString(),
            context.packageName, if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) "dev" else "prod",
            "Android ${Build.VERSION.RELEASE}", Build.MODEL, Locale.getDefault().toLanguageTag())
    }
    var composer by remember(owner) { mutableStateOf<String?>(null) }
    val snackbar = remember(owner) { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    CompositionLocalProvider(LocalNativeFeedback provides { keyboard?.hide(); composer = UUID.randomUUID().toString() }) {
        Box(Modifier.fillMaxSize()) {
            content()
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
    composer?.let { id -> key(owner, id) {
        NativeFeedbackComposer(email.orEmpty(), stamp, submit ?: client::submit,
            onCancel = { if (composer == id) composer = null }, onSent = {
                if (composer == id) { composer = null; scope.launch { snackbar.showSnackbar("Feedback sent") } }
            })
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeFeedbackComposer(initialEmail: String, stamp: NativeFeedbackStamp,
    submit: suspend (String, String, NativeFeedbackStamp) -> Unit, onCancel: () -> Unit, onSent: () -> Unit) {
    var email by remember { mutableStateOf(initialEmail) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Text("Send Feedback", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(enabled = !busy && NativeFeedbackClient.valid(email, message), modifier = Modifier.testTag("feedback-send"), onClick = {
                    if (!busy && NativeFeedbackClient.valid(email, message)) {
                        busy = true; failure = null
                        val replyTo = email; val note = message
                        scope.launch {
                            try { submit(replyTo, note, stamp); onSent() }
                            catch (error: Exception) { if (error is CancellationException) throw error; failure = error.message ?: "Could not send feedback." }
                            finally { busy = false }
                        }
                    }
                }) { Text(if (busy) "Sending…" else "Send") }
            }
            Text("Emails your feedback to the cmux team with your app version and device. This build is the unofficial Android companion.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth().testTag("feedback-message"),
                label = { Text("What happened?") }, enabled = !busy, minLines = 3, maxLines = 8,
                isError = message.length > 4_000, supportingText = { Text("${message.length} / 4000") })
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth().testTag("feedback-email"),
                label = { Text("Your email") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            Text("${stamp.version} (${stamp.build}) · ${stamp.os} · ${stamp.model}", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 12.dp))
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("feedback-error").padding(bottom = 16.dp)) }
        }
    }
}

@Composable
internal fun NativeFeedbackSettingsButton() {
    LocalNativeFeedback.current?.let { open -> TextButton(onClick = open, modifier = Modifier.padding(horizontal = 14.dp)) { Text("Send Feedback") } }
}
