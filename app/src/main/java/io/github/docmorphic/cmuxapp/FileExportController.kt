package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class FileExportAction { SHARE, OPEN, COPY_IMAGE }
internal enum class FileExportPhase { PREPARING, READY, PRESENTING, FAILED }
internal data class FileExportState(val identity: Any? = null, val key: Any? = null, val title: String = "",
    val action: FileExportAction = FileExportAction.SHARE, val phase: FileExportPhase? = null,
    val artifact: LocalFilePreview? = null, val failure: String? = null) {
    val busy get() = phase != null && phase != FileExportPhase.FAILED
}

/** One export outlives its requesting composable. A UI owner may claim its presentation exactly once. */
internal class FileExportController(private val scope: CoroutineScope) : AutoCloseable {
    private class Request { var handedOff = false }
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
        mutable.value = FileExportState(next, key, title, action, FileExportPhase.PREPARING)
        job = scope.launch {
            var artifact: LocalFilePreview? = null
            var lease: AutoCloseable? = null
            try {
                artifact = prepare()
                lease = ArtifactExportCache.hold(checkNotNull(artifact.file.parentFile))
                ensureActive()
                if (request === next) mutable.value = mutable.value.copy(phase = FileExportPhase.READY, artifact = artifact)
                awaitCancellation()
            } catch (error: Exception) {
                ensureActive()
                if (request === next) mutable.value = mutable.value.copy(phase = FileExportPhase.FAILED, failure = failure(error))
            } finally {
                try {
                    if (!next.handedOff) withContext(NonCancellable + Dispatchers.IO) { artifact?.file?.parentFile?.deleteRecursively() }
                } finally { lease?.close() }
            }
        }
        return true
    }
    fun claim(value: FileExportState): LocalFilePreview? {
        if (closed || request !== value.identity || mutable.value.phase != FileExportPhase.READY) return null
        val artifact = mutable.value.artifact ?: return null
        mutable.value = mutable.value.copy(phase = FileExportPhase.PRESENTING)
        return artifact
    }
    fun handedOff(value: FileExportState) {
        val active = request ?: return
        if (active !== value.identity || mutable.value.phase != FileExportPhase.PRESENTING) return
        active.handedOff = true; clear()
    }
    fun presentationFailed(value: FileExportState, message: String) {
        if (request !== value.identity || mutable.value.phase != FileExportPhase.PRESENTING) return
        job?.cancel(); job = null
        mutable.value = mutable.value.copy(phase = FileExportPhase.FAILED, artifact = null, failure = message)
    }
    fun clear() { request = null; job?.cancel(); job = null; mutable.value = FileExportState() }
    override fun close() { closed = true; clear() }
}
