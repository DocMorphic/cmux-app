package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    onSizing: (() -> Unit)? = null) {
    val accent = Color(0xFF76B9FF)
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back to workspaces" }) {
            Text("‹  $workspaceCount", color = accent)
        }
        var menu by remember(terminal.id) { mutableStateOf(false) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(terminal.title.ifBlank { workspace?.title ?: "Terminal" } + " ▾",
                Modifier.testTag("terminal-picker").semantics { contentDescription = "Choose terminal or pane" }
                    .clickable { menu = true }.background(Color(0xFF191B1F), RoundedCornerShape(18.dp))
                    .padding(horizontal = 15.dp, vertical = 7.dp), fontWeight = FontWeight.Medium,
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                val terminals = workspace?.terminals.orEmpty()
                if (terminals.isNotEmpty()) {
                    TerminalPickerSection("Terminals")
                    terminals.forEach { item -> DropdownMenuItem(text = { Text(item.title.ifBlank { "Terminal" }) },
                        modifier = Modifier.testTag("terminal-picker-terminal-${item.id}").semantics { selected = item.id == terminal.id },
                        trailingIcon = { if (item.id == terminal.id) Text("✓", Modifier.clearAndSetSemantics { }) },
                        onClick = { menu = false; onTerminal(item) }) }
                }
                val surfaces = workspace?.macSurfaces.orEmpty().filter { it.simulator == null }
                if (surfaces.isNotEmpty()) TerminalPickerSection("Mac Surfaces")
                surfaces.forEach { surface ->
                    DropdownMenuItem(text = { Text(surface.displayTitle) }, onClick = { menu = false; onSurface(surface) })
                }
                val simulators = workspace?.macSurfaces.orEmpty().filter { it.simulator != null }
                if (simulators.isNotEmpty()) TerminalPickerSection("Mac Simulators")
                simulators.forEach { surface ->
                    DropdownMenuItem(text = { Text(surface.displayTitle) }, onClick = { menu = false; onSurface(surface) })
                }
                val browsers = workspace?.browsers.orEmpty().filter { browser -> workspace?.simulators.orEmpty().none { it.panelId == browser.id } }
                if (browsers.isNotEmpty()) TerminalPickerSection("Mac Browsers")
                browsers.forEach { browser ->
                    DropdownMenuItem(text = { Text(browser.title.ifBlank { "Browser" }) }, onClick = { menu = false; onBrowser(browser) })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("New Workspace") }, enabled = onNewWorkspace != null,
                    onClick = { menu = false; onNewWorkspace?.invoke() })
                DropdownMenuItem(text = { Text("New Terminal") }, enabled = onNewTerminal != null,
                    onClick = { menu = false; onNewTerminal?.invoke() })
                DropdownMenuItem(text = { Text("New Browser") }, onClick = { menu = false; onNewBrowser() }, enabled = workspace != null)
                DropdownMenuItem(text = { Text("View as Text") }, onClick = { menu = false; onText() }, enabled = terminal.isReady)
                if (onSizing != null) DropdownMenuItem(text = { Text("Terminal size") }, onClick = { menu = false; onSizing() })
                if ("terminal.artifact.v1" in capabilities) DropdownMenuItem(text = { Text("Files") },
                    onClick = { menu = false; onFiles() }, enabled = ready)
            }
        }
        TextButton(onClick = onKeyboard, enabled = terminal.isReady) { Text(if (directTyping) "Compose" else "Keyboard", color = if (terminal.isReady) accent else Color(0xFF666A72), fontSize = 12.sp) }
    }
}

@Composable
private fun TerminalPickerSection(title: String) {
    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).semantics { heading() })
}
