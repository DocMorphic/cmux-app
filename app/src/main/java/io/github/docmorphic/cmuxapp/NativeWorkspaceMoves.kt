/* Ordering/reconciliation derived from cmux MobileWorkspaceOptimisticOrder/Reconciler,
 * revision 4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class NativeWorkspaceOrder(
    val entries: List<NativeWorkspaceOrderEntry>, val groupPins: Map<String, Boolean>,
    val groupAnchors: Map<String, String?>? = null
) {
    constructor(workspaces: List<NativeWorkspace>, groups: List<NativeGroup>) : this(
        workspaces.map { NativeWorkspaceOrderEntry(it.id, it.groupId, it.isPinned) }, groups.associate { it.id to it.isPinned },
        groups.associate { it.id to it.liveAnchorWorkspaceId })

    fun materialize(authoritative: List<NativeWorkspace>): List<NativeWorkspace> {
        val byId = authoritative.associateBy { it.id }
        val result = entries.mapNotNull { entry -> byId[entry.id]?.copy(groupId = entry.groupId) }.toMutableList()
        val present = result.map { it.id }.toMutableSet()
        authoritative.forEachIndexed { index, row ->
            if (row.id !in present) {
                val previous = authoritative.take(index).lastOrNull { it.id in present }
                val next = authoritative.drop(index + 1).firstOrNull { it.id in present }
                val position = when {
                    previous != null -> result.indexOfFirst { it.id == previous.id } + 1
                    next != null -> result.indexOfFirst { it.id == next.id }
                    else -> result.size
                }
                result.add(position, row); present.add(row.id)
            }
        }
        return result
    }

    fun matches(workspaces: List<NativeWorkspace>, groups: List<NativeGroup>): Boolean {
        if (workspaces.any { row -> entries.firstOrNull { it.id == row.id }?.pinned?.let { it != row.isPinned } == true }) return false
        if (groups.any { groupPins[it.id]?.let { pin -> pin != it.isPinned } == true }) return false
        return NativeWorkspaceOrder(materialize(workspaces), emptyList()).entries == NativeWorkspaceOrder(workspaces, emptyList()).entries
    }
}

internal data class NativeWorkspaceOptimism(
    val order: NativeWorkspaceOrder? = null, val bases: List<NativeWorkspaceOrder> = emptyList()
) {
    fun reconcile(source: NativeFeedSource): NativeWorkspaceOptimism {
        val prediction = order ?: return NativeWorkspaceOptimism()
        if (prediction.matches(source.workspaces, source.groups)) return NativeWorkspaceOptimism()
        val index = bases.indexOfFirst { it.matches(source.workspaces, source.groups) }
        return if (index < 0) NativeWorkspaceOptimism() else copy(bases = bases.drop(index))
    }
}

internal fun NativeFeedSource.canReorderWorkspaces(): Boolean =
    availability == NativeFeedAvailability.CONNECTED && "workspace.move.v1" in capabilities &&
        workspaces.isNotEmpty() && workspaces.all { !it.windowId.isNullOrBlank() } && workspaces.map { it.windowId }.distinct().size == 1

internal data class NativeWorkspaceMoveStatus(val pending: Int = 0, val error: String? = null)

/** UI-scope, bounded per-owner move chains. Host snapshots retain all live row content. */
internal class NativeWorkspaceMoves(private val scope: CoroutineScope, private val coordinator: NativeFeedCoordinator) {
    private class Chain(val mac: NativeCredentialStore.PairedMac) {
        var epoch = 0L
        var pending = 0
        var optimism = NativeWorkspaceOptimism()
        var tail: Deferred<Result<Unit>>? = null
        var error: String? = null
    }
    private val chains = mutableMapOf<String, Chain>()
    private var authoritative = emptyMap<String, NativeFeedSource>()
    private val mutableSources = MutableStateFlow<Map<String, NativeFeedSource>>(emptyMap())
    val sources = mutableSources.asStateFlow()
    private val mutableStatus = MutableStateFlow<Map<String, NativeWorkspaceMoveStatus>>(emptyMap())
    val status = mutableStatus.asStateFlow()
    init { scope.launch { coordinator.sources.collect { update(it) } } }

    private fun update(sources: Map<String, NativeFeedSource>) {
        authoritative = sources
        chains.keys.toList().forEach { origin ->
            val chain = chains.getValue(origin)
            val source = sources[origin]
            if (source?.mac != chain.mac) { chain.epoch++; chain.tail?.cancel(); chains.remove(origin) }
            else {
                val next = if (source.availability != NativeFeedAvailability.CONNECTED) NativeWorkspaceOptimism()
                    else chain.optimism.reconcile(source)
                if (chain.optimism.order != null && next.order == null) { chain.epoch++; chain.tail = null }
                chain.optimism = next
            }
        }
        publish()
    }
    private fun publish() {
        mutableSources.value = authoritative.mapValues { (origin, source) ->
            val prediction = chains[origin]?.optimism?.order
            if (prediction == null) source else source.copy(workspaces = prediction.materialize(source.workspaces))
        }
        mutableStatus.value = chains.mapValues { NativeWorkspaceMoveStatus(it.value.pending, it.value.error) }
    }
    fun clear() {
        chains.values.forEach { it.epoch++; it.tail?.cancel() }; chains.clear()
        authoritative = emptyMap(); publish()
    }
    fun enqueue(source: NativeFeedSource, movedId: String, proposed: NativeWorkspaceMove): Boolean =
        enqueueTask(source, movedId, proposed) { true } != null

    /** Browser callers need a host acknowledgement, not merely admission to the optimistic queue. */
    suspend fun submit(source: NativeFeedSource, movedId: String, proposed: NativeWorkspaceMove, canSend: () -> Boolean) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val task = checkNotNull(enqueueTask(source, movedId, proposed) { caller.isActive && canSend() }) {
            "Workspace move changed or the move queue is full. Refresh the sidebar."
        }
        try { task.await().getOrThrow() }
        finally { if (!caller.isActive) task.cancel() }
    }

    private fun enqueueTask(source: NativeFeedSource, movedId: String, proposed: NativeWorkspaceMove,
        canSend: () -> Boolean): Deferred<Result<Unit>>? {
        if (!scope.isActive || !canSend()) return null
        val current = mutableSources.value[source.mac.origin] ?: return null
        if (current.mac != source.mac || !current.canReorderWorkspaces() ||
            !NativeWorkspaceOrder(source.workspaces, source.groups).matches(current.workspaces, current.groups)) return null
        val chain = chains.getOrPut(source.mac.origin) { Chain(source.mac) }
        if (chain.pending >= 3) return null
        val policy = NativeWorkspaceMovePolicy(current.workspaces, current.groups)
        val intent = policy.normalized(proposed, movedId) ?: return null
        val base = NativeWorkspaceOrder(current.workspaces, current.groups)
        val predicted = NativeWorkspaceOrder(policy.applying(intent, movedId), current.groups)
        chain.optimism = NativeWorkspaceOptimism(predicted, chain.optimism.bases + base)
        chain.error = null; chain.pending++
        val previous = chain.tail
        val epoch = chain.epoch
        fun currentChain() = chains[source.mac.origin] === chain && chain.epoch == epoch
        publish()
        // Enter the try/finally immediately: cancellation before dispatch must also release
        // the pending slot and discard its prediction. This task still belongs to the UI scope.
        val task = scope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                previous?.await()?.getOrThrow()
                check(currentChain()) { "Workspace order changed. Refresh the sidebar." }
                coordinator.moveWorkspace(source.mac, movedId, intent, base) { currentChain() && canSend() }
                Result.success(Unit)
            } catch (failure: Exception) {
                if (currentChain()) {
                    chain.epoch++; chain.optimism = NativeWorkspaceOptimism(); chain.tail = null
                    if (failure !is CancellationException) chain.error = failure.message ?: "Could not move this workspace."
                }
                recordWorkspaceActionFailure(failure)
                Result.failure<Unit>(failure)
            } finally { chain.pending--; if (chains[source.mac.origin] === chain) publish() }
        }
        if (currentChain()) chain.tail = task
        return task
    }
}
