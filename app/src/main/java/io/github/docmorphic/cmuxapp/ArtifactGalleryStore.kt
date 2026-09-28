package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ArtifactGalleryLoadState(
    val snapshot: ArtifactGallerySnapshot? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val capped: Boolean = false,
)
internal data class ArtifactScanState(
    val scan: TerminalArtifactScan? = null, val loading: Boolean = false, val error: String? = null,
)

/** One Files sheet on one connection/terminal. All public mutations run on the owning UI dispatcher. */
internal class ArtifactGalleryStore(
    parent: CoroutineScope,
    val terminal: ArtifactAuthorization.Terminal,
    private val rpc: ArtifactRpc,
    private val searchDebounceMillis: Long = 300,
) : AutoCloseable {
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    private class Slot {
        val state = MutableStateFlow(ArtifactGalleryLoadState())
        var revision = 0L
        var job: Job? = null
        fun cancel() { revision++; job?.cancel(); job = null }
        fun reset() { cancel(); state.value = ArtifactGalleryLoadState() }
    }
    private val sessionSlot = Slot()
    private val searchSlot = Slot()
    val session = sessionSlot.state.asStateFlow()
    val search = searchSlot.state.asStateFlow()
    private val scanState = MutableStateFlow(ArtifactScanState())
    val inView = scanState.asStateFlow()
    private val sessionIdentity = MutableStateFlow<ArtifactAuthorization.Session?>(null)
    val sessionAuthorization = sessionIdentity.asStateFlow()
    private val queryState = MutableStateFlow("")
    val query = queryState.asStateFlow()
    private val pendingCount = MutableStateFlow(0)
    val pendingNewFiles = pendingCount.asStateFlow()
    private var pendingSnapshot: ArtifactGallerySnapshot? = null
    private var scanJob: Job? = null
    private var scanRevision = 0L
    private var liveJob: Job? = null
    private var liveRevision = 0L
    var isAtTopOrFits: Boolean = true

    fun initialize(): Job {
        check(owner.isActive) { "Files sheet has been closed." }
        sessionSlot.reset(); searchSlot.reset(); cancelLive(); resetPending()
        sessionIdentity.value = null; queryState.value = ""
        return scan(bindSession = true)
    }
    fun refreshInView(): Job = scan(bindSession = false)
    private fun scan(bindSession: Boolean): Job {
        check(owner.isActive) { "Files sheet has been closed." }
        val revision = ++scanRevision
        scanJob?.cancel()
        scanState.value = ArtifactScanState(loading = true)
        return scope.launch {
            try {
                val result = rpc.scan(terminal)
                ensureActive()
                if (revision != scanRevision) return@launch
                scanState.value = ArtifactScanState(scan = result)
                if (bindSession) {
                    sessionIdentity.value = result.sessionId?.takeIf { rpc.capabilities.gallery }?.let(ArtifactAuthorization::Session)
                    if (sessionIdentity.value != null) refreshSession()?.join()
                }
            } catch (failure: Exception) {
                ensureActive()
                if (revision == scanRevision) scanState.value = ArtifactScanState(error = failure.message ?: "Could not load files")
            }
        }.also { scanJob = it }
    }

    fun refreshSession(): Job? {
        cancelLive(); resetPending()
        return loadFirst(sessionSlot, null)
    }
    fun setQuery(value: String): Job? {
        val normalized = value.trim()
        if (normalized == queryState.value) return searchSlot.job
        queryState.value = normalized
        searchSlot.reset()
        cancelLive()
        return if (normalized.isEmpty()) null else loadFirst(searchSlot, normalized, searchDebounceMillis)
    }
    fun retrySearch(): Job? = queryState.value.takeIf { it.isNotEmpty() }?.let { loadFirst(searchSlot, it) }

    private fun loadFirst(slot: Slot, query: String?, debounce: Long = 0): Job? {
        val authorization = sessionIdentity.value ?: return null
        if (!owner.isActive) return null
        slot.cancel()
        val revision = slot.revision
        slot.state.value = ArtifactGalleryLoadState(loading = true)
        return scope.launch {
            try {
                if (debounce > 0) delay(debounce)
                val page = rpc.gallery(authorization, query = query)
                ensureActive()
                if (current(slot, revision, authorization, query)) slot.state.value = ArtifactGalleryLoadState(snapshot = page.snapshot)
            } catch (failure: Exception) {
                ensureActive()
                if (current(slot, revision, authorization, query)) slot.state.value = ArtifactGalleryLoadState(error = failure.message ?: "Could not load files")
            }
        }.also { slot.job = it }
    }

    /** Default view pages on demand; filtering/sorting asks for the upstream bounded eager snapshot. */
    fun loadMore(searching: Boolean = false, eager: Boolean = false): Job? {
        val authorization = sessionIdentity.value ?: return null
        val slot = if (searching) searchSlot else sessionSlot
        val query = if (searching) queryState.value.takeIf { it.isNotEmpty() } ?: return null else null
        val initialState = slot.state.value
        val initial = initialState.snapshot ?: return null
        if (!owner.isActive || initialState.loading || initialState.loadingMore) return slot.job
        val cursor = initial.nextCursor
        if (cursor == null) {
            slot.state.value = initialState.copy(capped = eager && initial.referenced.size < initial.referencedTotal)
            return null
        }
        slot.cancel()
        val revision = slot.revision
        slot.state.value = initialState.copy(loadingMore = true, error = null, capped = false)
        return scope.launch {
            try {
                val result = if (eager) loadRemainingArtifacts(initial) { rpc.gallery(authorization, it, query) }
                else rpc.gallery(authorization, cursor, query).let { ArtifactPagingResult(initial.append(it), requiresPagingRestart = it.requiresPagingRestart) }
                ensureActive()
                if (!current(slot, revision, authorization, query)) return@launch
                if (result.requiresPagingRestart) {
                    val fresh = rpc.gallery(authorization, query = query).snapshot
                    ensureActive()
                    if (!current(slot, revision, authorization, query)) return@launch
                    val merged = when {
                        searching -> initial.refresh(fresh, ArtifactRefreshPolicy.PRESERVE)
                        isAtTopOrFits -> { resetPending(); initial.refresh(fresh, ArtifactRefreshPolicy.APPLY) }
                        else -> { receivePending(fresh, initial); initial.refresh(fresh, ArtifactRefreshPolicy.DEFER) }
                    }
                    slot.state.value = ArtifactGalleryLoadState(snapshot = merged)
                } else {
                    slot.state.value = ArtifactGalleryLoadState(snapshot = result.snapshot, capped = result.reachedSafetyCap,
                        error = if (eager && !result.reachedSafetyCap && result.snapshot.nextCursor != null) "Could not finish loading files. Retry." else null)
                }
            } catch (failure: Exception) {
                ensureActive()
                if (current(slot, revision, authorization, query)) slot.state.value = initialState.copy(error = failure.message ?: "Could not load more files")
            }
        }.also { slot.job = it }
    }

    /** Called for accepted terminal/session refresh signals, not a polling loop. */
    fun refreshLive(): Job? {
        val authorization = sessionIdentity.value ?: return null
        val expectedGeneration = sessionSlot.state.value.snapshot?.generation ?: return null
        if (!owner.isActive || queryState.value.isNotEmpty()) return null
        cancelLive()
        val revision = liveRevision
        val slotRevision = sessionSlot.revision
        return scope.launch {
            try {
                val fresh = rpc.gallery(authorization).snapshot
                ensureActive()
                if (revision != liveRevision || !current(sessionSlot, slotRevision, authorization, null)) return@launch
                // Paging may have completed since this request started; retain every already-loaded row.
                val current = sessionSlot.state.value.snapshot ?: return@launch
                if (current.generation != expectedGeneration) return@launch
                if (fresh.generation == current.generation) return@launch
                val paths = current.items.mapTo(mutableSetOf()) { it.path }
                val newCount = fresh.items.count { it.path !in paths }
                if (isAtTopOrFits || newCount == 0) {
                    sessionSlot.cancel(); resetPending()
                    sessionSlot.state.value = ArtifactGalleryLoadState(snapshot = current.refresh(fresh, ArtifactRefreshPolicy.APPLY))
                } else receivePending(fresh, current)
            } catch (_: Exception) {
                ensureActive() // Retain readable content; a later accepted signal retries.
            }
        }.also { liveJob = it }
    }
    fun applyPending() {
        val displayed = sessionSlot.state.value.snapshot ?: return
        val fresh = pendingSnapshot ?: return
        cancelLive(); sessionSlot.cancel(); resetPending()
        sessionSlot.state.value = ArtifactGalleryLoadState(snapshot = displayed.refresh(fresh, ArtifactRefreshPolicy.APPLY))
    }
    fun sheetAuthorization(): ArtifactAuthorization = sessionIdentity.value ?: terminal

    private fun receivePending(fresh: ArtifactGallerySnapshot, displayed: ArtifactGallerySnapshot) {
        val paths = displayed.items.mapTo(mutableSetOf()) { it.path }
        pendingSnapshot = fresh
        pendingCount.value = fresh.items.count { it.path !in paths }
    }
    private fun current(slot: Slot, revision: Long, authorization: ArtifactAuthorization.Session, query: String?): Boolean =
        owner.isActive && slot.revision == revision && sessionIdentity.value == authorization &&
            (query == null || queryState.value == query)
    private fun resetPending() { pendingSnapshot = null; pendingCount.value = 0 }
    private fun cancelLive() { liveRevision++; liveJob?.cancel(); liveJob = null }
    override fun close() {
        scanRevision++; scanJob?.cancel(); cancelLive(); sessionSlot.reset(); searchSlot.reset(); resetPending()
        sessionIdentity.value = null; scanState.value = ArtifactScanState(); owner.cancel()
    }
}
