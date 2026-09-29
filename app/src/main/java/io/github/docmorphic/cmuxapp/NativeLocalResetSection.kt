package io.github.docmorphic.cmuxapp

import android.app.ActivityManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Matches iOS's local reset scope. Android owns process shutdown and data removal. */
@Composable
internal fun NativeLocalResetSection(requestErase: (() -> Boolean)? = null) {
    val context = LocalContext.current.applicationContext
    // A confirmation is intentionally lost on recreation: erasure always needs a fresh tap.
    var confirming by remember { mutableStateOf(false) }
    var erasing by rememberSaveable { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val destructive = Color(0xFFFF9999)
    val muted = Color(0xFF92979F)
    Column(Modifier.padding(bottom = 24.dp)) {
        Text("RESET", Modifier.padding(horizontal = 22.dp, vertical = 10.dp), color = muted, fontSize = 11.sp)
        TextButton(onClick = { failed = false; confirming = true }, enabled = !erasing,
            modifier = Modifier.padding(horizontal = 14.dp)) {
            Text(if (erasing) "Erasing local data…" else "Erase All Data on This Device", color = destructive)
        }
        Text("Signs out and removes this app’s saved computers, keys, drafts, settings and caches. " +
            "Your cmux account and data on your computers remain unchanged.",
            Modifier.padding(horizontal = 22.dp), color = muted, fontSize = 12.sp)
        if (failed) Text("Android could not start the reset. Try again or clear this app’s storage in Android Settings.",
            Modifier.padding(horizontal = 22.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = destructive, fontSize = 13.sp)
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Erase all cmux data on this device?") },
        text = { Text("You will be signed out and all data stored by this app will be erased. " +
            "Android will close the app and reset its permissions. This can’t be undone. " +
            "Your cmux account and data on your computers will remain unchanged.") },
        confirmButton = { TextButton(onClick = {
            if (!erasing) {
                confirming = false
                erasing = true
                // Do not manually clear stores while live workers can repopulate them.
                // This platform operation stops the app and clears private/internal and
                // app-specific external data, notifications, runtime permissions and grants.
                val accepted = try {
                    requestErase?.invoke() ?: context.getSystemService(ActivityManager::class.java).clearApplicationUserData()
                } catch (_: Exception) { false }
                if (!accepted) { erasing = false; failed = true }
            }
        }) { Text("Erase", color = destructive) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } }
    )
}
