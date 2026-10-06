package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val mutableFailures = MutableStateFlow<Map<String, CloudSessionFailure>>(emptyMap())
    val failures = mutableFailures.asStateFlow()
    private fun owns(id: String, expected: CloudMachineHandshake<S>) =
        !closed && isCurrent() && connections[id] === expected

    /** A result from a retired link cannot clear or replace its successor's status. */
    fun report(id: String, expected: CloudMachineHandshake<S>, failure: CloudSessionFailure?) = synchronized(lock) {
        if (owns(id, expected)) mutableFailures.value = if (failure == null) mutableFailures.value - id
            else mutableFailures.value + (id to failure)
    }

    suspend fun <T> withConnection(id: String, expected: CloudMachineHandshake<S>, reportSuccess: Boolean = true,
        operation: suspend (S) -> T): T {
        fun requireOwner() = synchronized(lock) {
            if (!owns(id, expected)) throw CancellationException("Cloud connection owner changed")
        }
        try {
            requireOwner()
            val session = expected.awaitSession()
            currentCoroutineContext().ensureActive(); requireOwner()
            val result = operation(session)
            currentCoroutineContext().ensureActive(); requireOwner()
            if (reportSuccess) report(id, expected, null)
            return result
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            report(id, expected, CloudSessionFailure.classify(failure, CloudFailureKind.LINK)); throw failure
        } catch (failure: LinkageError) {
            currentCoroutineContext().ensureActive()
            report(id, expected, CloudSessionFailure("Cloud native runtime is unavailable", kind = CloudFailureKind.LINK)); throw failure
        }
    }
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
        val retired = synchronized(lock) {
            mutableFailures.value = mutableFailures.value - ids
            ids.mapNotNull(connections::remove)
        }
        // Do not hold the pool lock while closing: an in-flight handshake can
        // hold its own lock while checking this pool's admission predicate.
        retired.forEach { it.close() }
    }
    fun retire(id: String, expected: CloudMachineHandshake<S>) {
        val retired = synchronized(lock) { if (connections[id] === expected) {
            mutableFailures.value = mutableFailures.value - id
            connections.remove(id)
        } else null }
        retired?.close()
    }
    override fun close() {
        val retired = synchronized(lock) {
            if (closed) return
            closed = true
            mutableFailures.value = emptyMap()
            connections.values.toList().also { connections.clear() }
        }
        retired.forEach { it.close() }
    }
}
