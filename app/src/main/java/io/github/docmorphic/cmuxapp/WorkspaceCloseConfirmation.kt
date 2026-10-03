package io.github.docmorphic.cmuxapp

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

internal enum class PersistentSshWorkspaceKind { TMUX, CMUX_TUI }

/** Shared by close entry points. Phone-owned shell tabs close directly. */
internal data class WorkspaceCloseConfirmation(val title: String, val message: String, val actionTitle: String) {
    companion object {
        val mac = WorkspaceCloseConfirmation("Delete Workspace?", "This will close the workspace on your Mac.", "Delete")

        fun ssh(kind: PersistentSshWorkspaceKind, workspaceName: String, hostName: String): WorkspaceCloseConfirmation {
            val title = "End “$workspaceName” on $hostName?"
            return when (kind) {
                PersistentSshWorkspaceKind.TMUX -> WorkspaceCloseConfirmation(title,
                    "This closes the tmux session and stops everything running in it, including anything open on other devices.", "End Session")
                PersistentSshWorkspaceKind.CMUX_TUI -> WorkspaceCloseConfirmation(title,
                    "This closes the workspace and its terminals and stops everything running in them, including anything open on other devices.", "Close Workspace")
            }
        }
    }
}

@Composable
internal fun WorkspaceCloseDialog(confirmation: WorkspaceCloseConfirmation, enabled: Boolean = true,
    onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(confirmation.title) }, text = { Text(confirmation.message) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled, modifier = Modifier.testTag("workspace.close.confirm"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(confirmation.actionTitle) }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
