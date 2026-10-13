package io.github.docmorphic.cmuxapp

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun NativeAccountTeamSection(state: NativeAccountTeamsState, onRefresh: () -> Unit,
    onSelect: (String) -> Unit, afterAccount: @Composable () -> Unit = {}, onCreate: suspend (String) -> Boolean) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val busy = state.loading || submitting
    val canManage = state.userId != null && !state.cached
    val currentState by rememberUpdatedState(state)
    val currentBusy by rememberUpdatedState(busy)
    val currentSelect by rememberUpdatedState(onSelect)
    var admitted by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { admitted = false } }
    val owner = state.userId to state.scope?.login
    Text("ACCOUNT", Modifier.padding(horizontal = 22.dp, vertical = 10.dp).semantics { heading() }, color = Color(0xFF9B9FA8), fontSize = 11.sp)
    if (state.userId != null) SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
            Text(state.displayName ?: "cmux account")
            state.email?.let { Text(it, color = Color(0xFF9B9FA8), fontSize = 13.sp) }
        }
    }
    if (state.cached) Text("Showing saved account details. Connect to refresh your teams.",
        Modifier.padding(horizontal = 22.dp, vertical = 4.dp), color = Color(0xFF9B9FA8), fontSize = 13.sp)
    afterAccount()
    if (state.teams.size > 1) {
        Text("TEAM", Modifier.padding(horizontal = 22.dp, vertical = 10.dp).semantics { heading() },
            color = Color(0xFF9B9FA8), fontSize = 11.sp)
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).selectableGroup()) {
            state.teams.forEach { team ->
                // Keep cached accessibility actions bound to their original account and team.
                key(owner, team.id) {
                    val chosen = team.id == state.selectedTeamId
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("settings.team:${team.id}")
                        .selectable(chosen, enabled = !busy && canManage, role = Role.RadioButton, onClick = {
                            val latest = currentState
                            if (admitted && !currentBusy && !latest.cached && latest.userId != null &&
                                owner == (latest.userId to latest.scope?.login) && latest.teams.any { it.id == team.id } &&
                                latest.selectedTeamId != team.id) currentSelect(team.id)
                        }).semantics { contentDescription = team.name.ifBlank { "Unnamed team" } }
                        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(team.name.ifBlank { "Unnamed team" }, Modifier.weight(1f).padding(end = 12.dp),
                            color = if (canManage && !busy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (chosen) Icon(painterResource(R.drawable.ic_menu_check), null, Modifier.size(20.dp),
                            tint = if (canManage && !busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text("Switches which cmux team's computers and devices this app shows.",
            Modifier.padding(horizontal = 22.dp, vertical = 4.dp), color = Color(0xFF9B9FA8), fontSize = 13.sp)
    }
    state.error?.let { Text(it, Modifier.padding(horizontal = 22.dp, vertical = 4.dp), color = Color(0xFFFF9999), fontSize = 13.sp) }
    Row(Modifier.padding(horizontal = 14.dp)) {
        TextButton(onClick = onRefresh, enabled = !busy) { Text("Refresh account") }
        TextButton(onClick = { name = ""; creating = true }, enabled = !busy && canManage) { Text("Create Team") }
    }
    if (creating) AlertDialog(
        onDismissRequest = { if (!busy) creating = false },
        title = { Text("Create Team") },
        text = { Column {
            Text("Create a team for your cmux account and switch to it.")
            OutlinedTextField(value = name, onValueChange = { if (it.length <= 120) name = it },
                label = { Text("Team name") }, singleLine = true, enabled = !busy && canManage)
            state.error?.let { Text(it, color = Color(0xFFFF9999), fontSize = 13.sp) }
        } },
        confirmButton = { TextButton(enabled = !busy && canManage && name.trim().isNotEmpty(), onClick = {
            if (!submitting) {
                submitting = true
                scope.launch {
                    try { if (onCreate(name.trim())) { creating = false; name = "" } }
                    finally { submitting = false }
                }
            }
        }) { Text(if (busy) "Creating…" else "Create") } },
        dismissButton = { TextButton(onClick = { creating = false }, enabled = !busy) { Text(stringResource(R.string.cmux_ui_mobile_common_cancel_fa7c525e)) } }
    )
}
