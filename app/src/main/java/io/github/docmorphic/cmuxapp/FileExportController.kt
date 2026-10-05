package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal enum class FileExportAction { SHARE, OPEN, COPY_IMAGE }
internal enum class FileExportPhase { PREPARING, READY, PRESENTING, FAILED }
internal data class FileExportState(val identity: Any? = null, val key: Any? = null, val title: String = "",
    val action: FileExportAction = FileExportAction.SHARE, val phase: FileExportPhase? = null,
    val artifact: LocalFilePreview? = null, val failure: String? = null, val needsConfirmation: Boolean = false) {
    val busy get() = phase != null && phase != FileExportPhase.FAILED
}

/** One export outlives its requesting composable. A UI owner may claim its presentation exactly once. */
internal class FileExportController(private val scope: CoroutineScope, private val store: FileExportStore? = null,
    private val pendingId: (String?) -> Unit = {}) : AutoCloseable {
    private class Request(val id: String = UUID.randomUUID().toString()) {
        var handedOff = false; var discarded = false
        @Volatile var persistence: CompletableDeferred<Unit>? = null
    }
    private var request: Request? = null
    private var job: Job? = null
    private var closed = false
    private val mutable = MutableStateFlow(FileExportState())
    val state = mutable.asStateFlow()
    fun begin(key: Any, title: String, action: FileExportAction, failure: (Exception) -> String,
        prepare: suspend () -> LocalFilePreview): Boolean {
        if (closed || !scope.isActive || mutable.value.busy) return false
        clear()
        val next = Request(); request = next
        pendingId(next.id)
        mutable.value = FileExportState(next, key, title, action, FileExportPhase.PREPARING)
        job = scope.launch {
            var artifact: LocalFilePreview? = null
            var lease: AutoCloseable? = null
            var owner: AutoCloseable? = null
            var source: LocalFilePreview? = null
            try {
                if (store != null) withContext(Dispatchers.IO) {
                    owner = checkNotNull(store.claim(next.id))
                    store.create(next.id, title, action)
                }
                source = prepare()
                withContext(Dispatchers.IO) { lease = ArtifactExportCache.hold(checkNotNull(source.file.parentFile)) }
                artifact = if (store != null) store.adopt(next.id, checkNotNull(source)) else source
                ensureActive()
                if (request === next) mutable.value = mutable.value.copy(phase = FileExportPhase.READY, artifact = artifact)
                awaitCancellation()
            } catch (error: Exception) {
                ensureActive()
                if (request === next) mutable.value = mutable.value.copy(phase = FileExportPhase.FAILED, failure = failure(error))
            } finally {
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        next.persistence?.await()
                        if (store != null) {
                            if (owner != null) runCatching { store.abandon(next.id, next.discarded) }
                            source?.file?.parentFile?.deleteRecursively()
                        } else if (!next.handedOff) (artifact ?: source)?.file?.parentFile?.deleteRecursively()
                    }
                } finally { lease?.close(); owner?.close() }
            }
        }
        return true
    }
    fun restore(id: String) {
        if (store == null || closed || mutable.value.phase != null) return
        val next = Request(id); request = next
        mutable.value = FileExportState(next, title = "Restoring file", phase = FileExportPhase.PREPARING)
        job = scope.launch {
            var owner: AutoCloseable? = null
            try {
                withContext(Dispatchers.IO) { owner = store.claim(id) }
                if (owner == null) { request = null; pendingId(null); mutable.value = FileExportState(); return@launch }
                val receipt = store.restore(id)
                ensureActive()
                when (receipt.phase) {
                    FileExportReceiptPhase.READY -> mutable.value = FileExportState(next, title = receipt.title,
                        action = receipt.action, phase = FileExportPhase.READY, artifact = receipt.artifact, needsConfirmation = true)
                    FileExportReceiptPhase.PRESENTING -> {
                        next.handedOff = true
                        mutable.value = FileExportState(next, title = receipt.title, phase = FileExportPhase.FAILED,
                            failure = "This file action may already have opened. Open its preview if you want to try again.")
                    }
                    FileExportReceiptPhase.HANDED_OFF, FileExportReceiptPhase.CANCELLED -> {
                        request = null; pendingId(null); mutable.value = FileExportState(); return@launch
                    }
                    FileExportReceiptPhase.PREPARING -> error("Preparation was interrupted")
                }
                awaitCancellation()
            } catch (error: Exception) {
                ensureActive()
                next.discarded = true
                if (request === next) mutable.value = mutable.value.copy(phase = FileExportPhase.FAILED,
                    failure = "The prepared file is unavailable or incomplete. Open its preview and try again.")
            } finally {
                try { withContext(NonCancellable + Dispatchers.IO) {
                    next.persistence?.await()
                    if (owner != null) runCatching { store.abandon(id, next.discarded) }
                } } finally { owner?.close() }
            }
        }
    }
    fun continueRestored(value: FileExportState) {
        if (request === value.identity && mutable.value.phase == FileExportPhase.READY)
            mutable.value = mutable.value.copy(needsConfirmation = false)
    }
    suspend fun claim(value: FileExportState): LocalFilePreview? {
        if (closed || request !== value.identity || mutable.value.phase != FileExportPhase.READY || mutable.value.needsConfirmation) return null
        val artifact = mutable.value.artifact ?: return null
        mutable.value = mutable.value.copy(phase = FileExportPhase.PRESENTING)
        val active = value.identity as Request
        store?.let { persist(active) { it.presenting(active.id) } }
        if (closed || request !== value.identity || mutable.value.phase != FileExportPhase.PRESENTING) return null
        return artifact
    }
    suspend fun handedOff(value: FileExportState) {
        val active = request ?: return
        if (active !== value.identity || mutable.value.phase != FileExportPhase.PRESENTING) return
        active.handedOff = true
        // PRESENTING already prevents automatic replay if writing the final receipt fails.
        try { store?.let { persist(active) { runCatching { it.handedOff(active.id) } } } }
        finally {
            // Opening a chooser may stop the Activity and cancel its collector while
            // the receipt is syncing. The completed handoff must still release busy UI.
            if (request === active) clear()
        }
    }
    fun presentationFailed(value: FileExportState, message: String) {
        if (request !== value.identity || mutable.value.phase != FileExportPhase.PRESENTING) return
        request?.discarded = true; job?.cancel(); job = null
        mutable.value = mutable.value.copy(phase = FileExportPhase.FAILED, artifact = null, failure = message)
    }
    fun clear() { request?.discarded = true; request = null; pendingId(null); job?.cancel(); job = null; mutable.value = FileExportState() }
    private suspend fun persist(value: Request, action: () -> Unit) {
        val finished = CompletableDeferred<Unit>(); value.persistence = finished
        try { withContext(NonCancellable + Dispatchers.IO) { action() } }
        finally { finished.complete(Unit) }
    }
    override fun close() {
        closed = true
        if (store == null) clear()
        else { request = null; job?.cancel(); job = null; mutable.value = FileExportState() }
    }
}
