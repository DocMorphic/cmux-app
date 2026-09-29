package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

internal data class TaskGroupSelection(val id: String?, val groups: List<NativeGroup>, val supported: Boolean?, val loaded: Boolean) {
    val pending get() = id != null && supported != false && (supported == null || !loaded)
    val missing get() = id != null && (supported == false || (supported == true && loaded && groups.count { it.id == id } != 1))
    val valid get() = !pending && !missing
    val visible get() = supported == true || id != null
    val label get() = when { pending -> "Loading groups…"; missing -> "Choose a group"; else -> groups.singleOrNull { it.id == id }?.name ?: "None" }
}

@Composable
internal fun TaskOptionsView(draft: TaskDraft, macs: List<NativeCredentialStore.PairedMac>, groups: TaskGroupSelection,
    enabled: Boolean, error: String?, onName: (String) -> Unit, onMac: (String) -> Unit,
    onGroup: (String?) -> Unit, onDirectory: () -> Unit, onRefreshGroups: () -> Unit, onDismiss: () -> Unit,
    computerName: (NativeCredentialStore.PairedMac) -> String = { it.name }) {
    var macMenu by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    BackHandler(enabled) { onDismiss() }
    Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0C0E)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Task Options", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDismiss, enabled = enabled) { Text("Done") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF1B1C1E)) {
                    TextField(draft.workspaceName, onName, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
                        label = { Text("Workspace name (optional)", fontSize = 12.sp) }, placeholder = { Text("Generated from prompt") },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent))
                }
                Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF1B1C1E)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                Box {
                    TaskOptionRouteRow("Machine", macs.firstOrNull { it.ownsOrigin(draft.origin) }?.let(computerName) ?: draft.macName, R.drawable.ic_feed_computer, "Task Mac",
                        enabled && macs.isNotEmpty(), menu = true, onClick = { macMenu = true })
                    DropdownMenu(macMenu, { macMenu = false }) {
                        macs.forEach { mac -> DropdownMenuItem(text = { Text(computerName(mac)) },
                            modifier = Modifier.semantics { contentDescription = "Task Mac: ${computerName(mac)}" },
                            trailingIcon = { if (mac.ownsOrigin(draft.origin)) Text("✓") },
                            onClick = { macMenu = false; if (!mac.ownsOrigin(draft.origin)) onMac(mac.origin) }) }
                    }
                }
                HorizontalDivider(Modifier.padding(start = 60.dp), color = Color(0xFF34363B))
                TaskOptionRouteRow("Directory", draft.directory.ifBlank { "Choose Folder" }, R.drawable.ic_workspace_folder_fill,
                    "Browse folders", enabled, monospace = true, onClick = onDirectory)
                if (groups.visible) {
                    HorizontalDivider(Modifier.padding(start = 60.dp), color = Color(0xFF34363B))
                    Box {
                        TaskOptionRouteRow("Workspace group", groups.label,
                            groups.groups.singleOrNull { it.id == groups.id }?.iconSymbol?.let(::nativeWorkspaceGroupIcon) ?: R.drawable.ic_primary_workspaces,
                            "Workspace group", enabled, menu = true, onClick = { groupMenu = true })
                        DropdownMenu(groupMenu, { groupMenu = false }) {
                            DropdownMenuItem(text = { Text("None") }, onClick = { groupMenu = false; onGroup(null) }, trailingIcon = { if (groups.id == null) Text("✓") })
                            if (groups.supported == true && groups.loaded) groups.groups.distinctBy { it.id }.forEach { group ->
                                DropdownMenuItem(text = { Text(group.name) }, enabled = groups.groups.count { it.id == group.id } == 1,
                                    trailingIcon = { if (groups.id == group.id) Text("✓") }, onClick = { groupMenu = false; onGroup(group.id) })
                            }
                            if (groups.pending) DropdownMenuItem(text = { Text("Waiting for this Mac’s groups") }, enabled = false, onClick = {})
                            else if (groups.supported == false || groups.groups.isEmpty()) DropdownMenuItem(text = { Text("No workspace groups on this Mac") }, enabled = false, onClick = {})
                        }
                    }
                }
                }
                }
                if (!groups.valid) {
                    Text(if (groups.pending) "Waiting for this Mac’s groups." else "Choose another group or select None before submitting.", color = Color(0xFFFF9999))
                    TextButton(onClick = onRefreshGroups, enabled = enabled) { Text("Refresh groups") }
                }
                error?.let { Text(it, color = Color(0xFFFF9999)) }
            }
        }
    }
}

@Composable
private fun TaskOptionRouteRow(title: String, value: String, icon: Int, description: String,
    enabled: Boolean, menu: Boolean = false, monospace: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).semantics { contentDescription = description; stateDescription = value }
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(21.dp), tint = Color(0xFF76B9FF))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Color(0xFF9B9FA8), fontSize = 12.sp, maxLines = 1)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (menu) "⌃\n⌄" else "›", color = Color(0xFF9B9FA8), fontSize = 12.sp, lineHeight = 7.sp)
    }
}
