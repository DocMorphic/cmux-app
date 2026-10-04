package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RoutedSidebarUi(val query: RoutedSidebarQuery = RoutedSidebarQuery(),
    val search: NativeSearchState = NativeSearchState(), val snapshot: RoutedSidebarSnapshot? = null,
    val loading: Boolean = false, val more: Boolean = false, val error: String? = null, val navigating: Boolean = false)

/** The browser reads a bounded display projection; only the host resolves destinations. */
internal class RoutedSidebarController(private val scope: CoroutineScope,
    private val publishVisibility: suspend (Boolean) -> Unit,
    private val read: suspend (RoutedSidebarQuery, String?, Int) -> RoutedSidebarPage,
    private val select: suspend (String) -> String) {
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
            workspaceQuery = if (!query.notifications) query.text else "",
            notificationQuery = if (query.notifications) query.text else ""))
        restart()
    }
    fun configure(ready: Boolean, active: Boolean) {
        if (available == ready && foreground == active) return
        available = ready; foreground = active; restart()
    }
    fun visible(value: Boolean) { if (visible != value) { visible = value; restart() } }
    fun query(value: RoutedSidebarQuery) {
        if (state.value.query == value) return
        mutable.value = state.value.copy(query = value, snapshot = null, error = null)
        window = 100; restart()
    }
    fun tab(notifications: Boolean) {
        val search = state.value.search.commit()
        mutable.value = state.value.copy(search = search)
        query(state.value.query.copy(notifications = notifications,
            text = search.text(if (notifications) NativeSearchScope.NOTIFICATIONS else NativeSearchScope.WORKSPACES)))
    }
    fun beginSearch() { mutable.value = state.value.copy(search = state.value.search.begin(searchScope)) }
    fun edit(value: String, generation: Long) {
        val search = state.value.search.edit(value, searchScope, generation)
        mutable.value = state.value.copy(search = search)
        query(state.value.query.copy(text = search.text(searchScope)))
    }
    fun finishSearch(cancel: Boolean = false) {
        val search = if (cancel) state.value.search.clear(searchScope) else state.value.search.commit()
        mutable.value = state.value.copy(search = search)
        query(state.value.query.copy(text = search.text(searchScope)))
    }
    fun more() { window = (window + 100).coerceAtMost(RoutedSidebarWire.MAX_ROWS); restart() }
    fun retry() = restart()
    private fun restart() {
        job?.cancel()
        job = scope.launch {
            val active = available && foreground && visible
            try { publishVisibility(active) }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (active) mutable.value = state.value.copy(error = failure.message, loading = false)
                return@launch
            }
            if (!active) { mutable.value = state.value.copy(loading = false); return@launch }
            val query = state.value.query
            while (isActive) {
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
                    mutable.value = state.value.copy(snapshot = first.snapshot.copy(rows = rows), more = last.next != null,
                        loading = false, error = null)
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    mutable.value = state.value.copy(error = failure.message ?: "Could not load sidebar", loading = false)
                }
                delay(1_500)
            }
        }
    }
    suspend fun open(key: String): String? {
        if (state.value.navigating || !available || !foreground || !visible) return null
        mutable.value = state.value.copy(navigating = true, error = null)
        return try { select(key) }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            mutable.value = state.value.copy(error = failure.message ?: "Destination changed", navigating = false)
            null
        }
    }
}
