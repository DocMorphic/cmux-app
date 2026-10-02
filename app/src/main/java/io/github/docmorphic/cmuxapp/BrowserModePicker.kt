package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

internal enum class BrowserMode(val label: String) { STREAMED("Streamed"), ON_DEVICE("On Android") }

@Composable
internal fun BrowserModePicker(mode: BrowserMode, unavailable: String? = null, onSwitch: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "Browser mode" }) { Text("${mode.label} ▾") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            BrowserMode.entries.forEach { choice ->
                val selected = choice == mode
                DropdownMenuItem(text = { Text((if (selected) "✓  " else "") + choice.label) },
                    enabled = selected || unavailable == null,
                    onClick = { expanded = false; if (!selected) onSwitch() })
            }
            unavailable?.let { reason -> DropdownMenuItem(text = { Text(reason) }, enabled = false, onClick = {}) }
        }
    }
}
