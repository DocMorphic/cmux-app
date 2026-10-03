package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class SshPaneAction(val title: String) { NEW_TAB("New Tab"), SPLIT_RIGHT("Split Right"), SPLIT_DOWN("Split Down") }
internal data class SshPickerRow(val target: SshWorkspaceTarget, val title: String, val paneLabel: String? = null, val startsPane: Boolean = false)
internal data class SshPickerSection(val id: Int, val title: String, val rows: List<SshPickerRow>, val targetPane: Int? = null,
    val actions: List<SshPaneAction> = emptyList())
internal data class SshPickerLayout(val sections: List<SshPickerSection>, val browsers: List<SshPickerRow> = emptyList(),
    val newTerminalTitle: String? = null)

internal fun sshTmuxPicker(workspace: SshTmuxWorkspace) = SshPickerLayout(
    workspace.panes.sortedWith(compareBy({ it.windowIndex }, { it.index })).groupBy { it.window }.map { (window, panes) ->
        SshPickerSection(window, "${panes.first().windowIndex}: ${panes.first().windowName}", panes.mapIndexed { index, pane ->
            SshPickerRow(SshWorkspaceTarget.Tmux(workspace.id, window, pane.id),
                if (pane.count > 1) "Pane ${index + 1}" else pane.windowName, startsPane = index > 0)
        }, actions = listOf(SshPaneAction.SPLIT_RIGHT, SshPaneAction.SPLIT_DOWN))
    }, newTerminalTitle = "New Window")

internal fun sshCmuxPicker(session: String, tree: SshCmuxTree, workspace: SshCmuxWorkspace): SshPickerLayout {
    val sections = workspace.screens.mapIndexedNotNull { index, screen ->
        val rows = mutableListOf<SshPickerRow>()
        screen.panes.forEachIndexed { paneIndex, pane ->
            pane.tabs.filter { it.isTerminal && !it.dead }.forEachIndexed { tabIndex, tab ->
                rows += SshPickerRow(SshWorkspaceTarget.Cmux(SshCmuxSelection.capture(session, tree, workspace, tab)),
                    tab.name?.takeIf { it.isNotBlank() } ?: tab.title.ifBlank { workspace.name },
                    if (screen.panes.size > 1) "Pane ${paneIndex + 1}" else null, tabIndex == 0 && rows.isNotEmpty())
            }
        }
        val target = screen.activePane ?: screen.panes.firstOrNull()?.id
        if (rows.isEmpty()) null else SshPickerSection(screen.id, screen.name?.takeIf { it.isNotBlank() } ?: "Screen ${index + 1}", rows,
            targetPane = target, actions = if (screen.panes.any { it.id == target && !it.dead }) SshPaneAction.entries else emptyList())
    }
    val browsers = workspace.tabs.filter { it.isBrowser && !it.dead }.map { tab ->
        SshPickerRow(SshWorkspaceTarget.Browser(SshCmuxBrowserSelection.capture(session, tree, workspace, tab)),
            tab.name?.takeIf { it.isNotBlank() } ?: tab.title.ifBlank { "Browser" })
    }
    return SshPickerLayout(sections, browsers, "New Screen")
}

/** Presentation rows hold durable targets; the owning route revalidates before executing actions. */
@Composable
internal fun SshPanePicker(title: String, layout: SshPickerLayout, selected: SshWorkspaceTarget?, enabled: Boolean,
    onSelect: (SshWorkspaceTarget) -> Unit, onAction: ((SshPickerSection, SshPaneAction) -> Unit)? = null,
    onNewWorkspace: (() -> Unit)? = null, onNewTerminal: (() -> Unit)? = null, onBrowser: (() -> Unit)? = null,
    onText: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    val feedback = LocalNativeFeedback.current
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("ssh.shell.menu")) {
            Text("$title ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            fun close(action: () -> Unit) { expanded = false; action() }
            @Composable fun heading(title: String) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).semantics { heading() })
            }
            @Composable fun row(row: SshPickerRow) {
                val checked = row.target == selected
                DropdownMenuItem(text = { Column {
                    Text(row.title)
                    row.paneLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                } }, enabled = enabled, modifier = Modifier.testTag("ssh.picker.row.${row.target.encode()}").semantics { this.selected = checked },
                    trailingIcon = { if (checked) Text("✓", Modifier.clearAndSetSemantics { }) }, onClick = { close { onSelect(row.target) } })
            }
            layout.sections.forEach { section ->
                heading(section.title)
                section.rows.forEach { if (it.startsPane) HorizontalDivider(); row(it) }
                section.actions.forEach { action ->
                    DropdownMenuItem(text = { Text(action.title) }, enabled = enabled && onAction != null,
                        modifier = Modifier.testTag("ssh.picker.action.${section.id}.${action.name}"),
                        onClick = { close { onAction?.invoke(section, action) } })
                }
            }
            if (layout.browsers.isNotEmpty()) { heading("Browsers"); layout.browsers.forEach { row(it) } }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("New Workspace") }, enabled = enabled && onNewWorkspace != null,
                onClick = { close { onNewWorkspace?.invoke() } })
            layout.newTerminalTitle?.let { label ->
                DropdownMenuItem(text = { Text(label) }, enabled = enabled && onNewTerminal != null,
                    onClick = { close { onNewTerminal?.invoke() } })
            }
            if (onBrowser != null) DropdownMenuItem(text = { Text("New Browser") }, enabled = enabled, onClick = { close(onBrowser) })
            if (onText != null) DropdownMenuItem(text = { Text("View as Text") }, onClick = { close(onText) })
            if (feedback != null) DropdownMenuItem(text = { Text("Send Feedback") }, onClick = { close(feedback) })
        }
    }
}
