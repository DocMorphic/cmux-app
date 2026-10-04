package io.github.docmorphic.cmuxapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal val LocalDebugTerminalText = staticCompositionLocalOf<() -> String?> { { null } }
internal val LocalDebugLogSource = staticCompositionLocalOf<(suspend () -> String)?> { null }

internal suspend fun debugLogSnapshot(context: Context): String {
    MobileDiagnostics.recorder?.clearCutoff()?.let(MobileDebugLog::clearThrough)
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return "cmux Android debug log · ${context.packageName} · ${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})\n" +
        "Installed update: ${java.time.Instant.ofEpochMilli(info.lastUpdateTime)} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
        "Process ${android.os.Process.myPid()} · ${MobileDebugLog.snapshot()}"
}

/** Text is read on explicit copy only and never admitted into the diagnostic ring. */
@Composable
internal fun DebugLogMenuItem(onDismiss: () -> Unit) {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    val haptics = rememberNativeHaptics()
    val text = LocalDebugTerminalText.current
    val source = LocalDebugLogSource.current
    val scope = rememberCoroutineScope()
    var copying by remember { mutableStateOf(false) }
    DropdownMenuItem(text = { Text(if (copying) "Copying Debug Logs…" else "Copy Debug Logs") }, leadingIcon = { PaneMenuIcon(R.drawable.ic_menu_clipboard) }, enabled = !copying,
        modifier = Modifier.testTag("copy-debug-logs"), onClick = {
            if (!copying) {
                copying = true
                scope.launch(Dispatchers.Main.immediate) {
                    try {
                        val captured = text().orEmpty()
                        val visible = captured.take(32_000)
                        val logs = source?.invoke() ?: debugLogSnapshot(context)
                        val clipped = if (captured.length > visible.length) "\n[Visible text truncated at 32,000 characters]" else ""
                        val result = (if (visible.isEmpty()) "" else "Visible terminal\n$visible$clipped\n\n") + logs
                        val clip = ClipData.newPlainText("cmux debug logs", result)
                        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                        Toast.makeText(context, "Debug logs copied", Toast.LENGTH_SHORT).show()
                        haptics.perform(NativeHaptic.SUCCESS)
                        onDismiss()
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        Toast.makeText(context, "Could not copy debug logs. Try again.", Toast.LENGTH_SHORT).show()
                    } finally { copying = false }
                }
            }
        })
}
