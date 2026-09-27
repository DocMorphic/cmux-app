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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    isCurrent: () -> Boolean = { true },
    savedDrafts: TaskDrafts? = null,
    draftId: String? = null,
    macName: String = "Mac",
    persistDrafts: suspend () -> Unit = {},
    flushDrafts: () -> Unit = {},
    onResumeDraft: (TaskDraft) -> Unit = {},
    onNewDraft: () -> Unit = {},
    supportsTaskCreation: Boolean = true
) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val collection = savedDrafts ?: remember { TaskDrafts() }
    val activeId = draftId ?: remember { UUID.randomUUID().toString() }
    val editor = remember(collection, activeId, origin) {
        collection.begin(activeId, origin, macName, directories.firstOrNull().orEmpty())
    }
    val entries by collection.state.collectAsState()
    val initialDraft = remember(editor) { checkNotNull(collection.state.value[editor.id]) }
    val draft = entries[editor.id] ?: initialDraft
    val agent = draft.agent
    val prompt = draft.prompt
    val directory = draft.directory
    val selection = draft.selection
    val submission = remember(editor) { TaskSubmissionIdentity().apply {
        initialDraft.lastRequest?.let { submitted(origin, JSONObject(it)) }
    } }
    val connectionToken = remember(client, origin) { Any() }
    val latestConnectionToken by rememberUpdatedState(connectionToken)
    val currentContext by rememberUpdatedState(isCurrent)
    val createdCallback by rememberUpdatedState(onCreated)
    var busy by remember { mutableStateOf(false) }
    var error by remember(editor) { mutableStateOf(if (initialDraft.lastRequest != null)
        "Previous task status is unconfirmed. Check your workspace list before retrying." else null) }
    var accepted by remember(editor) { mutableStateOf(false) }
    var dirty by remember(editor) { mutableStateOf(false) }
    var showDrafts by remember(editor) { mutableStateOf(false) }
    var confirmLeave by remember(editor) { mutableStateOf(false) }
    val canEdit = !busy && !accepted
    fun edit(update: (TaskDraft) -> TaskDraft) {
        if (busy || accepted) return
        collection.editIfCurrent(editor, update) ?: return
        dirty = true
        error = null
    }
    fun leave() {
        if (busy) return
        focus.clearFocus(); keyboard?.hide()
        if (dirty && !accepted) confirmLeave = true else onBack()
    }
    fun saveThen(action: () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                persistDrafts()
                currentCoroutineContext().ensureActive()
                check(collection.isCurrent(editor)) { "This draft session has changed" }
                action()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Could not save task drafts"
            } finally { busy = false }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentFlush by rememberUpdatedState(flushDrafts)
    DisposableEffect(editor, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) currentFlush() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); collection.end(editor); currentFlush() }
    }
    val provider = agent.command?.let(TaskAgentCommand::detect)
    val modelKey = provider?.let { TaskModelRepository.Key(origin, it) }
    var modelResult by remember(modelKey) { mutableStateOf(modelKey?.let(models::cached)
        ?: draft.restoredModels().takeIf { it.usable }) }
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
    LaunchedEffect(editor, modelKey, modelResult, busy, accepted, selection.explicit) {
        if (!busy && !accepted) collection.editIfCurrent(editor) {
            it.reconcileModels(modelKey?.provider, modelResult)
        }
    }
    BackHandler { leave() }
    if (confirmLeave) AlertDialog(onDismissRequest = { if (!busy) confirmLeave = false },
        title = { Text("Save this draft?") },
        text = { Text("Keep this task to continue later, or delete it.") },
        confirmButton = { TextButton(enabled = canEdit, onClick = { saveThen { confirmLeave = false; onBack() } }) { Text("Save Draft") } },
        dismissButton = { Row {
            TextButton(enabled = canEdit, onClick = {
                busy = true
                val removed = collection.remove(editor)
                scope.launch {
                    try { persistDrafts(); confirmLeave = false; onBack() }
                    catch (failure: Exception) {
                        // A dismissed view must not undo the user's deletion; the repository
                        // owns the pending write and lifecycle flush beyond this scope.
                        if (failure is CancellationException) throw failure
                        removed?.let { collection.restore(editor, it) }
                        error = "Could not delete task draft"
                        confirmLeave = false
                    } finally { busy = false }
                }
            }) { Text("Delete Draft", color = Color(0xFFFF9999)) }
            TextButton(enabled = canEdit, onClick = { confirmLeave = false }) { Text("Keep Editing") }
        } })
    if (showDrafts) TaskDraftsSheet(entries.values.filter { it.id != editor.id && !it.isEmpty }, busy, error,
        onDismiss = { if (!busy) showDrafts = false },
        onNew = { saveThen { showDrafts = false; onNewDraft() } },
        onResume = { selected -> saveThen { showDrafts = false; onResumeDraft(selected) } },
        onDelete = { selected ->
            busy = true
            val deleting = collection.begin(selected.id, selected.origin, selected.macName, selected.directory)
            val removed = collection.remove(deleting)
            scope.launch {
                try { persistDrafts() }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    removed?.let { collection.restore(deleting, it) }
                    error = "Could not delete task draft"
                } finally { collection.end(deleting); busy = false }
            }
        })

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0C0E)).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { leave() }, enabled = !busy) { Text("‹  Workspaces") }
            Text("New Task", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { focus.clearFocus(); keyboard?.hide(); showDrafts = true }, enabled = canEdit) { Text("Drafts") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text("Agent", color = Color(0xFF9B9FA8))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskCommand.Agent.entries.forEach { option ->
                FilterChip(selected = agent == option, onClick = { edit { it.copy(agent = option, selection = TaskModelSelection(), defaultModel = null) } },
                    enabled = canEdit, label = { Text(option.label) }, colors = FilterChipDefaults.filterChipColors(
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
                    }, enabled = canEdit, modifier = Modifier.semantics {
                        contentDescription = "Model"; stateDescription = selectedModel?.name ?: "Default"
                    }) { Text("${selectedModel?.name ?: "Default"} ▾") }
                    DropdownMenu(expanded = modelMenu != null, onDismissRequest = { modelMenu = null }) {
                        DropdownMenuItem(text = { Text("Default") }, onClick = {
                            edit { it.copy(selection = selection.choose(null, modelResult)) }
                            modelMenu = null
                        }, leadingIcon = { Text(if (selection.explicit == null) "✓" else " ") })
                        modelMenu.orEmpty().forEach { option ->
                            DropdownMenuItem(text = { Text(option.name) }, onClick = {
                                edit { it.copy(selection = selection.choose(option, modelResult)) }
                                modelMenu = null
                            }, leadingIcon = { Text(if (selection.explicit?.id == option.id) "✓" else " ") })
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { effortMenu = efforts.toList() }, enabled = canEdit && efforts.isNotEmpty(),
                        modifier = Modifier.semantics {
                            contentDescription = "Effort"; stateDescription = selectedEffort?.name ?: "Default"
                        }) { Text("${selectedEffort?.name ?: "Default"} ▾") }
                    DropdownMenu(expanded = effortMenu != null, onDismissRequest = { effortMenu = null }) {
                        effortMenu.orEmpty().forEach { option ->
                            DropdownMenuItem(text = { Column { Text(option.name)
                                option.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) } } },
                                onClick = {
                                    if (efforts.any { it.id == option.id }) edit { it.copy(selection = selection.copy(effortId = option.id)) }
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
        OutlinedTextField(prompt, { text -> edit { it.copy(prompt = text) } }, Modifier.fillMaxWidth().height(190.dp),
            enabled = canEdit, label = { Text(if (agent == TaskCommand.Agent.SHELL) "Workspace title (optional)" else "Task prompt") },
            minLines = 5)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(directory, { text -> edit { it.copy(directory = text) } }, Modifier.fillMaxWidth(),
            enabled = canEdit, singleLine = true, label = { Text("Directory on Mac") })
        if (directories.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                directories.distinct().take(8).forEach { option ->
                    TextButton(onClick = { edit { it.copy(directory = option) } }, enabled = canEdit) {
                        Text(option.substringAfterLast('/').ifBlank { option }, maxLines = 1)
                    }
                }
            }
        }
        Text("The agent starts in a new cmux workspace on this Mac.",
            color = Color(0xFF9B9FA8), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(18.dp))
        }
        if (!supportsTaskCreation) Text("Update cmux on this Mac to create tasks.",
            color = Color(0xFFFF9999), modifier = Modifier.padding(bottom = 12.dp))
        if (error != null) Text(error.orEmpty(), color = Color(0xFFFF9999),
            modifier = Modifier.padding(bottom = 12.dp))
        Button(onClick = {
            if (busy || accepted) return@Button
            val requestConnectionToken = connectionToken
            fun requestIsCurrent() = requestConnectionToken === latestConnectionToken && currentContext()
            val parameters = runCatching {
                check(supportsTaskCreation) { "Update cmux on this Mac to create tasks" }
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
                    collection.edit(editor) { it.copy(lastRequest = parameters.toString()) }
                    persistDrafts()
                    currentCoroutineContext().ensureActive()
                    check(requestIsCurrent() && collection.isCurrent(editor)) { "Task session changed before submission" }
                    val response = createTask(parameters)
                    currentCoroutineContext().ensureActive()
                    check(requestIsCurrent() && collection.isCurrent(editor)) { "Connection changed before the task could be opened" }
                    TaskCreationResult.parse(response)
                    response
                }
                    .onSuccess { response ->
                        collection.remove(editor)
                        flushDrafts()
                        accepted = true; busy = false
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
        }, enabled = canEdit && supportsTaskCreation && (agent == TaskCommand.Agent.SHELL || prompt.isNotBlank()),
            colors = ButtonDefaults.buttonColors(contentColor = Color(0xFF081421)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
            Text(if (accepted) "Task Created" else if (busy) "Creating…" else "Create Task")
        }
    }
}
