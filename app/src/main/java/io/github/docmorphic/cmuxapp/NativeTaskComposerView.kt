package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** The built-in cmux task templates backed by workspace.create. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeTaskComposerView(
    client: MobileRpcClient?,
    directories: List<String>,
    origin: String,
    models: TaskModelRepository,
    onCreated: (JSONObject) -> Unit,
    onBack: () -> Unit,
    catalog: suspend (TaskAgentCommand) -> TaskModelResult = TaskModelCatalog::load,
    createTask: suspend (JSONObject) -> JSONObject = { checkNotNull(client) { "That Mac is not connected" }.request("workspace.create", it, timeoutMillis = 30_000) },
    isCurrent: () -> Boolean = { true },
    savedDrafts: TaskDrafts? = null,
    draftId: String? = null,
    macName: String = "Mac",
    persistDrafts: suspend () -> Unit = {},
    flushDrafts: () -> Unit = {},
    onResumeDraft: (TaskDraft) -> Unit = {},
    onNewDraft: () -> Unit = {},
    supportsTaskCreation: Boolean? = true,
    refreshWorkspaces: suspend () -> Unit = { checkNotNull(client) { "That Mac is not connected" }.workspaces(); Unit },
    savedTemplates: TaskTemplates? = null,
    persistTemplateChange: (suspend (TaskTemplateChange) -> Unit)? = null,
    macs: List<NativeCredentialStore.PairedMac> = emptyList(),
    selectMac: (suspend (TaskDrafts.Editor, String) -> Unit)? = null,
    workspaceGroups: List<NativeGroup> = emptyList(), supportsGroups: Boolean? = false, groupsLoaded: Boolean = true,
    directoryWorkspaces: List<NativeWorkspace> = emptyList(), selectedWorkspaceId: String? = null,
    groupIsCurrent: ((String?) -> Boolean)? = null,
    attachmentRepository: TaskDraftRepository? = null, supportsAttachments: Boolean = false,
    hasSelectedMac: Boolean = true,
    resolvedMacOrigin: String? = null,
    appearances: NativeMacAppearances = NativeMacAppearances()
) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val collection = savedDrafts ?: remember { TaskDrafts() }
    val templateStore = savedTemplates ?: remember { TaskTemplates() }
    val templateState by templateStore.state.collectAsState()
    val activeId = draftId ?: remember { UUID.randomUUID().toString() }
    val editor = remember(collection, activeId, origin, templateStore) {
        val existing = collection.state.value[activeId]
        val templates = templateStore.state.value
        val template = templates.selected(existing?.templateId ?: existing?.let { TaskTemplate.builtInId(it.agent) })
        val suggested = templates.suggestedDirectory(template, origin, directories.firstOrNull())
        collection.begin(activeId, origin, macName, suggested).also { editor ->
            collection.edit(editor) { current ->
                val missingTemplate = existing?.templateId != null && templates.entries.none { it.id == existing.templateId }
                (if (existing == null) templates.restorePickers(current, directories.firstOrNull())
                    else current.selecting(template, if (!missingTemplate) existing.directory else suggested))
                    .let { if (missingTemplate) it.copy(lastRequest = null, completedRequest = null) else it }
            }
        }
    }
    val entries by collection.state.collectAsState()
    val initialDraft = remember(editor) { checkNotNull(collection.state.value[editor.id]) }
    val draft = entries[editor.id] ?: initialDraft
    val template = templateState.selected(draft.templateId)
    val command = draft.command
    val plainShell = command.isNullOrBlank()
    val prompt = draft.prompt
    val promptEditor = rememberSaveable(activeId, saver = TextFieldState.Saver) { TextFieldState(prompt, TextRange.Zero) }
    val promptScroll = rememberSaveable(activeId, saver = ScrollState.Saver) { ScrollState(0) }
    val promptBinding = remember(editor, promptEditor) { TaskComposerPromptBinding(promptEditor, prompt) }
    val promptInteractions = remember(promptEditor) { MutableInteractionSource() }
    val promptViewport = rememberSaveable(activeId, saver = TaskComposerPromptViewport.Saver) { TaskComposerPromptViewport() }
    val directory = draft.directory
    val selection = draft.selection
    val submission = remember(editor) { TaskSubmissionIdentity().apply {
        initialDraft.lastRequest?.let { submitted(initialDraft.lastRequestOrigin ?: origin, JSONObject(it)) }
    } }
    val connectionToken = remember(client, origin) { Any() }
    val latestConnectionToken by rememberUpdatedState(connectionToken)
    val currentContext by rememberUpdatedState(isCurrent)
    val createdCallback by rememberUpdatedState(onCreated)
    var busy by remember { mutableStateOf(false) }
    var submissionJob by remember(editor) { mutableStateOf<Job?>(null) }
    var submissionCommitted by remember(editor) { mutableStateOf(false) }
    var leavingPreparation by remember(editor) { mutableStateOf(false) }
    var error by remember(editor) { mutableStateOf(if (initialDraft.lastRequest != null && initialDraft.completedRequest == null)
        "Previous task status is unconfirmed. Check your workspace list before retrying." else null) }
    var submissionIssue by remember(editor) { mutableStateOf<TaskComposerFailure?>(
        TaskComposerFailure.restored.takeIf { initialDraft.lastRequest != null && initialDraft.completedRequest == null }) }
    var accepted by remember(editor) { mutableStateOf(false) }
    var dirty by remember(editor) { mutableStateOf(false) }
    var showDrafts by remember(editor) { mutableStateOf(false) }
    var confirmLeave by remember(editor) { mutableStateOf(false) }
    var agentMenu by remember(editor) { mutableStateOf(false) }
    var showTemplates by rememberSaveable(editor.id) { mutableStateOf(false) }
    var showOptions by rememberSaveable(editor.id) { mutableStateOf(false) }
    var showDirectory by rememberSaveable(editor.id) { mutableStateOf(false) }
    var confirmStartAgain by remember(editor) { mutableStateOf(false) }
    var recoveryReady by remember(editor, draft.completedRequest) { mutableStateOf(false) }
    val recovery = remember(editor, draft.completedRequest, draft.completedOrigin) {
        draft.completedRequest?.let { TaskCompletedRecovery(draft.completedOrigin ?: origin, it) }
    }
    val groupSelection = TaskGroupSelection(draft.groupId, workspaceGroups, supportsGroups, groupsLoaded)
    val latestGroups by rememberUpdatedState(groupSelection)
    val currentGroupCheck by rememberUpdatedState(groupIsCurrent)
    var preparingAttachments by remember(editor) { mutableStateOf(false) }
    val canEdit = !busy && !accepted && !preparingAttachments && !leavingPreparation
    val canLeave = !leavingPreparation && (!busy || (submissionJob?.isActive == true && !submissionCommitted))
    val currentCanEdit by rememberUpdatedState(canEdit)
    val currentHasSelectedMac by rememberUpdatedState(hasSelectedMac)
    val promptFocus = taskComposerInitialFocusModifier(activeId, canEdit,
        visible = !showOptions && !showDirectory && !showTemplates && !showDrafts && !confirmLeave && !confirmStartAgain,
        current = { currentContext() && collection.isCurrent(editor) })
    fun rememberPickerChoices() {
        if (currentHasSelectedMac && currentContext() && collection.isCurrent(editor)) {
            collection.state.value[editor.id]?.takeIf { it.origin == origin }?.let(templateStore::rememberPickers)
        }
    }
    LaunchedEffect(editor, draft.templateId, draft.selection, draft.defaultModel, draft.directory,
        draft.didEditDirectory, draft.groupId, hasSelectedMac) { rememberPickerChoices() }
    // A draft opened during the very first handshake initially has only the
    // pairing-code identity. Adopt the verified Mac once that handshake finishes.
    LaunchedEffect(editor, resolvedMacOrigin) {
        val resolved = resolvedMacOrigin ?: return@LaunchedEffect
        val select = selectMac ?: return@LaunchedEffect
        snapshotFlow { currentCanEdit }.first { it }
        if (!currentContext() || !collection.isCurrent(editor)) return@LaunchedEffect
        busy = true
        try { select(editor, resolved) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: "Could not select this Mac"
        } finally { busy = false }
    }
    fun edit(update: (TaskDraft) -> TaskDraft) {
        if (busy || accepted || preparingAttachments) return
        collection.editIfCurrent(editor, update) ?: return
        rememberPickerChoices()
        dirty = true
        error = null
    }
    fun syncPrompt() {
        if (!currentContext() || !collection.isCurrent(editor)) return
        promptBinding.sync(collection.state.value[editor.id]?.prompt) { text ->
            if (busy || accepted || preparingAttachments || leavingPreparation ||
                !currentContext() || !collection.isCurrent(editor)) false
            else collection.editIfCurrent(editor) { it.copy(prompt = text) }?.let {
                dirty = true; error = null; true
            } ?: false
        }
    }
    fun hidePromptKeyboard() {
        syncPrompt()
        val selectionBeforeBlur = promptEditor.selection
        focus.clearFocus()
        // Native Compose collapses selection on blur. Overlay controls must not
        // replace the user's selection when the editor returns.
        promptEditor.edit { this.selection = selectionBeforeBlur }
        keyboard?.hide()
    }
    SideEffect { syncPrompt(); promptViewport.editor(promptEditor.text.toString(), promptEditor.selection) }
    LaunchedEffect(promptBinding, editor) {
        snapshotFlow { promptEditor.text.toString() }.collect { syncPrompt() }
    }
    LaunchedEffect(promptViewport, promptInteractions, promptScroll) {
        promptInteractions.interactions.collect { interaction -> when (interaction) {
            is DragInteraction.Start -> promptViewport.beginDrag(promptScroll.value)
            is DragInteraction.Stop, is DragInteraction.Cancel -> {
                // Compose emits Stop before scheduling the fling. Keep the
                // user's ownership across that transition, then observe it.
                withFrameNanos { }
                promptViewport.endDrag(promptScroll.value, promptScroll.isScrollInProgress)
            }
        } }
    }
    LaunchedEffect(promptViewport, promptEditor, promptScroll) {
        snapshotFlow {
            // Observe text and selection before interpreting a scroll delta:
            // typing/caret movement must release a prior manual viewport.
            listOf(promptEditor.text.toString(), promptEditor.selection, promptScroll.value,
                promptScroll.maxValue, promptScroll.isScrollInProgress, promptViewport.manualOffset, promptViewport.tracking)
        }.collect {
            promptViewport.editor(promptEditor.text.toString(), promptEditor.selection)
            promptViewport.scroll(promptScroll.value, promptScroll.isScrollInProgress)
            if (!promptScroll.isScrollInProgress) promptViewport.target(promptScroll.maxValue)?.let { target ->
                if (target != promptScroll.value) promptScroll.dispatchRawDelta((target - promptScroll.value).toFloat())
            }
        }
    }
    fun selectTemplate(selected: TaskTemplate) {
        edit { it.selecting(selected, templateStore.state.value.suggestedDirectory(selected, origin, directories.firstOrNull())) }
    }
    fun leave() {
        // Recheck live state too: a tap can arrive before the disabled button is
        // recomposed at the preparation/creation boundary.
        if (leavingPreparation || (busy && (submissionJob?.isActive != true || submissionCommitted))) return
        fun presentLeave() {
            hidePromptKeyboard()
            if (dirty && !accepted) confirmLeave = true else onBack()
        }
        if (!busy) { presentLeave(); return }
        // Uploads and durable draft preparation can be stopped. Once creation is
        // admitted, keep dismissal locked until its result/uncertainty is known.
        val preparing = submissionJob ?: return
        leavingPreparation = true
        preparing.cancel()
        scope.launch {
            try {
                preparing.join()
                if (currentContext() && collection.isCurrent(editor)) presentLeave()
            } finally { leavingPreparation = false }
        }
    }
    fun saveThen(action: () -> Unit) {
        if (busy) return
        syncPrompt()
        busy = true; error = null
        scope.launch {
            try {
                rememberPickerChoices()
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
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            syncPrompt(); rememberPickerChoices(); currentFlush()
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); syncPrompt(); rememberPickerChoices(); collection.end(editor); currentFlush() }
    }
    val provider = command?.let(TaskAgentCommand::detect)
    val modelKey = provider?.let { TaskModelRepository.Key(origin, it) }
    var modelResult by remember(modelKey, client) { mutableStateOf(modelKey?.let { models.cached(it, client?.compatibilityWire) }
        ?: draft.restoredModels().takeIf { it.usable }) }
    var modelMenu by remember(modelKey) { mutableStateOf<List<TaskModel>?>(null) }
    var effortMenu by remember(modelKey) { mutableStateOf<List<TaskEffort>?>(null) }
    var loading by remember(modelKey) { mutableStateOf(false) }
    // Discovery is not an edit. Keep the submitted metadata while its accepted
    // operation is recoverable; changing a picker explicitly replaces the snapshot.
    val restoredModels = draft.restoredModels()
    val restoredEffort = selection.effective(restoredModels)?.efforts?.firstOrNull { it.id == selection.effortId }?.id
    val restoredCommand = command?.let { command ->
        TaskAgentCommand.detect(command)?.apply(command, selection.explicit?.id, restoredEffort) ?: command
    }
    val holdRecoveryModels = recovery != null && recovery.origin == origin && (recovery.parameters().opt("initial_command") as? String) == restoredCommand
    val selectionResult = if (holdRecoveryModels) restoredModels else modelResult
    val selectedModel = selection.model(selectionResult)
    val effortModel = selection.effective(selectionResult)
    val efforts = effortModel?.efforts.orEmpty()
    val selectedEffort = efforts.firstOrNull { it.id == selection.effortId }
    val effectiveRequest = runCatching {
        TaskAttachments.snapshot(TaskCommand.parameters(command, prompt, directory, UUID.randomUUID(), selection.explicit?.id, selectedEffort?.id, draft.workspaceName, draft.groupId), draft.attachments)
    }.getOrNull()
    val recoveryApplies = recovery?.appliesTo(origin, effectiveRequest) == true
    fun launchTask(reconcile: Boolean = false, startAgain: Boolean = false) {
        if (busy || accepted || preparingAttachments || leavingPreparation || supportsTaskCreation == false || !hasSelectedMac) return
        syncPrompt()
        val requestDraft = collection.state.value[editor.id] ?: return
        if (!latestGroups.copy(id = requestDraft.groupId).valid) return
        val requestModels = requestDraft.restoredModels()
        val requestEffort = requestDraft.selection.effective(requestModels)?.efforts
            ?.firstOrNull { it.id == requestDraft.selection.effortId }?.id
        val requestSnapshot = runCatching { TaskAttachments.snapshot(TaskCommand.parameters(requestDraft.command,
            requestDraft.prompt, requestDraft.directory, UUID.randomUUID(), requestDraft.selection.explicit?.id,
            requestEffort, requestDraft.workspaceName, requestDraft.groupId), requestDraft.attachments) }.getOrNull()
        val requestRecovery = requestDraft.completedRequest?.let { TaskCompletedRecovery(requestDraft.completedOrigin ?: origin, it) }
        val requestRecoveryApplies = requestRecovery?.appliesTo(origin, requestSnapshot) == true
        if (reconcile && !requestRecoveryApplies) return
        if (!reconcile && requestRecoveryApplies && !(startAgain && recoveryReady)) return
        val requestConnectionToken = connectionToken
        fun requestIsCurrent() = requestConnectionToken === latestConnectionToken && currentContext() && collection.isCurrent(editor)
        val parameters = runCatching {
            check(requestIsCurrent()) { "Connection changed. Reconnect to this Mac before creating the task" }
            if (reconcile) checkNotNull(requestRecovery).parameters()
            else submission.resolve(origin, checkNotNull(requestSnapshot) { "Enter a task prompt" })
        }.getOrElse { error = it.message; return }
        busy = true; error = null; submissionIssue = null
        hidePromptKeyboard()
        submissionJob = scope.launch {
            var transmitted = false
            var stage = TaskComposerFailure.Stage.SAVING
            try {
                check(requestIsCurrent()) { "Connection changed" }
                // An explicit offline attempt saves local edits, but has not sent an
                // operation and must not create an uncertain-submission snapshot.
                if (client == null) {
                    persistDrafts()
                    currentCoroutineContext().ensureActive()
                    if (requestIsCurrent()) error = "That Mac is not connected. Open cmux on the Mac, then try again."
                    return@launch
                }
                if (!reconcile) {
                    submission.submitted(origin, parameters)
                    collection.edit(editor) { it.copy(lastRequest = parameters.toString(), lastRequestOrigin = origin, completedRequest = null, completedOrigin = null) }
                }
                persistDrafts()
                currentCoroutineContext().ensureActive()
                check(requestIsCurrent()) { "Task session changed before submission" }
                if (reconcile) {
                    stage = TaskComposerFailure.Stage.REFRESHING
                    refreshWorkspaces()
                    currentCoroutineContext().ensureActive()
                    check(requestIsCurrent()) { "Connection changed while refreshing workspaces" }
                }
                stage = TaskComposerFailure.Stage.VALIDATING_GROUP
                check(currentGroupCheck?.invoke(parameters.opt("group_id") as? String) ?: latestGroups.copy(id = parameters.opt("group_id") as? String).valid) {
                    "The selected group is no longer available. Choose another group or None."
                }
                stage = TaskComposerFailure.Stage.UPLOADING
                val wire = TaskAttachments.prepareRequest(client, parameters, requestDraft.attachments, supportsAttachments, reconcile,
                    read = { attachment -> checkNotNull(attachmentRepository) { "Attachment storage is unavailable" }.readAttachment(attachment) },
                    checkCurrent = {
                        check(requestIsCurrent()) { "Connection changed during attachment upload" }
                        check(currentGroupCheck?.invoke(parameters.opt("group_id") as? String) ?: latestGroups.copy(id = parameters.opt("group_id") as? String).valid) { "The selected group is no longer available." }
                    })
                currentCoroutineContext().ensureActive()
                check(requestIsCurrent()) { "Task session changed before creation" }
                transmitted = true
                submissionCommitted = true
                stage = TaskComposerFailure.Stage.CREATING
                val response = createTask(wire)
                currentCoroutineContext().ensureActive()
                check(requestIsCurrent()) { "Connection changed before the task could be opened" }
                stage = TaskComposerFailure.Stage.READING_RESULT
                TaskCreationResult.parse(response)
                rememberPickerChoices()
                templateStore.recordSuccess(checkNotNull(requestDraft.templateId), origin, parameters.optString("working_directory").takeIf { it.isNotBlank() })
                collection.remove(editor)
                flushDrafts()
                accepted = true
                createdCallback(response)
            } catch (failure: Exception) {
                if (failure is CancellationException) {
                    currentCoroutineContext().ensureActive()
                    if (failure !is TimeoutCancellationException) throw failure
                }
                // An old client must never install a recovery gate into a replacement session.
                if (!requestIsCurrent()) {
                    if (collection.isCurrent(editor)) error = "Connection changed before the task could be opened."
                    return@launch
                }
                if (transmitted && failure is MobileRpcException && failure.code?.trim()?.lowercase() == "already_completed") {
                    if (reconcile) recoveryReady = true
                    else {
                        val fresh = submission.retire(origin, parameters)
                        collection.edit(editor) { it.copy(lastRequest = fresh.toString(), lastRequestOrigin = origin, completedRequest = parameters.toString(), completedOrigin = origin) }
                    }
                    try { persistDrafts() }
                    catch (saveFailure: Exception) {
                        if (saveFailure is CancellationException) throw saveFailure
                        error = "Could not save task recovery. Refresh before starting another task."
                    }
                } else {
                    submissionIssue = TaskComposerFailure.from(failure, stage)
                    error = submissionIssue?.message
                }
            } finally {
                if (!transmitted && !currentCoroutineContext().isActive && requestIsCurrent()) {
                    // Cancelled preparation did not create a workspace. Restore
                    // the prior retry/recovery anchor (which may itself be
                    // uncertain), without dropping prompt or attachment edits.
                    collection.editIfCurrent(editor) { it.copy(
                        lastRequest = requestDraft.lastRequest, lastRequestOrigin = requestDraft.lastRequestOrigin,
                        completedRequest = requestDraft.completedRequest, completedOrigin = requestDraft.completedOrigin
                    ) }
                    submission.restore(requestDraft.lastRequestOrigin ?: origin, requestDraft.lastRequest?.let(::JSONObject))
                    flushDrafts()
                }
                submissionCommitted = false
                busy = false
            }
        }
    }
    LaunchedEffect(client, modelKey) {
        if (modelKey == null || client == null) { loading = false; return@LaunchedEffect }
        loading = true
        try {
            var attempt = 0
            do {
                val retry = models.refreshShared(modelKey, client.compatibilityWire, scope,
                    host = { TaskModelParser.host(client.request("mobile.task.models.list",
                        JSONObject().put("provider", modelKey.provider.wireName))) },
                    catalog = { catalog(modelKey.provider) }, update = { modelResult = it })
                if (!retry) break
                delay(TaskModelRepository.retryDelay(attempt++))
            } while (true)
        } finally { loading = false }
    }
    LaunchedEffect(editor, modelKey, modelResult, busy, accepted, selection.explicit, holdRecoveryModels) {
        if (!busy && !accepted && !holdRecoveryModels) collection.editIfCurrent(editor) {
            it.reconcileModels(modelKey?.provider, modelResult)
        }
    }
    LaunchedEffect(editor, templateState.entries, busy, accepted) {
        if (!busy && !accepted) collection.editIfCurrent(editor) { current ->
            val selected = templateState.selected(current.templateId)
            if (current.templateId == selected.id && current.templateName == selected.name && current.command == selected.command) current
            else current.selecting(selected, templateState.suggestedDirectory(selected, origin, directories.firstOrNull()))
        }
    }
    BackHandler { leave() }
    if (showDirectory) {
        val candidates = taskDirectoryCandidates(template, templateState, origin, directoryWorkspaces, selectedWorkspaceId) +
            if (directoryWorkspaces.isEmpty()) directories.map { TaskDirectoryCandidate(it, TaskDirectorySource.OPEN_WORKSPACE) } else emptyList()
        TaskDirectoryPickerView(client, origin, directory, candidates,
            isCurrent = { currentContext() && collection.isCurrent(editor) && !busy && !accepted },
            onSelect = { path -> edit { it.copy(directory = path, didEditDirectory = true) }; showDirectory = false },
            onDismiss = { showDirectory = false })
        return
    }
    if (showOptions) {
        TaskOptionsView(draft, macs, groupSelection, canEdit, error, computerName = appearances::name,
            onName = { name -> edit { it.copy(workspaceName = name) } },
            onMac = { next ->
                if (next != origin && selectMac != null && canEdit) {
                    hidePromptKeyboard(); busy = true; error = null
                    scope.launch {
                        try { check(currentContext()); selectMac(editor, next) }
                        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message ?: "Could not select this Mac" }
                        finally { busy = false }
                    }
                }
            }, onGroup = { group -> edit { it.copy(groupId = group) } },
            onDirectory = { hidePromptKeyboard(); showDirectory = true },
            onRefreshGroups = {
                if (canEdit) { busy = true; scope.launch {
                    try { refreshWorkspaces(); error = null }
                    catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }
                    finally { busy = false }
                } }
            }, onDismiss = { hidePromptKeyboard(); showOptions = false })
        return
    }
    if (showTemplates) {
        TaskTemplatesView(templateState, onDismiss = { showTemplates = false },
            onChange = { change ->
                check(collection.isCurrent(editor)) { "Task session changed" }
                if (persistTemplateChange != null) persistTemplateChange(change) else templateStore.apply(change)
                currentCoroutineContext().ensureActive()
                check(collection.isCurrent(editor)) { "Task session changed" }
            }, onSaved = { saved, adding ->
                val current = templateStore.state.value.entries.first { it.id == saved.id }
                if (adding || draft.templateId == saved.id) selectTemplate(current)
            }, onDeleted = { deleted ->
                if (draft.templateId == deleted) selectTemplate(templateStore.state.value.entries.first())
            })
        return
    }
    if (confirmStartAgain) AlertDialog(onDismissRequest = { confirmStartAgain = false },
        title = { Text("Start this task again?") },
        text = { Text("Only continue if the task is not present. Starting again may create a duplicate.") },
        confirmButton = { TextButton(enabled = canEdit && recoveryApplies && recoveryReady, onClick = {
            confirmStartAgain = false; launchTask(startAgain = true)
        }) { Text("Start Again", color = Color(0xFFFF9999)) } },
        dismissButton = { TextButton(onClick = { confirmStartAgain = false }) { Text("Cancel") } })
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

    val modelError = when (modelResult?.error) {
        TaskModelError.PROVIDER_UNAVAILABLE -> "${template.name} unavailable"
        TaskModelError.QUERY_FAILED -> "Couldn’t load models"
        TaskModelError.HOST_UNAVAILABLE -> "Mac unavailable".takeUnless { modelResult?.usable == true }
        null -> null
    }
    val loadingModels = loading && modelResult?.usable != true
    val layout: @Composable (@Composable () -> Unit, @Composable () -> Unit, (TerminalPasteContent) -> Boolean) -> Unit = { attachmentStrip, attachmentPicker, receiveAttachment ->
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0C0E))) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { leave() }, enabled = canLeave) {
                    Icon(painterResource(R.drawable.ic_task_back), "Back to workspaces", Modifier.size(22.dp))
                }
                Text(draft.workspaceName.trim().ifEmpty { directory.trim().takeIf { it.isNotEmpty() }?.let(TaskDirectoryPaths::name) ?: "New Task" },
                    Modifier.weight(1f).semantics { contentDescription = "Task title" }, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { hidePromptKeyboard(); showDrafts = true }, enabled = canEdit) {
                    Icon(painterResource(R.drawable.ic_task_drafts), "Drafts", Modifier.size(22.dp))
                }
            }
            RichContentEditor(owner = editor to origin,
                enabled = canEdit && !plainShell && supportsAttachments && attachmentRepository != null,
                onContent = { syncPrompt(); receiveAttachment(it) },
                onError = { syncPrompt(); error = it }, truncateAttachmentPaste = true) { pasteModifier ->
                BasicTextField(state = promptEditor,
                    modifier = Modifier.weight(1f).then(promptFocus).then(pasteModifier)
                    .onGloballyPositioned {
                        // Equivalent to iOS's post-layout hook: caret layout may
                        // run after a keyboard/window change. Do not fight a drag.
                        promptViewport.editor(promptEditor.text.toString(), promptEditor.selection)
                        if (!promptScroll.isScrollInProgress) promptViewport.target(promptScroll.maxValue)?.let { target ->
                            if (target != promptScroll.value) promptScroll.dispatchRawDelta((target - promptScroll.value).toFloat())
                        }
                    }
                    .fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp).semantics {
                        contentDescription = if (plainShell) "Workspace title (optional)" else "Task prompt"
                        this[TaskPromptScrolling] = promptScroll.isScrollInProgress || promptViewport.tracking
                    }, enabled = canEdit,
                    inputTransformation = InputTransformation {
                        if (busy || accepted || preparingAttachments || leavingPreparation ||
                            !currentContext() || !collection.isCurrent(editor)) revertAllChanges()
                    }, textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, color = LocalContentColor.current),
                    interactionSource = promptInteractions, scrollState = promptScroll,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorator = TextFieldDecorator { inner -> TextFieldDefaults.DecorationBox(
                        value = promptEditor.text.toString(), innerTextField = inner,
                        enabled = canEdit, singleLine = false, visualTransformation = VisualTransformation.None,
                        interactionSource = promptInteractions,
                        placeholder = { Text(if (plainShell) "Workspace title (optional)" else directory.trim().takeIf { it.isNotEmpty() }
                        ?.let { "Describe a coding task in ${TaskDirectoryPaths.name(it)}" } ?: "Describe a coding task",
                        color = Color(0xFF64676E), fontSize = 20.sp) },
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent)) })
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (client == null) Text(if (hasSelectedMac) "That Mac is not connected. Open cmux on the Mac to start this task."
                    else "Pair a Mac to start this task. You can save your draft now.",
                    color = Color(0xFF9B9FA8), style = MaterialTheme.typography.bodySmall)
                if (supportsTaskCreation == false) Text("Pair this Mac again or update cmux to create tasks.",
                    color = Color(0xFFFF9999), modifier = Modifier.padding(bottom = 12.dp))
                if (recoveryApplies) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        TaskComposerFailureBanner(TaskComposerFailure(
                            if (error == null) TaskComposerFailure.ACCEPTED else
                                submissionIssue?.takeIf { it.message == error }?.title ?: TaskComposerFailure.UNCONFIRMED,
                            error ?: if (recoveryReady) TaskCompletedRecovery.MISSING_MESSAGE else TaskCompletedRecovery.REFRESH_MESSAGE))
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { launchTask(reconcile = true) }, enabled = canEdit && hasSelectedMac && supportsTaskCreation != false,
                                modifier = Modifier.weight(1f)) { Text(if (recoveryReady) "Refresh Again" else "Refresh Workspaces") }
                            if (recoveryReady) OutlinedButton(onClick = { confirmStartAgain = true }, enabled = canEdit,
                                modifier = Modifier.weight(1f)) { Text("Start Again") }
                        }
                    }
                } else if (error != null) TaskComposerFailureBanner(
                    submissionIssue?.takeIf { it.message == error } ?: TaskComposerFailure(TaskComposerFailure.FAILED, error.orEmpty()))
                if (!groupSelection.valid) Text(if (groupSelection.pending) "Loading the selected Mac’s groups…" else "The selected group is unavailable. Open Task Options to choose another group or None.",
                    color = Color(0xFFFF9999), modifier = Modifier.padding(bottom = 12.dp))

                if (preparingAttachments) Text("Preparing attachments…", color = Color(0xFF9B9FA8), style = MaterialTheme.typography.bodySmall)
                if (client != null && !plainShell && draft.attachments.isNotEmpty() && !supportsAttachments)
                    Text("This Mac does not support task attachments. Update cmux or remove the attachments.", color = Color(0xFFFF9999))
                attachmentStrip()
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    TaskComposerCircle("Task Options", R.drawable.ic_task_options, canEdit,
                        onClick = { hidePromptKeyboard(); showOptions = true })
                    attachmentPicker()
                    TaskComposerPillScroller(Modifier.weight(1f)) {
                        Box {
                            TaskComposerPill(onClick = { agentMenu = true }, enabled = canEdit,
                                modifier = Modifier.semantics { contentDescription = "Agent"; stateDescription = template.name }) {
                                TaskTemplateIcon(template.icon, Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("${template.name} ▾", maxLines = 1)
                            }
                            DropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                                templateState.entries.forEach { option ->
                                    DropdownMenuItem(text = { Text(option.name) }, leadingIcon = { TaskTemplateIcon(option.icon) },
                                        trailingIcon = { if (draft.templateId == option.id) Text("✓") },
                                        onClick = { selectTemplate(option); agentMenu = false })
                                }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Edit Agents") }, onClick = {
                                    agentMenu = false; hidePromptKeyboard(); showTemplates = true
                                })
                            }
                        }
                        if (provider != null) {
                                Box {
                                    TaskComposerPill(onClick = {
                                        val current = modelResult?.models.orEmpty()
                                        modelMenu = if (selectedModel != null && current.none { it.id == selectedModel.id }) current + selectedModel else current
                                    }, enabled = canEdit && !loadingModels, modifier = Modifier.semantics {
                                        contentDescription = "Model"; stateDescription = selectedModel?.name ?: "Default"
                                    }) {
                                        if (loadingModels) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                        if (!loadingModels) Icon(painterResource(R.drawable.ic_task_model), null, Modifier.size(16.dp).padding(end = 3.dp))
                                        Text(if (loadingModels) "Loading models…" else modelError ?: "${selectedModel?.name ?: "Default"} ▾",
                                            color = if (modelError != null) Color(0xFFFF9999) else Color.Unspecified, maxLines = 1)
                                    }
                                    DropdownMenu(expanded = modelMenu != null, onDismissRequest = { modelMenu = null }) {
                                        DropdownMenuItem(text = { Text("Default") }, onClick = {
                                            edit { it.copy(selection = selection.choose(null, modelResult), defaultModel = modelResult?.defaultModel) }
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
                                if (efforts.isNotEmpty()) Box {
                                    TaskComposerPill(onClick = { effortMenu = efforts.toList() }, enabled = canEdit && efforts.isNotEmpty(),
                                        modifier = Modifier.semantics {
                                            contentDescription = "Effort"; stateDescription = selectedEffort?.name ?: "Default"
                                        }) {
                                        Icon(painterResource(R.drawable.ic_task_effort), null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("${selectedEffort?.name ?: "Effort"} ▾", maxLines = 1)
                                    }
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

                    }
                    TaskComposerCircle(if (accepted) "Task Created" else if (busy) "Creating…" else "Create Task", R.drawable.ic_task_submit,
                        canEdit && !recoveryApplies && hasSelectedMac && supportsTaskCreation != false && groupSelection.valid &&
                            (client == null || plainShell || draft.attachments.isEmpty() || supportsAttachments) && (plainShell || prompt.isNotBlank()),
                        accent = true, busy = busy, onClick = { launchTask() })
                }
            }
        }
    }
    if (attachmentRepository != null) TaskAttachmentControls(attachmentRepository, editor, origin,
        draft.attachments, canEdit, canAdd = !plainShell && supportsAttachments,
        isCurrent = { currentContext() && collection.isCurrent(editor) && !busy && !accepted && !plainShell && supportsAttachments },
        canPreview = { currentContext() && collection.isCurrent(editor) },
        beforeOverlay = ::hidePromptKeyboard,
        onPreparing = { if (it) syncPrompt(); preparingAttachments = it },
        onChanged = { dirty = true; error = null }, onError = { syncPrompt(); error = it }, content = layout)
    else layout({}, {}, { false })
}
