package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class WorkspaceChromeKind { STATUS, ERROR, EMPTY, MORE, PROGRESS }
internal data class WorkspaceListChrome(val id: String, val text: String,
    val action: String? = null, val enabled: Boolean = true,
    val kind: WorkspaceChromeKind = WorkspaceChromeKind.STATUS, val actionTag: String? = null) {
    val key get() = "chrome:$id"
}

/** Chrome shares the body's gesture transaction and resolves every action against live state. */
internal fun LazyListScope.workspaceChromeRows(rendered: List<WorkspaceListChrome>,
    current: State<List<WorkspaceListChrome>>, action: State<(String) -> Unit>) {
    items(rendered, key = { it.key }, contentType = { it.kind }) { row ->
        WorkspacePresentationRow(current.value.any { it.key == row.key }, { current.value.any { it.key == row.key } }) {
            val admitted by rememberUpdatedState(LocalWorkspaceRowAdmission.current)
            WorkspaceMeasuredContent(row, LocalWorkspaceGeometryHeld.current) { shown, measuring ->
                WorkspaceChromeBody(shown, measuring) {
                    val live = current.value.singleOrNull { it.key == row.key }
                    if (admitted() && live?.enabled == true && live.action != null && live.action == shown.action)
                        action.value(live.id)
                }
            }
        }
    }
}

@Composable
private fun WorkspaceChromeBody(row: WorkspaceListChrome, measuring: Boolean, onAction: () -> Unit) {
    @Composable fun button() {
        TextButton(onClick = { if (!measuring) onAction() }, enabled = row.enabled && !measuring,
            modifier = if (!measuring && row.actionTag != null) Modifier.testTag(row.actionTag) else Modifier) {
            Text(row.action.orEmpty())
        }
    }
    when (row.kind) {
        WorkspaceChromeKind.STATUS -> Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(row.text, Modifier.weight(1f), color = Color(0xFF9B9FA8), fontSize = 12.sp)
            if (row.action != null) button()
        }
        WorkspaceChromeKind.ERROR -> Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(row.text, color = MaterialTheme.colorScheme.error)
            if (row.action != null) button()
        }
        WorkspaceChromeKind.EMPTY -> Text(row.text, Modifier.fillMaxWidth().padding(20.dp))
        WorkspaceChromeKind.MORE -> Box(Modifier.fillMaxWidth()) { button() }
        WorkspaceChromeKind.PROGRESS -> if (measuring) Spacer(Modifier.fillMaxWidth().height(4.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
    }
}
