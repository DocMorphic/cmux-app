package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-dispatcher owner for one terminal's count requests, settled local observations and chip lifetime. */
internal class TerminalArtifactController(parent: CoroutineScope, private val rpc: ArtifactRpc,
    private val terminal: ArtifactAuthorization.Terminal, private val showMissing: Boolean,
    private val hideDelayMillis: Long = 3_500) : AutoCloseable {
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    private val counts = TerminalArtifactCount()
    private val visibility = TerminalArtifactVisibility()
    private val displayed = MutableStateFlow<Int?>(null)
    val count = displayed.asStateFlow()
    private val refresh = MutableStateFlow(0)
    val galleryRefresh = refresh.asStateFlow()
    private var lastReport: TerminalArtifactCount.Report? = null
    private var generation = 0L
    private var localCount = 0
    private var requestJob: Job? = null
    private var hideJob: Job? = null

    suspend fun observe(text: String) {
        if (!owner.isActive) return
        val observedGeneration = ++generation
        val paths = withContext(Dispatchers.Default) { TerminalArtifactPaths.paths(text) }
        currentCoroutineContext().ensureActive()
        if (!owner.isActive || generation != observedGeneration) return
        localCount = paths.size
        val action = counts.trigger(localCount, generation, rpc.capabilities.gallery)
        deliver(action.report, action.authoritative)
        action.request?.let(::start)
    }
    private fun start(initial: TerminalArtifactCount.Request) {
        requestJob = scope.launch {
            var request: TerminalArtifactCount.Request? = initial
            while (request != null) {
                val activeRequest = request
                val response = try { rpc.scan(terminal, visibleOnly = true, countOnly = true, includeMissing = showMissing) }
                    catch (_: Exception) { ensureActive(); null }
                ensureActive()
                val completion = counts.complete(activeRequest, response?.galleryRowTotal, response?.sessionTotal, response?.sessionId,
                    response != null, generation, localCount)
                completion.report?.let { deliver(it, true) }
                request = completion.next
            }
        }
    }
    private fun deliver(report: TerminalArtifactCount.Report, authoritative: Boolean) {
        if (!owner.isActive || report.surfaceGeneration != generation) return
        when (visibility.update(report.count, enabled = rpc.capabilities.terminal)) {
            TerminalArtifactVisibility.Action.NONE -> Unit
            TerminalArtifactVisibility.Action.MOUNT -> { hideJob?.cancel(); hideJob = null; displayed.value = report.count }
            TerminalArtifactVisibility.Action.HIDE -> { hideJob?.cancel(); hideJob = null; displayed.value = null }
            TerminalArtifactVisibility.Action.SCHEDULE_HIDE -> if (hideJob == null) hideJob = scope.launch {
                delay(hideDelayMillis); visibility.hideCompleted(); displayed.value = null; hideJob = null
            }
        }
        if (authoritative && lastReport != report) { lastReport = report; refresh.value++ }
    }
    override fun close() {
        counts.reset(); owner.cancel(); requestJob = null; hideJob = null; displayed.value = null
    }
}
