package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** The built-in cmux task templates backed by workspace.create. */
@Composable
internal fun NativeTaskComposerView(
    client: MobileRpcClient,
    directories: List<String>,
    origin: String,
    models: TaskModelRepository,
    onCreated: (JSONObject) -> Unit,
    onBack: () -> Unit,
    catalog: suspend (TaskAgentCommand) -> TaskModelResult = TaskModelCatalog::load,
    createTask: suspend (JSONObject) -> JSONObject = { client.request("workspace.create", it, timeoutMillis = 30_000) },
    isCurrent: () -> Boolean = { true }
) {
    val scope = rememberCoroutineScope()
    var agent by remember { mutableStateOf(TaskCommand.Agent.CLAUDE) }
    var prompt by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf(directories.firstOrNull().orEmpty()) }
    val submission = remember(origin) { TaskSubmissionIdentity() }
    val connectionToken = remember(client, origin) { Any() }
    val latestConnectionToken by rememberUpdatedState(connectionToken)
    val currentContext by rememberUpdatedState(isCurrent)
    val createdCallback by rememberUpdatedState(onCreated)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val provider = agent.command?.let(TaskAgentCommand::detect)
    val modelKey = provider?.let { TaskModelRepository.Key(origin, it) }
    var modelResult by remember(modelKey) { mutableStateOf(modelKey?.let(models::cached)) }
    var selection by remember(modelKey) { mutableStateOf(TaskModelSelection().reconcile(modelResult)) }
    var modelMenu by remember(modelKey) { mutableStateOf<List<TaskModel>?>(null) }
    var effortMenu by remember(modelKey) { mutableStateOf<List<TaskEffort>?>(null) }
    var loading by remember(modelKey) { mutableStateOf(false) }
    val selectedModel = selection.model(modelResult)
    val effortModel = selection.effective(modelResult)
    val efforts = effortModel?.efforts.orEmpty()
    val selectedEffort = efforts.firstOrNull { it.id == selection.effortId }
    LaunchedEffect(client, modelKey) {
        if (modelKey == null) return@LaunchedEffect
        loading = true
        try {
            var attempt = 0
            do {
                val retry = models.refresh(modelKey,
                    host = { TaskModelParser.host(client.request("mobile.task.models.list",
                        JSONObject().put("provider", modelKey.provider.wireName))) },
                    catalog = { catalog(modelKey.provider) }, update = { modelResult = it })
                if (!retry) break
                delay(TaskModelRepository.retryDelay(attempt++))
            } while (true)
        } finally { loading = false }
    }
    LaunchedEffect(modelResult, busy, selection.explicit) { if (!busy) selection = selection.reconcile(modelResult) }
    BackHandler { if (!busy) onBack() }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0C0E)).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = !busy) { Text("‹  Workspaces") }
            Text("New Task", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text("Agent", color = Color(0xFF9B9FA8))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskCommand.Agent.entries.forEach { option ->
                FilterChip(selected = agent == option, onClick = { agent = option },
                    enabled = !busy, label = { Text(option.label) }, colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF253440), selectedLabelColor = Color(0xFF76B9FF)))
            }
        }
        if (provider != null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = {
                        val current = modelResult?.models.orEmpty()
                        modelMenu = if (selectedModel != null && current.none { it.id == selectedModel.id }) current + selectedModel else current
                    }, enabled = !busy, modifier = Modifier.semantics {
                        contentDescription = "Model"; stateDescription = selectedModel?.name ?: "Default"
                    }) { Text("${selectedModel?.name ?: "Default"} ▾") }
                    DropdownMenu(expanded = modelMenu != null, onDismissRequest = { modelMenu = null }) {
                        DropdownMenuItem(text = { Text("Default") }, onClick = {
                            if (!busy) selection = selection.choose(null, modelResult)
                            modelMenu = null
                        }, leadingIcon = { Text(if (selection.explicit == null) "✓" else " ") })
                        modelMenu.orEmpty().forEach { option ->
                            DropdownMenuItem(text = { Text(option.name) }, onClick = {
                                if (!busy) selection = selection.choose(option, modelResult)
                                modelMenu = null
                            }, leadingIcon = { Text(if (selection.explicit?.id == option.id) "✓" else " ") })
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { effortMenu = efforts.toList() }, enabled = !busy && efforts.isNotEmpty(),
                        modifier = Modifier.semantics {
                            contentDescription = "Effort"; stateDescription = selectedEffort?.name ?: "Default"
                        }) { Text("${selectedEffort?.name ?: "Default"} ▾") }
                    DropdownMenu(expanded = effortMenu != null, onDismissRequest = { effortMenu = null }) {
                        effortMenu.orEmpty().forEach { option ->
                            DropdownMenuItem(text = { Column { Text(option.name)
                                option.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) } } },
                                onClick = {
                                    if (!busy && efforts.any { it.id == option.id }) selection = selection.copy(effortId = option.id)
                                    effortMenu = null
                                }, leadingIcon = { Text(if (selection.effortId == option.id) "✓" else " ") })
                        }
                    }
                }
            }
            if (loading && modelResult?.usable != true) Text("Loading models…", style = MaterialTheme.typography.bodySmall)
            val modelError = when (modelResult?.error) {
                TaskModelError.PROVIDER_UNAVAILABLE -> "${agent.label} unavailable"
                TaskModelError.QUERY_FAILED -> "Couldn’t load models"
                TaskModelError.HOST_UNAVAILABLE -> "Mac unavailable".takeUnless { modelResult?.usable == true }
                null -> null
            }
            modelError?.let { Text(it, color = Color(0xFFFF9999), style = MaterialTheme.typography.bodySmall) }
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
        Spacer(Modifier.height(18.dp))
        }
        if (error != null) Text(error.orEmpty(), color = Color(0xFFFF9999),
            modifier = Modifier.padding(bottom = 12.dp))
        Button(onClick = {
            if (busy) return@Button
            val requestConnectionToken = connectionToken
            fun requestIsCurrent() = requestConnectionToken === latestConnectionToken && currentContext()
            val parameters = runCatching {
                check(requestIsCurrent()) { "Connection changed. Reconnect to this Mac before creating the task" }
                submission.resolve(origin, TaskCommand.parameters(agent, prompt, directory, UUID.randomUUID(),
                    selection.explicit?.id, selectedEffort?.id))
            }
                .getOrElse { error = it.message; return@Button }
            busy = true; error = null
            scope.launch {
                runCatching {
                    check(requestIsCurrent()) { "Connection changed" }
                    submission.submitted(origin, parameters)
                    val response = createTask(parameters)
                    currentCoroutineContext().ensureActive()
                    check(requestIsCurrent()) { "Connection changed before the task could be opened" }
                    TaskCreationResult.parse(response)
                    response
                }
                    .onSuccess { response ->
                        busy = false
                        createdCallback(response)
                    }
                    .onFailure {
                        if (it is CancellationException) {
                            currentCoroutineContext().ensureActive()
                            if (it !is TimeoutCancellationException) throw it
                        }
                        busy = false
                        error = (it.message ?: "Could not create task") +
                            ". Check your workspace list before retrying."
                    }
            }
        }, enabled = !busy && (agent == TaskCommand.Agent.SHELL || prompt.isNotBlank()),
            colors = ButtonDefaults.buttonColors(contentColor = Color(0xFF081421)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
            Text(if (busy) "Creating…" else "Create Task")
        }
    }
}
