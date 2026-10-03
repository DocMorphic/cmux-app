package io.github.docmorphic.cmuxapp

import io.github.docmorphic.cmuxapp.iroh.IrxDuplexLane
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

internal class IrxArtifactLane(private val lane: IrxDuplexLane, private val permits: () -> Unit) : ArtifactLane {
    private val closed = AtomicBoolean()
    override suspend fun read(maximumBytes: Int): ByteArray? {
        require(maximumBytes in 1..64 * 1024)
        check(!closed.get()) { "Artifact lane closed" }
        permits()
        val bytes = withTimeout(30_000) { lane.read(maximumBytes) }
        currentCoroutineContext().ensureActive(); permits()
        check(!closed.get()) { "Artifact lane closed" }
        return bytes.takeIf { it.isNotEmpty() }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) cleanup.launch { runCatching { lane.retire(0uL) } }
    }
    companion object { private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
