package io.github.docmorphic.cmuxapp

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.text.PrecomputedTextCompat
import androidx.core.widget.TextViewCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private fun terminalSnapshotTextView(context: android.content.Context) = TextView(context).apply {
    textSize = 14f
    typeface = Typeface.MONOSPACE
    breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
    hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
    if (android.os.Build.VERSION.SDK_INT >= 35) setUseBoundsForWidth(false)
    setTextColor(android.graphics.Color.rgb(224, 229, 235))
    val padding = (16 * resources.displayMetrics.density).toInt()
    setPadding(padding, padding, padding, padding)
    setTextIsSelectable(true)
    tag = "terminal-text-snapshot"
}

/** Loading belongs to this presentation, so dismissed/retired reads cannot reopen it. */
@Composable
internal fun TerminalTextSheet(source: TerminalTextSource, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val metrics = remember(context, density) { TextViewCompat.getTextMetricsParams(terminalSnapshotTextView(context)) }
    var prepared by remember(source) { mutableStateOf<PrecomputedTextCompat?>(null) }
    var snapshot by remember(source) { mutableStateOf<TerminalTextSnapshot?>(null) }
    var failure by remember(source) { mutableStateOf<String?>(null) }
    var attempt by remember(source) { mutableIntStateOf(0) }
    LaunchedEffect(source, attempt, metrics) {
        failure = null; snapshot = null; prepared = null
        try {
            check(source.current())
            val captured = source.read()
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                PrecomputedTextCompat.create(captured.text, metrics)
            }
            check(source.current())
            prepared = text
            snapshot = captured
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            failure = if (source.current()) "Could not read terminal text. Try again."
                else "This terminal changed. Close this sheet and open its text again."
        }
    }
    TerminalTextSheetContent(snapshot, failure, { attempt++ }, source.current(), onDismiss, prepared)
}

/** iOS View as Text workflow, with Android's native selection handles and Copy menu. */
@Composable
fun TerminalTextSheet(snapshot: TerminalTextSnapshot, onDismiss: () -> Unit) {
    TerminalTextSheetContent(snapshot, null, {}, false, onDismiss)
}

@Composable
private fun TerminalTextSheetContent(snapshot: TerminalTextSnapshot?, failure: String?,
    retry: () -> Unit, canRetry: Boolean, onDismiss: () -> Unit, prepared: PrecomputedTextCompat? = null) {
    val context = LocalContext.current
    val haptics = rememberNativeHaptics()
    var copied by remember(snapshot) { mutableStateOf(false) }
    var copyError by remember(snapshot) { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF111316), contentColor = Color(0xFFE0E5EB)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Done") }
                    Text("Terminal Text", Modifier.weight(1f))
                    TextButton(enabled = snapshot?.text?.isNotEmpty() == true, onClick = {
                        try {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("Terminal text", checkNotNull(snapshot).text))
                            copied = true; copyError = null
                            haptics.perform(NativeHaptic.SUCCESS)
                        } catch (_: RuntimeException) {
                            copyError = "Could not copy all text. Select a smaller section and copy it."
                        }
                    }) { Text(if (copied) "Copied" else "Copy All") }
                }
                HorizontalDivider()
                copyError?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                if (snapshot == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (failure == null) CircularProgressIndicator()
                        else Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(failure)
                            if (canRetry) TextButton(retry) { Text("Retry") }
                        }
                    }
                } else {
                    if (snapshot.truncated) Text("Showing the last ${snapshot.lineBudget} lines.",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color(0xFF9B9FA8))
                    if (snapshot.text.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No terminal text available", color = Color(0xFF9B9FA8))
                    } else AndroidView(factory = { viewContext ->
                        ScrollView(viewContext).apply {
                            isFillViewport = true
                            addView(terminalSnapshotTextView(viewContext).apply {
                                if (prepared != null) TextViewCompat.setPrecomputedText(this, prepared)
                                else text = snapshot.text
                                NativeViewHaptics(this)
                            })
                        }
                    }, modifier = Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}
