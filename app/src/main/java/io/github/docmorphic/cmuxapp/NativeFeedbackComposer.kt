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
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import java.util.Locale

internal val LocalNativeFeedback = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun NativeFeedbackHost(owner: String?, email: String? = null,
    submit: (suspend (String, String, NativeFeedbackStamp) -> Unit)? = null, content: @Composable () -> Unit) {
    val client = remember { NativeFeedbackClient() }
    val context = LocalContext.current
    val stamp = remember {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        NativeFeedbackStamp(info.versionName.orEmpty(), androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info).toString(),
            context.packageName, if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) "dev" else "prod",
            "Android ${Build.VERSION.RELEASE}", Build.MODEL, Locale.getDefault().toLanguageTag())
    }
    val modelOwner = checkNotNull(LocalView.current.findViewTreeViewModelStoreOwner())
    val controller = remember(modelOwner) { ViewModelProvider(modelOwner)[NativeFeedbackViewModel::class.java].controller }
    val state by controller.state.collectAsState()
    val haptics = rememberNativeHaptics()
    SideEffect { controller.bind(owner) }
    // Distinct request ids survive StateFlow/frame conflation on immediate identical retries.
    LaunchedEffect(owner, state.completion, state.completionId) {
        when (controller.takeCompletion(owner)) {
            NativeFeedbackCompletion.SUCCESS -> haptics.perform(NativeHaptic.SUCCESS)
            NativeFeedbackCompletion.FAILURE -> haptics.perform(NativeHaptic.ERROR)
            null -> Unit
        }
    }
    val snackbar = remember(owner) { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    CompositionLocalProvider(LocalNativeFeedback provides { keyboard?.hide(); controller.open(owner, email.orEmpty()) }) {
        Box(Modifier.fillMaxSize()) {
            content()
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
    LaunchedEffect(owner, state.receipt) {
        if (state.owner == owner) state.receipt?.let { receipt ->
            snackbar.showSnackbar("Feedback sent"); controller.acknowledge(receipt)
        }
    }
    if (state.owner == owner) state.id?.let { id -> key(owner, id) {
        NativeFeedbackComposer(state, stamp,
            onEmail = { controller.edit(id, email = it) }, onMessage = { controller.edit(id, message = it) },
            onSend = { controller.send(id, stamp, submit ?: client::submit) }, onCancel = { controller.dismiss(id) })
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeFeedbackComposer(state: NativeFeedbackState, stamp: NativeFeedbackStamp,
    onEmail: (String) -> Unit, onMessage: (String) -> Unit, onSend: () -> Unit, onCancel: () -> Unit) {
    val email = state.email; val message = state.message; val busy = state.sending; val failure = state.error
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Text("Send Feedback", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(enabled = !busy && NativeFeedbackClient.valid(email, message), modifier = Modifier.testTag("feedback-send"), onClick = onSend) { Text(if (busy) "Sending…" else "Send") }
            }
            Text("Emails your feedback to the cmux team with your app version and device. This build is the unofficial Android companion.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            OutlinedTextField(message, onMessage, Modifier.fillMaxWidth().testTag("feedback-message"),
                label = { Text("What happened?") }, enabled = !busy, minLines = 3, maxLines = 8,
                isError = message.length > 4_000, supportingText = { Text("${message.length} / 4000") })
            OutlinedTextField(email, onEmail, Modifier.fillMaxWidth().testTag("feedback-email"),
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
