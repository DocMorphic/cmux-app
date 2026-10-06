package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Lazy, shared machine attempts for one tunnel generation. Never owns the API client. */
internal class CloudMachineConnections<S : AutoCloseable>(
    private val parent: CoroutineScope, private val service: CloudTerminalService,
    private val fingerprint: String, private val isCurrent: () -> Boolean,
    private val connect: (CloudAttachEndpoint) -> S,
    private val nativeDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val retireSession: (S) -> Unit = {}
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val connections = mutableMapOf<String, CloudMachineHandshake<S>>()
    fun connection(id: String): CloudMachineHandshake<S>? = synchronized(lock) {
        if (closed || !isCurrent()) return@synchronized null
        // A failed initial dial owns no reusable session. The next catalog retry
        // needs a fresh attempt; retirement/cleanup already belongs to that dial.
        if (connections[id]?.state?.value?.phase in setOf(CloudLinkPhase.FAILED, CloudLinkPhase.CLOSED)) connections.remove(id)
        connections.getOrPut(id) {
            CloudMachineHandshake(parent, service, id, fingerprint,
                { synchronized(lock) { !closed && isCurrent() } }, connect, nativeDispatcher, retireSession = retireSession)
        }
    }
    fun retire(ids: Set<String>) {
        val retired = synchronized(lock) { ids.mapNotNull(connections::remove) }
        // Do not hold the pool lock while closing: an in-flight handshake can
        // hold its own lock while checking this pool's admission predicate.
        retired.forEach { it.close() }
    }
    fun retire(id: String, expected: CloudMachineHandshake<S>) {
        val retired = synchronized(lock) { if (connections[id] === expected) connections.remove(id) else null }
        retired?.close()
    }
    override fun close() {
        val retired = synchronized(lock) {
            if (closed) return
            closed = true
            connections.values.toList().also { connections.clear() }
        }
        retired.forEach { it.close() }
    }
}
