package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Host identity is part of the menu, even when two hosts have identical kind lists. */
@Composable
internal fun SshWorkspaceCreateMenu(host: SshHostRecord?, options: List<SshWorkspaceKindOption>, enabled: Boolean,
    isCurrent: (SshHostRecord) -> Boolean, onCreate: (SshHostRecord, SshWorkspaceKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val openingHost = remember(open) { host.takeIf { open } }
    val openingOptions = remember(open) { options }
    val currentCheck by rememberUpdatedState(isCurrent)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOptions by rememberUpdatedState(options)
    LaunchedEffect(host, enabled) {
        if (openingHost != null && (!enabled || host == null || !host.connectsLike(openingHost) || !currentCheck(openingHost))) open = false
    }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled && host != null,
            modifier = Modifier.semantics { contentDescription = "New Workspace" }.testTag("ssh.workspace.create")) { Text("+") }
        DropdownMenu(open && openingHost != null, onDismissRequest = { open = false }) {
            SshWorkspaceKindMenuItems(openingOptions, enabled,
                canCreate = { kind -> currentOptions.singleOrNull { it.kind == kind }?.unavailableReason == null && currentOptions.any { it.kind == kind } },
                onCreate = { kind ->
                    open = false
                    val target = openingHost
                    if (target != null && currentEnabled && currentCheck(target) &&
                        currentOptions.singleOrNull { it.kind == kind }?.let { it.unavailableReason == null } == true)
                        onCreate(target, kind)
                })
        }
    }
}

@Composable
internal fun SshWorkspaceKindMenuItems(options: List<SshWorkspaceKindOption>, enabled: Boolean,
    canCreate: (SshWorkspaceKind) -> Boolean, onCreate: (SshWorkspaceKind) -> Unit) {
    options.forEach { option ->
        DropdownMenuItem(text = { Column {
            Text(option.kind.title)
            option.unavailableReason?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        } }, enabled = option.unavailableReason == null && canCreate(option.kind) && enabled,
            modifier = Modifier.testTag("ssh.workspace.create.${option.kind.name}"),
            leadingIcon = { Icon(painterResource(when (option.kind) {
                SshWorkspaceKind.CMUX_TUI -> R.drawable.ic_ssh_kind_cmux
                SshWorkspaceKind.TMUX -> R.drawable.ic_ssh_kind_tmux
                SshWorkspaceKind.SHELL -> R.drawable.ic_workspace_terminal
            }), null, Modifier.size(21.dp)) }, onClick = {
                if (enabled && option.unavailableReason == null && canCreate(option.kind)) onCreate(option.kind)
            })
    }
}
