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

/** Only pane identity and presentation cross the routed-browser process boundary. */
internal data class NativePanePickerRow(val kind: String, val id: String, val title: String, val simulator: Boolean = false)
internal fun nativePanePickerRows(workspace: NativeWorkspace): List<NativePanePickerRow> =
    workspace.terminals.map { NativePanePickerRow("terminal", it.id, it.title.ifBlank { "Terminal" }) } +
        workspace.macSurfaces.map { NativePanePickerRow("surface", it.id, it.displayTitle, it.simulator != null) } +
        workspace.browsers.filter { browser -> workspace.simulators.none { it.panelId == browser.id } }
            .map { NativePanePickerRow("browser", it.id, it.title.ifBlank { "Browser" }) }

/** Shared live inventory for terminal, streamed browser and Mac surface headers. */
@Composable
internal fun NativePanePicker(title: String, workspace: NativeWorkspace?, selection: NativeWorkspacePane,
    modifier: Modifier = Modifier, onTerminal: (NativeTerminal) -> Unit, onSurface: (NativeSurface) -> Unit,
    onBrowser: (NativeBrowser) -> Unit, onNewWorkspace: (() -> Unit)? = null,
    onNewTerminal: (() -> Unit)? = null, onNewBrowser: (() -> Unit)? = null,
    utilities: @Composable ColumnScope.(close: () -> Unit) -> Unit = {}) {
    val rows = workspace?.let(::nativePanePickerRows).orEmpty()
    val selected = rows.singleOrNull { row -> when (row.kind) {
        "terminal" -> row.id == selection.terminal?.id
        "surface" -> row.id == selection.surface?.id
        "browser" -> row.id == selection.browser?.id
        else -> false
    } }
    NativePanePicker(title, rows, selected, modifier, onSelect = { row ->
        when (row.kind) {
            "terminal" -> workspace?.terminals?.singleOrNull { it.id == row.id }?.let(onTerminal)
            "surface" -> workspace?.macSurfaces?.singleOrNull { it.id == row.id }?.let(onSurface)
            "browser" -> workspace?.browsers?.singleOrNull { it.id == row.id }?.let(onBrowser)
        }
    }, onNewWorkspace, onNewTerminal, onNewBrowser, utilities = utilities)
}

@Composable
internal fun NativePanePicker(title: String, rows: List<NativePanePickerRow>, selectedRow: NativePanePickerRow?,
    modifier: Modifier = Modifier, onSelect: (NativePanePickerRow) -> Unit,
    onNewWorkspace: (() -> Unit)? = null, onNewTerminal: (() -> Unit)? = null,
    onNewBrowser: (() -> Unit)? = null, checksNewBrowser: Boolean = false,
    utilities: @Composable ColumnScope.(close: () -> Unit) -> Unit = {}) {
    var expanded by remember(selectedRow?.kind, selectedRow?.id, checksNewBrowser) {
        mutableStateOf(false)
    }
    val feedback = LocalNativeFeedback.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Text("$title ▾", Modifier.testTag("terminal-picker").semantics { contentDescription = "Choose terminal or pane" }
            .clickable { expanded = true }.background(Color(0xFF191B1F), RoundedCornerShape(18.dp))
            .padding(horizontal = 15.dp, vertical = 7.dp), fontWeight = FontWeight.Medium,
            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            val close = { expanded = false }
            val sections = listOf("Terminals" to rows.filter { it.kind == "terminal" },
                "Mac Surfaces" to rows.filter { it.kind == "surface" && !it.simulator },
                "Mac Simulators" to rows.filter { it.kind == "surface" && it.simulator },
                "Mac Browsers" to rows.filter { it.kind == "browser" })
            sections.forEach { (heading, items) ->
                if (items.isNotEmpty()) PanePickerSection(heading)
                items.forEach { row ->
                    PanePickerItem(row.title, "${row.kind}-${row.id}", row.kind == selectedRow?.kind && row.id == selectedRow?.id) {
                        close(); onSelect(row)
                    }
                }
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("New Workspace") }, enabled = onNewWorkspace != null,
                onClick = { close(); onNewWorkspace?.invoke() })
            DropdownMenuItem(text = { Text("New Terminal") }, enabled = onNewTerminal != null,
                onClick = { close(); onNewTerminal?.invoke() })
            DropdownMenuItem(text = { Text("New Browser") }, enabled = onNewBrowser != null,
                modifier = Modifier.testTag("terminal-picker-new-browser").semantics { selected = checksNewBrowser },
                trailingIcon = { if (checksNewBrowser) Text("✓", Modifier.clearAndSetSemantics { }) },
                onClick = { close(); onNewBrowser?.invoke() })
            utilities(close)
            DebugLogMenuItem(close)
            if (feedback != null) DropdownMenuItem(text = { Text("Send Feedback") }, onClick = { close(); feedback() })
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
