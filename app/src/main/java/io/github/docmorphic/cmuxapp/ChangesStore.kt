package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal data class ChangesListState(val snapshot: ChangesSnapshot? = null, val loading: Boolean = false,
    val error: String? = null, val notRepository: Boolean = false)
internal data class ChangesPageState(val document: ChangesDiffDocument? = null, val loading: Boolean = false,
    val error: String? = null, val budget: Int = DiffContinuation.DEFAULT_BUDGET, val ceiling: Boolean = false,
    val failedContinuation: Boolean = false, val expansion: ChangesExpansion = ChangesExpansion(),
    val rows: List<ChangesDiffRowContent> = emptyList())

/** Workspace/connection-scoped requests and a seven-page cache, independent of pager composition. */
internal class ChangesStore(parent: CoroutineScope, private val workspace: String,
    private val fetchFiles: suspend () -> JSONObject, private val fetchDiff: suspend (String, Int) -> JSONObject,
    private val fetchLines: suspend (String) -> ChangesCurrentFile = { error("File content is unavailable") }) : AutoCloseable {
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    private val list = MutableStateFlow(ChangesListState())
    val listing = list.asStateFlow()
    private class Page {
        val state = MutableStateFlow(ChangesPageState())
        var generation = 0L
        var job: Job? = null
    }
    private val pages = mutableMapOf<String, Page>()
    private val recency = ArrayDeque<String>()
    private var selected: String? = null
    private var listJob: Job? = null
    private var listGeneration = 0L
    private var prefetch: Job? = null
    val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()
    fun page(path: String) = pages.getOrPut(path) { Page() }.state.asStateFlow()
    fun refresh(): Job {
        val generation = ++listGeneration
        listJob?.cancel()
        list.value = list.value.copy(loading = true, error = null, notRepository = false)
        return scope.launch {
            try {
                val value = fetchFiles()
                val snapshot = withContext(Dispatchers.Default) { ChangesSnapshot.read(value, workspace) }
                ensureActive()
                if (generation != listGeneration) return@launch
                val paths = snapshot.files.map { it.path }.toSet()
                pages.keys.filter { it !in paths }.forEach { path ->
                    pages.remove(path)?.let { it.generation++; it.job?.cancel() }
                    recency.remove(path); scrollPositions.remove(path)
                }
                // A refreshed list is a new repository snapshot, even when paths stayed the same.
                pages.values.forEach { it.generation++; it.job?.cancel(); it.job = null; it.state.value = ChangesPageState() }
                recency.clear()
                list.value = ChangesListState(snapshot = snapshot)
                selected?.takeIf { it in paths }?.let(::select)
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation != listGeneration) return@launch
                val notRepo = (failure as? MobileRpcException)?.code == "not_a_repo"
                list.value = if (notRepo) ChangesListState(notRepository = true)
                    else list.value.copy(loading = false, error = failure.message ?: "Could not load changes")
            }
        }.also { listJob = it }
    }
    fun load(path: String, force: Boolean = false, more: Boolean = false): Job? {
        if (!owner.isActive || list.value.snapshot?.files?.none { it.path == path } != false) return null
        val record = pages.getOrPut(path) { Page() }
        val previous = record.state.value
        if (!force && !more && (previous.document != null || previous.loading)) { touch(path); return record.job }
        val continuation = previous.document?.let { DiffContinuation(previous.budget, it, previous.ceiling) }
        if (more && (previous.loading || continuation?.canGrow != true)) return record.job
        val requested = if (more) checkNotNull(continuation).nextBudget else previous.budget
        val generation = ++record.generation
        record.job?.cancel()
        record.state.value = previous.copy(document = if (force) null else previous.document,
            loading = true, error = null, failedContinuation = false,
            rows = if (force) emptyList() else previous.rows,
            expansion = if (force) ChangesExpansion() else previous.expansion.copy(pending = null))
        return scope.launch {
            try {
                val value = fetchDiff(path, requested)
                val kind = list.value.snapshot?.files?.firstOrNull { it.path == path }?.kind ?: ChangeKind.UNKNOWN
                val (document, rows) = withContext(Dispatchers.Default) {
                    val document = ChangesDiffDocument.read(value, path)
                    document to projectChanges(document, kind)
                }
                ensureActive()
                if (generation != record.generation || pages[path] !== record) return@launch
                record.state.value = ChangesPageState(document, budget = requested,
                    ceiling = more && document.rawLineCount <= (previous.document?.rawLineCount ?: 0), rows = rows)
                touch(path); trim()
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation != record.generation || pages[path] !== record) return@launch
                record.state.value = record.state.value.copy(loading = false, error = failure.message ?: "Could not load diff", failedContinuation = more)
            } finally {
                if (generation == record.generation) {
                    record.state.value = record.state.value.copy(loading = false)
                    record.job = null
                }
            }
        }.also { record.job = it }
    }
    fun expand(path: String, gapId: Int, direction: ChangesExpandDirection, preferred: ChangesLineRange? = null): Job? {
        val record = pages[path] ?: return null
        val previous = record.state.value
        val document = previous.document ?: return null
        val kind = list.value.snapshot?.files?.firstOrNull { it.path == path }?.kind ?: return null
        if (!owner.isActive || kind == ChangeKind.DELETED || document.binary || previous.expansion.tooLarge || previous.expansion.pending != null) return null
        if (ChangesGap.gaps(document, previous.expansion.current?.lines?.size).none { it.id == gapId }) return null
        val generation = ++record.generation
        record.job?.cancel()
        record.state.value = previous.copy(loading = false, expansion = previous.expansion.copy(pending = gapId, failed = null))
        return scope.launch {
            try {
                val current = previous.expansion.current ?: fetchLines(path)
                ensureActive()
                if (record.generation != generation || pages[path] !== record) return@launch
                if (!changesFingerprintValid(document.fingerprint, currentOnly = true) || document.fingerprint != current.fingerprint) {
                    load(path, force = true)
                    return@launch
                }
                val (expansion, rows) = withContext(Dispatchers.Default) {
                    var expansion = previous.expansion.copy(current = current, pending = null, failed = null)
                    ChangesGap.gaps(document, current.lines.size).firstOrNull { it.id == gapId }?.let {
                        expansion = expansion.reveal(it, direction, preferred)
                    }
                    expansion to projectChanges(document, kind, expansion)
                }
                ensureActive()
                if (record.generation == generation && pages[path] === record) {
                    record.state.value = record.state.value.copy(expansion = expansion, rows = rows)
                    touch(path); trim()
                }
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (record.generation != generation || pages[path] !== record) return@launch
                if (failure is ChangesRevisionChanged) { load(path, force = true); return@launch }
                record.state.value = record.state.value.copy(expansion = previous.expansion.copy(
                    pending = null, failed = gapId, tooLarge = failure is ChangesContentTooLarge))
            } finally {
                if (record.generation == generation) {
                    record.state.value = record.state.value.copy(expansion = record.state.value.expansion.copy(pending = null))
                    record.job = null
                }
            }
        }.also { record.job = it }
    }
    fun select(path: String) {
        selected = path; touch(path); load(path)
        prefetch?.cancel()
        val files = list.value.snapshot?.files.orEmpty()
        val index = files.indexOfFirst { it.path == path }
        if (index < 0) return
        prefetch = scope.launch {
            for (distance in 1..2) for (candidate in listOf(index + distance, index - distance)) {
                ensureActive()
                files.getOrNull(candidate)?.let { load(it.path)?.join() }
            }
        }
    }
    fun clearSelection() { selected = null; prefetch?.cancel(); prefetch = null }
    private fun touch(path: String) { recency.remove(path); recency.addLast(path) }
    private fun trim() {
        val files = list.value.snapshot?.files.orEmpty()
        val current = files.indexOfFirst { it.path == selected }
        val protected = if (current < 0) emptySet() else (current - 2..current + 2).mapNotNull { files.getOrNull(it)?.path }.toSet()
        while (pages.values.count { it.state.value.document != null } > 7) {
            val victim = recency.firstOrNull { it !in protected && pages[it]?.state?.value?.document != null } ?: return
            recency.remove(victim)
            pages[victim]?.let { it.state.value = it.state.value.copy(document = null, budget = DiffContinuation.DEFAULT_BUDGET, ceiling = false, rows = emptyList(), expansion = ChangesExpansion()) }
        }
    }
    override fun close() { listGeneration++; pages.values.forEach { it.generation++ }; owner.cancel() }
}
