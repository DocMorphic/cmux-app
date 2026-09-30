package io.github.docmorphic.cmuxapp

import io.github.docmorphic.cmuxapp.iroh.IrxDuplexLane
import io.github.docmorphic.cmuxapp.iroh.IrxWire
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal fun simulatorLaneDescriptor(panelId: String): IrxWire.Descriptor {
    val id = UUID.fromString(panelId).toString()
    require(id.equals(panelId, ignoreCase = true)) { "Invalid simulator panel ID" }
    return IrxWire.Descriptor(IrxWire.Lane.SIMULATOR_STREAM, "simstream:$id")
}

internal class IrxSimulatorLane(private val lane: IrxDuplexLane, private val permits: () -> Unit) : SimStreamLane {
    private val closed = AtomicBoolean()
    private fun checkAccess() { check(!closed.get()) { "Simulator lane closed" }; permits() }
    override suspend fun read(): ByteArray? {
        checkAccess()
        val bytes = lane.read(SimStreamWire.MAX_CHUNK)
        currentCoroutineContext().ensureActive(); checkAccess()
        return bytes.takeIf { it.isNotEmpty() }
    }
    override suspend fun write(bytes: ByteArray) {
        require(bytes.size <= SimStreamWire.MAX_BODY + 4)
        checkAccess()
        lane.write(bytes)
        currentCoroutineContext().ensureActive(); checkAccess()
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) cleanup.launch {
            try { runCatching { withTimeout(2000) { lane.retire(0uL) } } }
            finally { lane.close() }
        }
    }
    companion object { private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
