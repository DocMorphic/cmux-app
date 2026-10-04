package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class RoutedSidebarUi(val query: RoutedSidebarQuery = RoutedSidebarQuery(),
    val search: NativeSearchState = NativeSearchState(), val snapshot: RoutedSidebarSnapshot? = null,
    val loading: Boolean = false, val more: Boolean = false, val error: String? = null, val navigating: Boolean = false,
    val actionError: String? = null, val saving: Boolean = false, val orderGeneration: Int = 0,
    val notificationBusy: Boolean = false, val mutationBusy: Boolean = false,
    val editor: RoutedSidebarCustomization? = null, val editorLoading: Boolean = false)

/** The browser reads a bounded display projection; only the host resolves destinations. */
internal class RoutedSidebarController(private val scope: CoroutineScope,
    private val publishVisibility: suspend (Boolean) -> Unit,
    private val read: suspend (RoutedSidebarQuery, String?, Int) -> RoutedSidebarPage,
    private val select: suspend (String) -> String,
    private val saveSort: suspend (RoutedSidebarSort) -> Unit = { error("Sidebar sorting is unavailable") },
    private val notificationAction: suspend (RoutedSidebarNotification) -> Unit = { error("Notification actions are unavailable") },
    private val workspaceAction: suspend (RoutedSidebarMutation) -> Unit = { error("Workspace actions are unavailable") },
    private val readGroupMenu: suspend (String, String?, Int) -> RoutedSidebarGroupPage = { _, _, _ -> error("Group moves are unavailable") },
    private val readEditor: suspend (String) -> RoutedSidebarCustomization = { error("Workspace customization is unavailable") },
    private val closeEditor: suspend (String) -> Unit = {},
    private val customizeWorkspace: suspend (RoutedSidebarCustomizationSave) -> WorkspaceCustomizationResult = { error("Workspace customization is unavailable") },
    private val readChanges: suspend (String) -> String = { error("Changes are unavailable") },
    private val dropWorkspace: suspend (RoutedSidebarDrop) -> Unit = { error("Workspace ordering is unavailable") }) {
    private val sortMutex = Mutex()
    private val mutable = MutableStateFlow(RoutedSidebarUi())
    val state = mutable.asStateFlow()
    private var available = false
    private var foreground = false
    private var visible = false
    private var initialized = false
    private var window = 100
    private var job: Job? = null
    private val searchScope get() = if (state.value.query.notifications) NativeSearchScope.NOTIFICATIONS else NativeSearchScope.WORKSPACES
    fun initialize(query: RoutedSidebarQuery) {
        if (initialized) return
        initialized = true
        mutable.value = state.value.copy(query = query, search = NativeSearchState(
            workspaceQuery = query.workspaceQuery, notificationQuery = query.notificationQuery))
        restart()
    }
    fun configure(ready: Boolean, active: Boolean) {
        if (available == ready && foreground == active) return
        available = ready; foreground = active; restart()
        if (!ready) dismissEditor()
    }
    fun visible(value: Boolean) { if (visible != value) { visible = value; restart() } }
    fun query(value: RoutedSidebarQuery) {
        if (state.value.query == value) return
        mutable.value = state.value.copy(query = value, snapshot = null, more = false, error = null, actionError = null)
        window = 100; restart()
    }
    fun tab(notifications: Boolean) {
        val search = state.value.search.commit()
        mutable.value = state.value.copy(search = search)
        query(state.value.query.copy(notifications = notifications, workspaceQuery = search.workspaceQuery, notificationQuery = search.notificationQuery))
    }
    fun beginSearch() { mutable.value = state.value.copy(search = state.value.search.begin(searchScope)) }
    fun edit(value: String, generation: Long) {
        val search = state.value.search.edit(value, searchScope, generation)
        mutable.value = state.value.copy(search = search)
        query(state.value.query.withText(search.text(searchScope)))
    }
    fun finishSearch(cancel: Boolean = false) {
        val search = if (cancel) state.value.search.clear(searchScope) else state.value.search.commit()
        mutable.value = state.value.copy(search = search)
        query(state.value.query.withText(search.text(searchScope)))
    }
    fun more() { window = (window + 100).coerceAtMost(RoutedSidebarWire.MAX_ROWS); restart() }
    fun retry() { mutable.value = state.value.copy(actionError = null); restart() }
    private fun editorVisible() = available && foreground && (visible || state.value.editor != null) && !state.value.query.notifications
    suspend fun editWorkspace(key: String) {
        if (!editorVisible() || state.value.mutationBusy || state.value.editorLoading || state.value.editor != null ||
            state.value.snapshot?.rows?.any { it.key == key && it.canCustomize } != true) return
        mutable.value = state.value.copy(editorLoading = true, actionError = null)
        try {
            val editor = readEditor(key)
            check(editor.key == key) { "Workspace editor changed. Try again." }
            currentCoroutineContext().ensureActive()
            if (!editorVisible()) { closeEditor(editor.ticket); return }
            mutable.value = state.value.copy(editor = editor)
            restart()
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            mutable.value = state.value.copy(actionError = failure.message ?: "Could not open workspace customization")
        } finally { mutable.value = state.value.copy(editorLoading = false) }
    }
    fun dismissEditor() {
        val editor = state.value.editor ?: return
        mutable.value = state.value.copy(editor = null)
        scope.launch { runCatching { closeEditor(editor.ticket) } }
        restart()
    }
    suspend fun customize(editor: RoutedSidebarCustomization, baseline: WorkspaceCustomizationDraft,
        submitted: WorkspaceCustomizationDraft): WorkspaceCustomizationResult {
        check(editorVisible() && state.value.editor?.ticket == editor.ticket) { "Workspace editor is no longer active." }
        check(!state.value.mutationBusy) { "A sidebar update is already in progress" }
        mutable.value = state.value.copy(mutationBusy = true)
        try {
            val result = customizeWorkspace(RoutedSidebarCustomizationSave(editor.key, editor.ticket, baseline, submitted))
            restart()
            return result
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            restart()
            return WorkspaceCustomizationResult(false, message = if (failure is TimeoutCancellationException)
                "Workspace save wasn't confirmed. Reopen Customize to review its current values."
                else failure.message ?: "Could not save this workspace.")
        } finally { mutable.value = state.value.copy(mutationBusy = false) }
    }
    suspend fun groupMenu(key: String): RoutedSidebarGroupPage {
        fun current() = available && foreground && visible && !state.value.query.notifications && !state.value.mutationBusy &&
            state.value.snapshot?.rows?.any { it.key == key && RoutedSidebarMutationKind.MOVE_TO_GROUP in it.mutations } == true
        check(current()) { "Group menu is no longer available." }
        val first = readGroupMenu(key, null, 0)
        check(first.offset == 0)
        var page = first
        val choices = first.choices.toMutableList()
        while (page.next != null) {
            currentCoroutineContext().ensureActive()
            check(current()) { "Group menu is no longer available." }
            val next = readGroupMenu(key, first.revision, checkNotNull(page.next))
            check(next.revision == first.revision && next.offset == page.next && next.total == first.total && next.canRemove == first.canRemove)
            choices += next.choices
            page = next
        }
        check(current() && choices.size == first.total && choices.map { it.key }.distinct().size == choices.size)
        return first.copy(next = null, choices = choices)
    }
    suspend fun sort(command: RoutedSidebarSort): Boolean = sortMutex.withLock {
        if (!available || !foreground || !visible || state.value.query.notifications || state.value.query.computer != null) return@withLock false
        mutable.value = state.value.copy(saving = true, actionError = null)
        try {
            saveSort(command)
            // Retain the list while awaiting the authoritative order/mode update.
            restart()
            true
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            mutable.value = state.value.copy(actionError = failure.message ?: "Could not save computer order",
                orderGeneration = state.value.orderGeneration + 1)
            restart()
            false
        } finally { mutable.value = state.value.copy(saving = false) }
    }
    suspend fun notification(command: RoutedSidebarNotification): Boolean {
        if (state.value.notificationBusy || !available || !foreground || !visible || !state.value.query.notifications) return false
        mutable.value = state.value.copy(notificationBusy = true, actionError = null)
        try {
            notificationAction(command)
            restart()
            return true
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            mutable.value = state.value.copy(actionError = if (failure is TimeoutCancellationException)
                "Notification update wasn't confirmed. Refresh before trying again."
                else failure.message ?: "Could not update notifications")
            restart()
            return false
        } finally { mutable.value = state.value.copy(notificationBusy = false) }
    }
    suspend fun mutate(command: RoutedSidebarMutation): Boolean {
        if (state.value.mutationBusy || !available || !foreground || !visible || state.value.query.notifications) return false
        mutable.value = state.value.copy(mutationBusy = true, actionError = null)
        try {
            workspaceAction(command)
            restart()
            return true
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            mutable.value = state.value.copy(actionError = if (failure is TimeoutCancellationException)
                "Workspace update wasn't confirmed. Refresh before trying again."
                else failure.message ?: "Could not update workspace")
            restart()
            return false
        } finally { mutable.value = state.value.copy(mutationBusy = false) }
    }
    suspend fun drop(command: RoutedSidebarDrop): Boolean {
        if (!available || !foreground || !visible || state.value.query.notifications || state.value.snapshot?.dragRevision != command.revision) return false
        mutable.value = state.value.copy(actionError = null)
        return try { dropWorkspace(command); restart(); true }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            mutable.value = state.value.copy(actionError = if (failure is TimeoutCancellationException)
                "Workspace move wasn't confirmed. Refresh before trying again." else failure.message ?: "Could not move workspace")
            restart(); false
        }
    }
    private fun restart() {
        job?.cancel()
        job = scope.launch {
            // A modal editor remains active when rotation/resize hides the sidebar.
            val active = available && foreground && (visible || state.value.editor != null)
            try { publishVisibility(active) }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (active) mutable.value = state.value.copy(error = failure.message, loading = false)
                return@launch
            }
            if (!active) { mutable.value = state.value.copy(loading = false); return@launch }
            while (isActive) {
                val query = state.value.query
                mutable.value = state.value.copy(loading = state.value.snapshot == null)
                try {
                    val first = read(query, null, 0)
                    require(first.offset == 0)
                    var last = first
                    val rows = first.snapshot.rows.toMutableList()
                    while (last.next != null && rows.size < window) {
                        val page = read(query, first.revision, checkNotNull(last.next))
                        require(page.revision == first.revision && page.offset == rows.size && page.total == first.total)
                        require(page.snapshot.copy(rows = emptyList()) == first.snapshot.copy(rows = emptyList()))
                        rows += page.snapshot.rows; last = page
                    }
                    require(rows.map { it.key }.distinct().size == rows.size)
                    currentCoroutineContext().ensureActive()
                    val editor = state.value.editor
                    if (editor != null && first.snapshot.editorTicket != editor.ticket) {
                        dismissEditor()
                        currentCoroutineContext().ensureActive()
                    }
                    mutable.value = state.value.copy(snapshot = first.snapshot.copy(rows = rows), more = last.next != null,
                        query = query.copy(machines = first.snapshot.selectedMachines, expanded = first.snapshot.expanded), loading = false, error = null)
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    mutable.value = state.value.copy(error = failure.message ?: "Could not load sidebar", loading = false)
                }
                delay(1_500)
            }
        }
    }
    suspend fun previewChanges(key: String): String? {
        if (!editorVisible() || state.value.editorLoading || state.value.snapshot?.rows?.any { it.key == key && it.changes != null } != true) return null
        mutable.value = state.value.copy(editorLoading = true, actionError = null)
        return try { readChanges(key).takeIf { editorVisible() } }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            mutable.value = state.value.copy(actionError = failure.message ?: "Could not open changes")
            null
        } finally { mutable.value = state.value.copy(editorLoading = false) }
    }
    suspend fun open(key: String): String? {
        if (state.value.navigating || !available || !foreground || !visible) return null
        mutable.value = state.value.copy(navigating = true, actionError = null)
        return try { select(key) }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            mutable.value = state.value.copy(actionError = failure.message ?: "Destination changed", navigating = false)
            null
        }
    }
}
