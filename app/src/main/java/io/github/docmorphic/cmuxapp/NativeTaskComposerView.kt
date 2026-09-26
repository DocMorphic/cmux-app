package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** The built-in cmux task templates backed by workspace.create. */
@Composable
fun NativeTaskComposerView(
    client: MobileRpcClient,
    directories: List<String>,
    onCreated: (JSONObject) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var agent by remember { mutableStateOf(TaskCommand.Agent.CLAUDE) }
    var prompt by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf(directories.firstOrNull().orEmpty()) }
    var operationId by remember { mutableStateOf(UUID.randomUUID()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(agent, prompt, directory) { if (!busy) operationId = UUID.randomUUID() }
    BackHandler { if (!busy) onBack() }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0C0E)).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = !busy) { Text("‹  Workspaces") }
            Text("New Task", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        }
        Text("Agent", color = Color(0xFF9B9FA8))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskCommand.Agent.entries.forEach { option ->
                FilterChip(selected = agent == option, onClick = { agent = option },
                    enabled = !busy, label = { Text(option.label) })
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth().height(190.dp),
            enabled = !busy, label = { Text(if (agent == TaskCommand.Agent.SHELL) "Workspace title (optional)" else "Task prompt") },
            minLines = 5)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(directory, { directory = it }, Modifier.fillMaxWidth(),
            enabled = !busy, singleLine = true, label = { Text("Directory on Mac") })
        if (directories.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                directories.distinct().take(8).forEach { option ->
                    TextButton(onClick = { directory = option }, enabled = !busy) {
                        Text(option.substringAfterLast('/').ifBlank { option }, maxLines = 1)
                    }
                }
            }
        }
        Text("The agent starts in a new cmux workspace on this Mac.",
            color = Color(0xFF9B9FA8), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        if (error != null) Text(error.orEmpty(), color = Color(0xFFFF9999),
            modifier = Modifier.padding(bottom = 12.dp))
        Button(onClick = {
            val parameters = runCatching { TaskCommand.parameters(agent, prompt, directory, operationId) }
                .getOrElse { error = it.message; return@Button }
            busy = true; error = null
            scope.launch {
                runCatching { client.request("workspace.create", parameters, timeoutMillis = 30_000) }
                    .onSuccess { response ->
                        busy = false
                        val created = response.optString("created_workspace_id")
                        if (created.isBlank()) error = "Mac did not identify the new task workspace"
                        else { operationId = UUID.randomUUID(); onCreated(response) }
                    }
                    .onFailure {
                        busy = false
                        error = (it.message ?: "Could not create task") +
                            ". Check your workspace list before retrying."
                    }
            }
        }, enabled = !busy && (agent == TaskCommand.Agent.SHELL || prompt.isNotBlank()),
            modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
            Text(if (busy) "Creating…" else "Create Task")
        }
    }
}
