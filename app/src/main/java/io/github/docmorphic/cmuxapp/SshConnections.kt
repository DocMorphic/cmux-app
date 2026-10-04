package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.UUID

internal enum class SshConnectionPhase { IDLE, CONNECTING, CONNECTED, FAILED }
internal data class SshConnectionStatus(val phase: SshConnectionPhase, val error: String? = null)
internal data class SshPendingTrust(val id: UUID, val question: SshTrustQuestion)

/** One coordinator per login incarnation. View cancellation does not cancel a
 * shared dial; disconnect, account retirement and route changes do. Automatic
 * opens join existing work, but failures require an explicit retry. */
internal class SshConnections<C : SshManagedConnection>(
    private val hosts: SshHostStore,
    lifetime: CoroutineScope,
    private val admitted: () -> Boolean,
    private val dial: suspend (UUID, CoroutineScope, () -> Boolean, suspend (SshTrustQuestion) -> Boolean) -> C,
) : AutoCloseable {
    private val job = SupervisorJob(checkNotNull(lifetime.coroutineContext[Job]))
    private val scope = CoroutineScope(lifetime.coroutineContext + job)
    // Host publication can resume this coordinator inline on Main.immediate while
    // holding the store monitor. Transport guards run on IO and consult the store.
    // Share that monitor so those two paths cannot acquire the same locks in reverse.
    private val lock = hosts
    private var closed = false
    private val entries = mutableMapOf<UUID, Entry>()
    private val status = MutableStateFlow<Map<UUID, SshConnectionStatus>>(emptyMap())
    val statuses = status.asStateFlow()
    private val questions = MutableStateFlow<List<SshPendingTrust>>(emptyList())
    val prompts = questions.asStateFlow()
    private val queue = mutableListOf<Prompt>()

    private inner class Entry(val hostId: UUID, val plan: SshDialPlan) {
        val job = SupervisorJob(this@SshConnections.job)
        val scope = CoroutineScope(this@SshConnections.scope.coroutineContext + job)
        val result = CompletableDeferred<C>()
        var connection: C? = null
    }
    private inner class Waiter(val entry: Entry, val answer: CompletableDeferred<Boolean> = CompletableDeferred())
    private inner class Prompt(val presentation: SshPendingTrust, val waiters: MutableList<Waiter>)

    init {
        scope.launch {
            try { awaitCancellation() } finally { close() }
        }
        scope.launch { hosts.state.collect { reconcile() } }
    }

    private fun allowed() = !closed && job.isActive && admitted()
    private fun current(entry: Entry) = allowed() && entries[entry.hostId] === entry &&
        entry.job.isActive && hosts.isCurrent(entry.plan)
    private fun publish(id: UUID, phase: SshConnectionPhase, error: String? = null) {
        status.value = status.value + (id to SshConnectionStatus(phase, error))
    }
    private fun publishPrompts() { questions.value = queue.map { it.presentation } }

    suspend fun open(hostId: UUID): C = acquire(hostId, explicit = true)!!
    suspend fun autoConnect(hostId: UUID): C? = acquire(hostId, explicit = false)

    private suspend fun acquire(hostId: UUID, explicit: Boolean): C? {
        var replaced: Entry? = null
        var start = false
        val entry = synchronized(lock) {
            check(allowed()) { "Sign in before connecting an SSH computer" }
            if (hosts.state.value.host(hostId) == null && !explicit) return null
            var plan = hosts.dialPlan(hostId)
            if (explicit) for (hop in plan.hops) {
                check(hosts.setAutoConnectPaused(plan, hop.hostId, false)) { "SSH route changed" }
                plan = hosts.dialPlan(hostId)
            }
            val existing = entries[hostId]
            if (existing != null && current(existing) &&
                (existing.connection?.isConnected != false)) existing
            else {
                if (existing != null) { entries.remove(hostId); replaced = existing; removeWaiters(existing) }
                if (!explicit && (status.value[hostId]?.phase == SshConnectionPhase.FAILED || !hosts.mayAutoConnect(plan))) {
                    null
                } else Entry(hostId, plan).also {
                    entries[hostId] = it
                    publish(hostId, SshConnectionPhase.CONNECTING)
                    start = true
                }
            }
        }
        replaced?.let(::dispose)
        if (entry == null) return null
        if (start) launchDial(entry)
        val result = entry.result.await()
        synchronized(lock) {
            check(current(entry) && entry.connection === result && result.isConnected) { "SSH connection ended" }
        }
        return result
    }

    private fun launchDial(entry: Entry) {
        entry.scope.launch {
            var opened: C? = null
            try {
                opened = dial(entry.hostId, entry.scope, { synchronized(lock) { current(entry) } }, { ask(entry, it) })
                synchronized(lock) {
                    check(current(entry) && opened.isConnected && opened.plan == entry.plan) { "SSH connection changed while opening" }
                    entry.connection = opened
                    publish(entry.hostId, SshConnectionPhase.CONNECTED)
                    entry.result.complete(opened)
                }
                val connection = opened
                entry.scope.launch {
                    connection.disconnected.first { it }
                    retire(entry)
                }
            } catch (failure: Throwable) {
                opened?.close()
                synchronized(lock) {
                    if (entries[entry.hostId] === entry) {
                        entries.remove(entry.hostId)
                        val quiet = failure is CancellationException || !hosts.isCurrent(entry.plan) ||
                            !hosts.mayAutoConnect(entry.plan) || !allowed()
                        publish(entry.hostId, if (quiet) SshConnectionPhase.IDLE else SshConnectionPhase.FAILED,
                            if (quiet) null else failure.message ?: "SSH connection failed")
                        removeWaiters(entry)
                    }
                    entry.result.completeExceptionally(failure)
                }
                entry.job.cancel()
            }
        }
    }

    /** Dismissal after a previous answer cannot cancel a replacement question. */
    fun answer(id: UUID, trust: Boolean): Boolean = synchronized(lock) {
        val prompt = queue.firstOrNull()?.takeIf { it.presentation.id == id } ?: return false
        if (!allowed()) return false
        val question = prompt.presentation.question
        val active = prompt.waiters.filter { current(it.entry) &&
            it.entry.plan.hops.any { hop -> hop.hostId == question.hostId && hop.endpoint == question.endpoint } }
        if (active.isEmpty() || hosts.trustSnapshot(question.endpoint) != question.prior) {
            queue.remove(prompt)
            prompt.waiters.forEach { it.answer.cancel(CancellationException("SSH identity question expired")) }
            publishPrompts()
            return false
        }
        queue.remove(prompt)
        // Pause before resuming waiters. A jump refusal pauses that identity for
        // every route that uses it, while leaving the previous pin untouched.
        if (!trust) hosts.setAutoConnectPaused(active.first().entry.plan, question.hostId, true)
        prompt.waiters.forEach { if (it in active) it.answer.complete(trust) else it.answer.cancel() }
        publishPrompts()
        true
    }

    private suspend fun ask(entry: Entry, question: SshTrustQuestion): Boolean {
        val waiter = Waiter(entry)
        synchronized(lock) {
            check(current(entry)) { "SSH connection retired" }
            check(hosts.trustSnapshot(question.endpoint) == question.prior) { "SSH identity question expired" }
            val previous = queue.firstOrNull { it.presentation.question.hostId == question.hostId }
            if (previous != null && previous.presentation.question == question) previous.waiters += waiter
            else {
                if (previous != null) {
                    queue.remove(previous)
                    previous.waiters.forEach { it.answer.cancel(CancellationException("SSH identity question replaced")) }
                }
                queue += Prompt(SshPendingTrust(UUID.randomUUID(), question), mutableListOf(waiter))
            }
            publishPrompts()
        }
        return try { waiter.answer.await() }
        finally {
            synchronized(lock) {
                queue.forEach { it.waiters.remove(waiter) }
                queue.removeAll { it.waiters.isEmpty() }
                publishPrompts()
            }
        }
    }

    /** Explicit disconnect persists until the user opens this computer again. */
    fun disconnect(hostId: UUID) {
        val retired = synchronized(lock) {
            if (hosts.state.value.host(hostId) != null) {
                val plan = hosts.dialPlan(hostId)
                check(hosts.setAutoConnectPaused(plan, hostId, true)) { "SSH route changed" }
            }
            entries.remove(hostId)?.also { removeWaiters(it) }
                .also { publish(hostId, SshConnectionPhase.IDLE) }
        }
        retired?.let(::dispose)
        reconcile() // Also retires routes that use a newly paused jump host.
    }

    private fun retire(entry: Entry) {
        val removed = synchronized(lock) {
            if (entries[entry.hostId] !== entry) false else {
                entries.remove(entry.hostId); removeWaiters(entry)
                publish(entry.hostId, SshConnectionPhase.IDLE); true
            }
        }
        if (removed) dispose(entry)
    }
    private fun reconcile() {
        if (!admitted()) { close(); return }
        val retired = synchronized(lock) {
            entries.values.filter { !current(it) }.also { invalid ->
                invalid.forEach {
                    entries.remove(it.hostId); removeWaiters(it)
                    publish(it.hostId, SshConnectionPhase.IDLE)
                }
                status.value = status.value.filterKeys { hosts.state.value.host(it) != null }
            }
        }
        retired.forEach(::dispose)
    }
    private fun removeWaiters(entry: Entry) {
        queue.forEach { prompt ->
            prompt.waiters.filter { it.entry === entry }.forEach { it.answer.cancel() }
            prompt.waiters.removeAll { it.entry === entry }
        }
        queue.removeAll { it.waiters.isEmpty() }; publishPrompts()
    }
    private fun dispose(entry: Entry) {
        entry.result.cancel(CancellationException("SSH connection retired"))
        entry.job.cancel()
        entry.connection?.close()
    }
    override fun close() {
        val retired = synchronized(lock) {
            if (closed) return
            closed = true
            entries.values.toList().also {
                entries.clear()
                queue.forEach { prompt -> prompt.waiters.forEach { it.answer.cancel() } }
                queue.clear(); publishPrompts(); status.value = emptyMap()
            }
        }
        job.cancel(); retired.forEach(::dispose)
    }
}
