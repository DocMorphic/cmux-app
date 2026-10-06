package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Management keeps hidden Cloud machines reachable without changing their remote lifecycle. */
@Composable internal fun NativeCloudComputerRows(snapshots: List<CloudWorkspaceSnapshot>, hidden: Set<String>,
    enabled: Boolean, onVisibility: (CloudWorkspaceSnapshot, Boolean) -> Unit) {
    if (snapshots.isEmpty()) return
    Text("Cloud Computers", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 14.dp))
    val ordered = snapshots.sortedWith(compareBy<CloudWorkspaceSnapshot> { it.machine.id in hidden }
        .thenBy { it.machine.preferredName.lowercase(java.util.Locale.ROOT) })
    ordered.forEach { snapshot -> key(snapshot.machine.id) {
        val machine = snapshot.machine
        val visible = machine.id !in hidden
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(machine.preferredName, fontWeight = FontWeight.SemiBold)
                    Text(if (visible) "Cloud · ${machine.status}" else "Cloud · Hidden on this phone",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (visible) NativeComputerStatusDot(NativeComputerConnection(snapshot.availability), NativeComputerPresence(), reconnect = false)
                Switch(checked = visible, onCheckedChange = { onVisibility(snapshot, it) }, enabled = enabled,
                    modifier = Modifier.testTag("computer.visibility.cloud:${machine.id}")
                        .semantics { contentDescription = "Show ${machine.preferredName} on this phone" })
            }
        }
    } }
}
