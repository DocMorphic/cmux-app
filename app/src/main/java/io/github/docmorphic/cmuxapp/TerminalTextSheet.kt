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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** iOS View as Text workflow, with Android's native selection handles and Copy menu. */
@Composable
fun TerminalTextSheet(snapshot: TerminalTextSnapshot, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberNativeHaptics()
    var copied by remember(snapshot) { mutableStateOf(false) }
    var copyError by remember(snapshot) { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF111316)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Done") }
                    Text("Terminal Text", Modifier.weight(1f))
                    TextButton(enabled = snapshot.text.isNotEmpty(), onClick = {
                        try {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("Terminal text", snapshot.text))
                            copied = true; copyError = null
                            haptics.perform(NativeHaptic.SUCCESS)
                        } catch (_: RuntimeException) {
                            copyError = "Could not copy all text. Select a smaller section and copy it."
                        }
                    }) { Text(if (copied) "Copied" else "Copy All") }
                }
                HorizontalDivider()
                copyError?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                if (snapshot.truncated) Text("Showing the last ${snapshot.lineBudget} lines.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color(0xFF9B9FA8))
                if (snapshot.text.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No terminal text available", color = Color(0xFF9B9FA8))
                } else AndroidView(factory = { viewContext ->
                    ScrollView(viewContext).apply {
                        isFillViewport = true
                        addView(TextView(viewContext).apply {
                            text = snapshot.text
                            textSize = 14f
                            typeface = Typeface.MONOSPACE
                            setTextColor(android.graphics.Color.rgb(224, 229, 235))
                            val padding = (16 * resources.displayMetrics.density).toInt()
                            setPadding(padding, padding, padding, padding)
                            setTextIsSelectable(true)
                            tag = "terminal-text-snapshot"
                            NativeViewHaptics(this)
                        })
                    }
                }, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
