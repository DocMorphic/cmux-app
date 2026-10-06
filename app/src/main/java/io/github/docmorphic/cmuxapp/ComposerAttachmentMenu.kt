package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Available sources follow this composer's transport; merely opening the menu reads no clipboard. */
@Composable
internal fun ComposerAttachmentMenu(enabled: Boolean, onPhotos: () -> Unit, onPaste: () -> Unit,
    modifier: Modifier = Modifier, onFiles: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        ComposerIconButton(onClick = { expanded = true }, enabled = enabled,
            modifier = modifier.semantics { contentDescription = "Add attachment" }) {
            Icon(painterResource(R.drawable.ic_composer_attachment), null, Modifier.size(20.dp))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Photos") }, onClick = { expanded = false; onPhotos() })
            if (onFiles != null) DropdownMenuItem(text = { Text("Files") }, onClick = { expanded = false; onFiles() })
            DropdownMenuItem(text = { Text("Paste attachment") }, onClick = { expanded = false; onPaste() })
        }
    }
}
