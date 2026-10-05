package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ArtifactFolderState(val identity: Any? = null, val listing: ArtifactDirectoryListing? = null,
    val loading: Boolean = false, val failure: ArtifactPreviewFailure? = null)

/** Owns a directory request independently of its Android view. Contains neither views nor file bytes. */
internal class ArtifactFolderController(private val scope: CoroutineScope) : AutoCloseable {
    private class Request(var rpc: ArtifactRpc, val authorization: ArtifactAuthorization, val path: String)
    private var request: Request? = null
    private var job: Job? = null
    private var closed = false
    private val mutable = MutableStateFlow(ArtifactFolderState())
    val state = mutable.asStateFlow()
    fun matches(state: ArtifactFolderState, rpc: ArtifactRpc, destination: ArtifactDestination.Folder) = request?.let {
        state.identity === it && it.rpc === rpc && it.authorization == destination.authorization && it.path == destination.item.path
    } == true
    fun open(rpc: ArtifactRpc, destination: ArtifactDestination.Folder) {
        if (closed || !scope.isActive || matches(mutable.value, rpc, destination)) return
        start(Request(rpc, destination.authorization, destination.item.path))
    }
    fun retry() {
        if (!closed && scope.isActive) request?.let {
            start(Request(it.rpc, it.authorization, it.path), mutable.value.listing)
        }
    }
    fun connectionLost() {
        if (closed || !mutable.value.loading) return
        job?.cancel(); job = null
        mutable.value = mutable.value.copy(loading = false,
            failure = ArtifactPreviewFailure(ArtifactPreviewFailure.Kind.MAC_UNREACHABLE))
    }
    fun replaceConnection(rpc: ArtifactRpc) {
        if (closed || !scope.isActive) return
        connectionLost(); request?.rpc = rpc
    }
    private fun start(next: Request, previous: ArtifactDirectoryListing? = null) {
        job?.cancel(); request = next
        mutable.value = ArtifactFolderState(next, previous, loading = true)
        val rpc = next.rpc
        job = scope.launch {
            try {
                val listing = rpc.list(next.authorization, next.path)
                ensureActive()
                if (!closed && request === next) mutable.value = ArtifactFolderState(next, listing)
            } catch (failure: Exception) {
                ensureActive()
                if (!closed && request === next) mutable.value = ArtifactFolderState(next, previous,
                    failure = ArtifactPreviewFailure.from(failure, next.authorization))
            }
        }
    }
    fun clear() { request = null; job?.cancel(); job = null; mutable.value = ArtifactFolderState() }
    override fun close() { closed = true; clear() }
}
