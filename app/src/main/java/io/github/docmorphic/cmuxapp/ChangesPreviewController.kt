package io.github.docmorphic.cmuxapp

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ChangesPreviewState(val identity: Any? = null, val artifact: ChangesPreviewArtifact? = null,
    val received: Long = 0, val total: Long? = null, val error: String? = null)

/** Changes-owner confined. One active revision/download; no Activity or view is retained. */
internal class ChangesPreviewController(private val scope: CoroutineScope) : AutoCloseable {
    private class Request(val file: ChangedFile, val generation: Any, val transfer: ChangesContentTransfer,
        val root: File, val revision: ChangesRevision)
    private var request: Request? = null
    private var job: Job? = null
    private var closed = false
    private val mutable = MutableStateFlow(ChangesPreviewState())
    val state = mutable.asStateFlow()
    private val choices = MutableStateFlow<Map<String, ChangesRevision>>(emptyMap())
    val revisions = choices.asStateFlow()

    fun revision(file: ChangedFile): ChangesRevision {
        val policy = ChangesPreviewPolicy.forFile(file)
        return choices.value[file.path]?.takeIf { it in policy.revisions } ?: policy.initial
    }

    fun choose(file: ChangedFile, revision: ChangesRevision) {
        if (closed || revision !in ChangesPreviewPolicy.forFile(file).revisions) return
        choices.value = (choices.value - file.path).entries.toList().takeLast(31).associate { it.toPair() } + (file.path to revision)
    }

    fun matches(value: ChangesPreviewState, file: ChangedFile, generation: Any,
                transfer: ChangesContentTransfer, revision: ChangesRevision): Boolean = request?.let {
        value.identity === it && it.file == file && it.generation === generation &&
            it.transfer === transfer && it.revision == revision
    } == true

    fun open(file: ChangedFile, generation: Any, transfer: ChangesContentTransfer, root: File) {
        if (closed || !scope.isActive) return
        val revision = revision(file)
        if (matches(mutable.value, file, generation, transfer, revision)) return
        start(Request(file, generation, transfer, root, revision))
    }

    fun retry() { if (!closed) request?.let { start(Request(it.file, it.generation, it.transfer, it.root, it.revision)) } }

    private fun start(next: Request) {
        job?.cancel()
        request = next; mutable.value = ChangesPreviewState(next)
        job = scope.launch {
            val session = ChangesPreviewFiles(next.root, next.transfer)
            fun publish(change: (ChangesPreviewState) -> ChangesPreviewState) {
                if (request === next && !closed) mutable.value = change(mutable.value)
            }
            try {
                val path = ChangesPreviewPolicy.path(next.file, next.revision)
                val metadata = next.transfer.metadata(path, next.revision)
                ensureActive(); publish { it.copy(total = metadata.size) }
                val artifact = session.download(path, next.revision, metadata) { received, total ->
                    withContext(scope.coroutineContext.minusKey(Job)) { ensureActive(); publish { it.copy(received = received, total = total) } }
                }
                ensureActive(); publish { it.copy(artifact = artifact) }
                awaitCancellation()
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                publish { it.copy(error = failure.message ?: "Could not load preview") }
                awaitCancellation()
            } finally { withContext(NonCancellable + Dispatchers.IO) { session.close() } }
        }
    }

    fun retainPath(path: String?) { if (request?.file?.path != path) invalidate() }
    fun invalidate(path: String? = null) {
        if (path != null && request?.file?.path != path) return
        request = null; mutable.value = ChangesPreviewState(); job?.cancel(); job = null
    }
    override fun close() { closed = true; invalidate(); choices.value = emptyMap() }
}
