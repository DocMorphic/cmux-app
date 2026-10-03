package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A separate generated composition method for the terminal branch of the main screen. */
@Composable
internal fun ColumnScope.NativeTerminalContent(content: @Composable ColumnScope.() -> Unit) {
    content()
}

@Composable
internal fun NativeTerminalTabs(terminals: List<NativeTerminal>, selected: NativeTerminal, onSelect: (NativeTerminal) -> Unit) {
    if (terminals.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        terminals.forEach { item -> TextButton(onClick = { onSelect(item) }) {
            Text(item.title.ifBlank { "Terminal" }, color = if (item.id == selected.id) Color(0xFF76B9FF) else Color(0xFF9B9FA8),
                fontSize = 12.sp, maxLines = 1)
        } }
    }
}

@Composable
internal fun NativeTerminalHeader(terminal: NativeTerminal, workspace: NativeWorkspace?, workspaceCount: Int,
    capabilities: Set<String>, ready: Boolean, directTyping: Boolean,
    onBack: () -> Unit, onSurface: (NativeSurface) -> Unit, onText: () -> Unit, onFiles: () -> Unit,
    onNewBrowser: () -> Unit, onKeyboard: () -> Unit, onBrowser: (NativeBrowser) -> Unit,
    onTerminal: (NativeTerminal) -> Unit,
    onNewWorkspace: (() -> Unit)? = null, onNewTerminal: (() -> Unit)? = null,
    onSizing: (() -> Unit)? = null, debugText: () -> String? = { null }) {
    val accent = Color(0xFF76B9FF)
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back to workspaces" }) {
            Text("‹  $workspaceCount", color = accent)
        }
        CompositionLocalProvider(LocalDebugTerminalText provides debugText) {
        NativePanePicker(terminal.title.ifBlank { workspace?.title ?: "Terminal" }, workspace,
            NativeWorkspacePane(terminal = terminal), Modifier.weight(1f), onTerminal, onSurface, onBrowser,
            onNewWorkspace, onNewTerminal, onNewBrowser.takeIf { workspace != null },
            browserState = NativeBrowserPickerState.from(ready, capabilities)) { close ->
            DropdownMenuItem(text = { Text("View as Text") }, onClick = { close(); onText() }, enabled = terminal.isReady)
            if (onSizing != null) DropdownMenuItem(text = { Text("Terminal size") }, onClick = { close(); onSizing() })
            if ("terminal.artifact.v1" in capabilities) DropdownMenuItem(text = { Text("Files") },
                onClick = { close(); onFiles() }, enabled = ready)
        }
        }
        TextButton(onClick = onKeyboard, enabled = terminal.isReady) { Text(if (directTyping) "Compose" else "Keyboard", color = if (terminal.isReady) accent else Color(0xFF666A72), fontSize = 12.sp) }
    }
}
