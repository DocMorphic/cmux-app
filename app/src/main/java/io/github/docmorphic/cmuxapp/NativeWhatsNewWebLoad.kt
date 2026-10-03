package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class WhatsNewWebPhase { LOADING, LOADED, FAILED }

/** Renderer-neutral lifetime. Its deadline includes session exchange, cookie seeding and first paint. */
internal class NativeWhatsNewWebLoad(
    parent: CoroutineScope, private val policy: WhatsNewWebPolicy, val url: String,
    deadlineMillis: Long, private val stopRenderer: () -> Unit,
    private val closeRenderer: () -> Unit, start: suspend () -> Unit
) : AutoCloseable {
    private val lifetime = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + lifetime)
    private val mutable = MutableStateFlow(WhatsNewWebPhase.LOADING)
    val phase = mutable.asStateFlow()
    private val settled = CompletableDeferred<WhatsNewWebPhase>()
    private val lock = Any()
    private var closed = false
    private var loading: Job? = null
    private var deadline: Job? = null
    init {
        require(deadlineMillis > 0)
        deadline = scope.launch(start = CoroutineStart.LAZY) { delay(deadlineMillis); settle(WhatsNewWebPhase.FAILED) }
        loading = scope.launch(start = CoroutineStart.LAZY) {
            try { if (policy.allows(url)) start() else settle(WhatsNewWebPhase.FAILED) }
            catch (cancelled: CancellationException) { settle(WhatsNewWebPhase.FAILED); throw cancelled }
            catch (_: Exception) { settle(WhatsNewWebPhase.FAILED) }
        }
        lifetime.invokeOnCompletion { close() }
        deadline?.start(); loading?.start()
    }
    suspend fun outcome(): WhatsNewWebPhase = settled.await()
    fun finishedInitialPage() = settle(WhatsNewWebPhase.LOADED)
    fun failedInitialPage() = settle(WhatsNewWebPhase.FAILED)
    /** Internal blank documents are harmless. Every actual navigation uses the same origin policy. */
    fun allowsNavigation(destination: String, mainFrame: Boolean): Boolean {
        if (destination == "about:blank") return true
        if (policy.allows(destination)) return true
        if (mainFrame) settle(WhatsNewWebPhase.FAILED)
        return false
    }
    private fun settle(next: WhatsNewWebPhase) {
        val stop = synchronized(lock) {
            if (mutable.value != WhatsNewWebPhase.LOADING) return
            mutable.value = next
            settled.complete(next)
            deadline?.cancel()
            if (next == WhatsNewWebPhase.FAILED) { loading?.cancel(); true } else false
        }
        if (stop) stopRenderer()
    }
    override fun close() {
        synchronized(lock) { if (closed) return; closed = true }
        settle(WhatsNewWebPhase.FAILED)
        lifetime.cancel()
        closeRenderer()
    }
    companion object {
        const val LAUNCH_DEADLINE_MS = 10_000L
        const val ARCHIVE_DEADLINE_MS = 20_000L
    }
}
