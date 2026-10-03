package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared live inventory for terminal, streamed browser and Mac surface headers. */
@Composable
internal fun NativePanePicker(title: String, workspace: NativeWorkspace?, selection: NativeWorkspacePane,
    modifier: Modifier = Modifier, onTerminal: (NativeTerminal) -> Unit, onSurface: (NativeSurface) -> Unit,
    onBrowser: (NativeBrowser) -> Unit, onNewWorkspace: (() -> Unit)? = null,
    onNewTerminal: (() -> Unit)? = null, onNewBrowser: (() -> Unit)? = null,
    utilities: @Composable ColumnScope.(close: () -> Unit) -> Unit = {}) {
    var expanded by remember(workspace?.id, selection.terminal?.id, selection.browser?.id, selection.surface?.id) {
        mutableStateOf(false)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Text("$title ▾", Modifier.testTag("terminal-picker").semantics { contentDescription = "Choose terminal or pane" }
            .clickable { expanded = true }.background(Color(0xFF191B1F), RoundedCornerShape(18.dp))
            .padding(horizontal = 15.dp, vertical = 7.dp), fontWeight = FontWeight.Medium,
            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            val close = { expanded = false }
            val terminals = workspace?.terminals.orEmpty()
            if (terminals.isNotEmpty()) PanePickerSection("Terminals")
            terminals.forEach { item ->
                PanePickerItem(item.title.ifBlank { "Terminal" }, "terminal-${item.id}", selection.terminal?.id == item.id) {
                    close(); onTerminal(item)
                }
            }
            val surfaces = workspace?.macSurfaces.orEmpty().filter { it.simulator == null }
            if (surfaces.isNotEmpty()) PanePickerSection("Mac Surfaces")
            surfaces.forEach { item ->
                PanePickerItem(item.displayTitle, "surface-${item.id}", selection.surface?.id == item.id) {
                    close(); onSurface(item)
                }
            }
            val simulators = workspace?.macSurfaces.orEmpty().filter { it.simulator != null }
            if (simulators.isNotEmpty()) PanePickerSection("Mac Simulators")
            simulators.forEach { item ->
                PanePickerItem(item.displayTitle, "surface-${item.id}", selection.surface?.id == item.id) {
                    close(); onSurface(item)
                }
            }
            val browsers = workspace?.browsers.orEmpty().filter { browser ->
                workspace?.simulators.orEmpty().none { it.panelId == browser.id }
            }
            if (browsers.isNotEmpty()) PanePickerSection("Mac Browsers")
            browsers.forEach { item ->
                PanePickerItem(item.title.ifBlank { "Browser" }, "browser-${item.id}", selection.browser?.id == item.id) {
                    close(); onBrowser(item)
                }
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("New Workspace") }, enabled = onNewWorkspace != null,
                onClick = { close(); onNewWorkspace?.invoke() })
            DropdownMenuItem(text = { Text("New Terminal") }, enabled = onNewTerminal != null,
                onClick = { close(); onNewTerminal?.invoke() })
            DropdownMenuItem(text = { Text("New Browser") }, enabled = onNewBrowser != null,
                onClick = { close(); onNewBrowser?.invoke() })
            utilities(close)
        }
    }
}

@Composable
private fun PanePickerItem(title: String, key: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(title) },
        modifier = Modifier.testTag("terminal-picker-$key").semantics { selected = checked },
        trailingIcon = { if (checked) Text("✓", Modifier.clearAndSetSemantics { }) }, onClick = onClick)
}

@Composable
private fun PanePickerSection(title: String) {
    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).semantics { heading() })
}
