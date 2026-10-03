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
    onText: (() -> Unit)? = null, checksNewBrowser: Boolean = false) {
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
                } }, leadingIcon = { PaneMenuIcon(if (row.target is SshWorkspaceTarget.Browser) R.drawable.ic_workspace_globe else R.drawable.ic_computer_terminal) }, enabled = enabled, modifier = Modifier.testTag("ssh.picker.row.${row.target.encode()}").semantics { this.selected = checked },
                    trailingIcon = { if (checked) PaneMenuIcon(R.drawable.ic_menu_check) }, onClick = { close { onSelect(row.target) } })
            }
            layout.sections.forEach { section ->
                heading(section.title)
                section.rows.forEach { if (it.startsPane) HorizontalDivider(); row(it) }
                section.actions.forEach { action ->
                    DropdownMenuItem(text = { Text(action.title) }, leadingIcon = { PaneMenuIcon(action.menuIcon()) }, enabled = enabled && onAction != null,
                        modifier = Modifier.testTag("ssh.picker.action.${section.id}.${action.name}"),
                        onClick = { close { onAction?.invoke(section, action) } })
                }
            }
            if (layout.browsers.isNotEmpty()) { heading("Browsers"); layout.browsers.forEach { row(it) } }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("New Workspace") }, leadingIcon = { PaneMenuIcon(R.drawable.ic_menu_workspace_add) }, enabled = enabled && onNewWorkspace != null,
                onClick = { close { onNewWorkspace?.invoke() } })
            layout.newTerminalTitle?.let { label ->
                DropdownMenuItem(text = { Text(label) }, leadingIcon = { PaneMenuIcon(R.drawable.ic_task_plus) }, enabled = enabled && onNewTerminal != null,
                    onClick = { close { onNewTerminal?.invoke() } })
            }
            if (onBrowser != null) DropdownMenuItem(text = { Text("New Browser") }, leadingIcon = { PaneMenuIcon(R.drawable.ic_workspace_globe) }, enabled = enabled,
                modifier = Modifier.semantics { this.selected = checksNewBrowser },
                trailingIcon = { if (checksNewBrowser) PaneMenuIcon(R.drawable.ic_menu_check) }, onClick = { close(onBrowser) })
            if (onText != null || BuildConfig.DEBUG || feedback != null) HorizontalDivider()
            if (onText != null) DropdownMenuItem(text = { Text("View as Text") }, leadingIcon = { PaneMenuIcon(R.drawable.ic_workspace_file_text) }, onClick = { close(onText) })
            DebugLogMenuItem { expanded = false }
            if (feedback != null) DropdownMenuItem(text = { Text("Send Feedback") }, leadingIcon = { PaneMenuIcon(R.drawable.ic_menu_send) }, onClick = { close(feedback) })
        }
    }
}

internal fun sshPickerNativeRow(target: SshWorkspaceTarget) = when (target) {
    is SshWorkspaceTarget.Browser -> NativePanePickerRow("browser", target.selection.panelId, "")
    else -> NativePanePickerRow("terminal", target.encode(), "")
}

@Composable
internal fun SshBrowserPanePicker(title: String, presentation: SshPickerPresentation, linkedPanel: String?,
    onSelect: (NativePanePickerRow) -> Unit, onCommand: (SshPickerCommand) -> Unit) {
    val layout = presentation.layout
    val selected = layout.browsers.singleOrNull { (it.target as? SshWorkspaceTarget.Browser)?.selection?.panelId == linkedPanel }?.target
    fun command(operation: SshPickerOperation): (() -> Unit)? {
        val command = SshPickerCommand(operation)
        return if (presentation.permits(command)) ({ onCommand(command) }) else null
    }
    SshPanePicker(title, layout, selected, presentation.enabled,
        onSelect = { onSelect(sshPickerNativeRow(it)) },
        onAction = if (presentation.creationEnabled) ({ section, action -> onCommand(SshPickerCommand(SshPickerOperation.SECTION, section.id, action)) }) else null,
        onNewWorkspace = command(SshPickerOperation.WORKSPACE), onNewTerminal = command(SshPickerOperation.TERMINAL),
        onBrowser = if (selected == null) ({}) else command(SshPickerOperation.BROWSER), checksNewBrowser = selected == null)
}
