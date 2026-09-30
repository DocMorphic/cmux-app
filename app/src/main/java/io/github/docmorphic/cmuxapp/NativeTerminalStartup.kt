package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A create response pins one terminal, never another Mac's matching terminal ID. */
internal class NativeTerminalStartup(private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    data class Pending(val key: NativeWorkspaceTabKey, val terminalId: String, val deadline: Long,
        val id: String = java.util.UUID.randomUUID().toString())
    data class Failure(val key: NativeWorkspaceTabKey, val terminalId: String)
    data class State(val pending: Pending? = null, val failure: Failure? = null)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    fun begin(key: NativeWorkspaceTabKey, terminal: NativeTerminal, startedAt: Long = now()) {
        mutableState.value = State(pending = if (terminal.isReady) null else Pending(key, terminal.id, startedAt + TIMEOUT))
    }
    fun clear() { mutableState.value = State() }
    /** Restore only after this checkpoint's owning Mac and workspace were verified. Never extend a deadline. */
    fun restore(checkpoint: NativeScreenCheckpoint, workspace: NativeWorkspace, bootCount: Int?) {
        require(workspace.id == checkpoint.key.workspaceId)
        val pending = checkpoint.startup?.takeIf { ticket -> workspace.terminals.any { it.id == ticket.terminalId && !it.isReady } }
        val expired = pending != null && (checkpoint.bootCount == null || checkpoint.bootCount != bootCount ||
            now() < checkpoint.savedAt || now() >= pending.deadline)
        val failure = if (expired) Failure(checkpoint.key, pending!!.terminalId) else checkpoint.failure
        mutableState.value = State(pending = pending?.takeUnless { expired }, failure = failure?.takeUnless {
            workspace.terminals.any { terminal -> terminal.id == it.terminalId && terminal.isReady }
        })
    }
    fun cancelPin() { mutableState.value = mutableState.value.copy(pending = null) }
    fun observe(key: NativeWorkspaceTabKey?, terminalId: String?) {
        val state = mutableState.value
        mutableState.value = state.copy(
            pending = state.pending?.takeIf { it.key == key && it.terminalId == terminalId },
            failure = state.failure?.takeIf { it.key == key })
    }
    fun remaining(ticket: Pending): Long = (ticket.deadline - now()).coerceAtLeast(0)
    fun expire(ticket: Pending): Boolean {
        if (mutableState.value.pending != ticket || remaining(ticket) != 0L) return false
        mutableState.value = State(failure = Failure(ticket.key, ticket.terminalId))
        return true
    }
    /** Call only for a validated authoritative snapshot of this owner. Failed reads do not retire a pin. */
    fun reconcile(key: NativeWorkspaceTabKey, workspace: NativeWorkspace?, selected: NativeTerminal?): NativeWorkspacePane? {
        require(workspace == null || workspace.id == key.workspaceId)
        val state = mutableState.value
        val current = workspace?.terminals?.firstOrNull { it.id == selected?.id }
        val pending = state.pending?.takeIf { it.key == key && it.terminalId == current?.id && current?.isReady == false }
        val failed = state.failure?.takeUnless { failure -> failure.key == key &&
            workspace?.terminals?.any { it.id == failure.terminalId && it.isReady } == true }
        mutableState.value = state.copy(pending = if (state.pending?.key == key) pending else state.pending,
            failure = failed)
        if (current != null && (pending != null || current.isReady || workspace?.terminals?.none { it.isReady } == true))
            return NativeWorkspacePane(terminal = current)
        return workspace?.defaultPane()
    }
    companion object {
        const val TIMEOUT = 30_000L
        const val TIMEOUT_MESSAGE = "The new terminal did not finish starting."
    }
}

/** A→B→A is still newer navigation; value equality alone cannot fence a delayed mutation. */
internal class NativeNavigationGeneration {
    private var context: List<Any?>? = null
    private var generation = 0L
    fun observe(value: List<Any?>): Long {
        if (context != value) { context = value; generation++ }
        return generation
    }
    fun matches(expected: Long, value: List<Any?>) = observe(value) == expected
}
