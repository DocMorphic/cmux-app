package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared across pane remounts and borrowed leases of the same physical connection. */
internal object SimulatorTransitions {
    private data class Key(val connection: String, val panel: String)
    private class Entry(val mutex: Mutex = Mutex(), var references: Int = 0)
    private val entries = mutableMapOf<Key, Entry>()
    suspend fun <T> use(connection: String, panel: String, block: suspend () -> T): T {
        val key = Key(connection, panel)
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.references++ } }
        try { return entry.mutex.withLock { block() } }
        finally { synchronized(entries) { if (--entry.references == 0) entries.remove(key) } }
    }
}

internal fun interface LegacySimulatorSource {
    suspend fun use(block: suspend (LegacySimulatorEndpoint) -> Unit)
}

internal class MobileLegacySimulatorSource(private val client: MobileRpcClient, private val panel: String) : LegacySimulatorSource {
    override suspend fun use(block: suspend (LegacySimulatorEndpoint) -> Unit) = client.useEventSession { scoped ->
        SimulatorTransitions.use(client.simulatorConnectionId, panel) { block(MobileLegacySimulatorEndpoint(scoped)) }
    }
}
