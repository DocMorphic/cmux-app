package io.github.docmorphic.cmuxapp

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ArtifactPreviewState(val identity: Any? = null, val artifact: LocalFilePreview? = null,
    val total: Long? = null, val received: Long = 0, val error: String? = null,
    val failure: ArtifactPreviewFailure? = null)

/** One selected file, owned by a presentation rather than its Android view. UI-dispatcher confined. */
internal class ArtifactPreviewController(private val scope: CoroutineScope) : AutoCloseable {
    private class Request(val rpc: ArtifactRpc, val authorization: ArtifactAuthorization, val path: String,
        val root: File, val forceMarkdown: Boolean)
    private var request: Request? = null
    private var job: Job? = null
    private var closed = false
    private val mutable = MutableStateFlow(ArtifactPreviewState())
    val state = mutable.asStateFlow()

    fun matches(value: ArtifactPreviewState, rpc: ArtifactRpc, authorization: ArtifactAuthorization,
                path: String, forceMarkdown: Boolean): Boolean = request?.let {
        value.identity === it && it.rpc === rpc && it.authorization == authorization &&
            it.path == path && it.forceMarkdown == forceMarkdown
    } == true

    fun open(rpc: ArtifactRpc, authorization: ArtifactAuthorization, path: String, root: File,
             forceMarkdown: Boolean = false) {
        if (closed || !scope.isActive) return
        if (matches(mutable.value, rpc, authorization, path, forceMarkdown) && request?.root == root) return
        start(Request(rpc, authorization, path, root, forceMarkdown))
    }

    fun retry() { if (!closed) request?.let { start(Request(it.rpc, it.authorization, it.path, it.root, it.forceMarkdown)) } }

    /** A panel's old admission cannot issue a retry while its replacement connection is unavailable. */
    fun retryUnavailable() {
        if (closed || !scope.isActive || mutable.value.error == null) return
        mutable.value = mutable.value.copy(error = "Mac disconnected.",
            failure = ArtifactPreviewFailure(ArtifactPreviewFailure.Kind.MAC_UNREACHABLE))
    }

    /** Stop an unfinished request when its exact connection retires; late chunks cannot publish. */
    fun connectionLost() {
        if (closed || !scope.isActive || request == null || mutable.value.artifact != null || mutable.value.error != null) return
        job?.cancel(); job = null
        mutable.value = mutable.value.copy(error = "Mac disconnected.",
            failure = ArtifactPreviewFailure(ArtifactPreviewFailure.Kind.MAC_UNREACHABLE))
    }

    private fun start(next: Request) {
        job?.cancel()
        request = next; mutable.value = ArtifactPreviewState(next)
        job = scope.launch {
            val transfer = ArtifactContentTransfer(next.rpc, next.authorization)
            val files = ArtifactPreviewFiles(next.root, transfer)
            fun publish(change: (ArtifactPreviewState) -> ArtifactPreviewState) {
                if (!closed && request === next) mutable.value = change(mutable.value)
            }
            try {
                val metadata = transfer.metadata(next.path).let {
                    if (next.forceMarkdown) it.copy(kind = ArtifactKind.TEXT, mime = "text/markdown") else it
                }
                ensureActive(); publish { it.copy(total = metadata.size) }
                val artifact = files.download(next.path, metadata) { received, total ->
                    withContext(scope.coroutineContext.minusKey(Job)) {
                        ensureActive(); publish { it.copy(received = received, total = total) }
                    }
                }
                ensureActive(); publish { it.copy(artifact = artifact) }
                awaitCancellation()
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                publish { it.copy(error = failure.message ?: "Could not load preview", failure = ArtifactPreviewFailure.from(failure, next.authorization)) }
                awaitCancellation()
            } finally { withContext(NonCancellable + Dispatchers.IO) { files.close() } }
        }
    }

    fun clear() { request = null; mutable.value = ArtifactPreviewState(); job?.cancel(); job = null }
    override fun close() { closed = true; clear() }
}
