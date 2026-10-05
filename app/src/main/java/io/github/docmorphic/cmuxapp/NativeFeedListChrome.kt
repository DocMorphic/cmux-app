package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun NativeFeedHistoryToggle(disclosure: NativeFeedDisclosure?, onToggle: (expanded: Boolean) -> Unit) {
    val latest by rememberUpdatedState(disclosure)
    val toggle by rememberUpdatedState(onToggle)
    val admitted by rememberUpdatedState(LocalWorkspaceRowAdmission.current)
    WorkspaceMeasuredContent(disclosure, LocalWorkspaceGeometryHeld.current) { shown, measuring ->
        Box(Modifier.fillMaxWidth()) {
            if (shown != null) Row(Modifier.fillMaxWidth().padding(end = 18.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    if (!measuring && admitted() && latest?.group == shown.group) toggle(!shown.expanded)
                }, enabled = !measuring && latest?.group == shown.group, modifier = Modifier.semantics {
                    contentDescription = if (shown.expanded) "Hide earlier notifications" else "Show earlier notifications"
                    stateDescription = "${shown.count} updates"
                }) { Text("${shown.count} ${if (shown.expanded) "⌄" else "›"}", color = Color(0xFF9B9FA8)) }
            }
        }
    }
}

@Composable
internal fun NativeFeedDivider(visible: Boolean) {
    WorkspaceMeasuredContent(visible, LocalWorkspaceGeometryHeld.current) { shown, _ ->
        Box(Modifier.fillMaxWidth()) { if (shown) HorizontalDivider(color = Color(0xFF292C31)) }
    }
}

/** Stateless status content; an offscreen probe never starts a spinner or dispatches an action. */
@Composable
internal fun NativeFeedStatusBody(row: NativeFeedListRow, measuring: Boolean, onAction: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        when (row) {
            is NativeFeedListRow.Notice -> Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(row.text, Modifier.weight(1f), color = Color(0xFFFFB86C), fontSize = 12.sp)
                TextButton(onClick = { if (!measuring) onAction() }, enabled = !measuring) { Text("Retry") }
            }
            NativeFeedListRow.More -> TextButton(onClick = { if (!measuring) onAction() }, enabled = !measuring,
                modifier = Modifier.fillMaxWidth()) { Text("Load more notifications") }
            is NativeFeedListRow.Empty -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (row.loading) {
                    if (measuring) Spacer(Modifier.size(24.dp)) else CircularProgressIndicator(Modifier.size(24.dp))
                }
                Text(row.text, color = Color(0xFF9B9FA8))
                if (row.retry) TextButton(onClick = { if (!measuring) onAction() }, enabled = !measuring) { Text("Retry") }
            }
            else -> Unit
        }
    }
}
