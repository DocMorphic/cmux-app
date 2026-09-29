package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun NativeAccountTeamSection(state: NativeAccountTeamsState, onRefresh: () -> Unit,
    onSelect: (String) -> Unit, onCreate: suspend (String) -> Boolean) {
    var expanded by remember { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val busy = state.loading || submitting
    Text("ACCOUNT", Modifier.padding(horizontal = 22.dp, vertical = 10.dp), color = Color(0xFF96989F), fontSize = 11.sp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Team", Modifier.weight(1f))
        Box {
            TextButton(onClick = { expanded = true }, enabled = !busy && state.teams.isNotEmpty()) {
                Text(state.teams.firstOrNull { it.id == state.selectedTeamId }?.name
                    ?: if (state.loading) "Loading…" else "No team")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.teams.forEach { team ->
                    DropdownMenuItem(text = { Text(team.name.ifBlank { "Unnamed team" }) },
                        trailingIcon = { if (team.id == state.selectedTeamId) Text("✓") },
                        onClick = { expanded = false; if (team.id != state.selectedTeamId) onSelect(team.id) })
                }
            }
        }
    }
    state.error?.let { Text(it, Modifier.padding(horizontal = 22.dp, vertical = 4.dp), color = Color(0xFFFF9999), fontSize = 13.sp) }
    Row(Modifier.padding(horizontal = 14.dp)) {
        TextButton(onClick = onRefresh, enabled = !busy) { Text("Refresh account") }
        TextButton(onClick = { name = ""; creating = true }, enabled = !busy && state.userId != null) { Text("Create Team") }
    }
    if (creating) AlertDialog(
        onDismissRequest = { if (!busy) creating = false },
        title = { Text("Create Team") },
        text = { Column {
            Text("Create a team for your cmux account and switch to it.")
            OutlinedTextField(value = name, onValueChange = { if (it.length <= 120) name = it },
                label = { Text("Team name") }, singleLine = true, enabled = !busy)
            state.error?.let { Text(it, color = Color(0xFFFF9999), fontSize = 13.sp) }
        } },
        confirmButton = { TextButton(enabled = !busy && name.trim().isNotEmpty(), onClick = {
            if (!submitting) {
                submitting = true
                scope.launch {
                    try { if (onCreate(name.trim())) { creating = false; name = "" } }
                    finally { submitting = false }
                }
            }
        }) { Text(if (busy) "Creating…" else "Create") } },
        dismissButton = { TextButton(onClick = { creating = false }, enabled = !busy) { Text("Cancel") } }
    )
}
