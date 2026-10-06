/* Catalog ownership follows CloudWorkspaceBridge at cmux c2715faa.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CloudWorkspaceSnapshot(val machine: CloudMachine,
    val catalog: CloudWorkspaceCatalog = CloudWorkspaceCatalog(emptyList(), emptyList()),
    val availability: NativeFeedAvailability = NativeFeedAvailability.CONNECTING,
    val authoritative: Boolean = false, val failure: CloudSessionFailure? = null,
    val catalogRevision: Long = 0) {
    val rows get() = projectCloudWorkspaces(machine, catalog)
}

/** UI-dispatcher account owner; observers never own catalog reads or machine links. */
internal class CloudWorkspaceController(parent: CoroutineScope, private val isCurrent: () -> Boolean,
    private val load: suspend (String) -> CloudWorkspaceCatalog) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private var closed = false
    private var available = false
    private var machines = emptyList<CloudMachine>()
    private val reads = mutableMapOf<String, Job>()
    private val revisions = mutableMapOf<String, Long>()
    private var revision = 0L
    private var machineEpoch = 0L
    private val machineEpochs = mutableMapOf<String, Long>()
    private val lastCatalogs = mutableMapOf<String, CloudWorkspaceCatalog>()
    private val mutable = MutableStateFlow<Map<String, CloudWorkspaceSnapshot>>(emptyMap())
    val state = mutable.asStateFlow()
    private fun current() = !closed && job.isActive && isCurrent()
    private fun admitted(id: String, at: Long) = current() && available && revisions[id] == at &&
        machines.any { it.id == id && it.lifecycle == CloudMachineLifecycle.RUNNING }
    fun setMachines(next: List<CloudMachine>) {
        if (!current() || machines == next) return
        require(next.map { it.id }.distinct().size == next.size)
        val previous = machines.associateBy { it.id }
        machines = next
        machineEpochs.keys.retainAll(next.map { it.id }.toSet())
        next.forEach { if (previous[it.id]?.lifecycle != it.lifecycle || it.id !in machineEpochs) machineEpochs[it.id] = ++machineEpoch }
        val ids = next.map { it.id }.toSet()
        reads.keys.toList().filter { it !in ids }.forEach(::cancelRead)
        lastCatalogs.keys.retainAll(ids)
        mutable.value = next.associate { machine -> machine.id to
            (mutable.value[machine.id]?.copy(machine = machine) ?: CloudWorkspaceSnapshot(machine)) }
        next.filter { previous[it.id] != it }.forEach { refresh(it.id) }
    }
    fun setAvailable(value: Boolean) {
        if (!current() || available == value) return
        available = value
        if (value) refreshAll() else {
            reads.keys.toList().forEach(::cancelRead)
            machines.forEach { publishRetained(it) }
        }
    }
    fun refreshAll() { if (current()) machines.forEach { refresh(it.id) } }
    private fun cancelRead(id: String) {
        revisions[id] = ++revision
        reads.remove(id)?.cancel()
    }
    private fun publish(snapshot: CloudWorkspaceSnapshot) {
        if (current() && machines.any { it.id == snapshot.machine.id })
            mutable.value = mutable.value + (snapshot.machine.id to snapshot)
    }
    private fun publishRetained(machine: CloudMachine, failure: CloudSessionFailure? = null) {
        if (machine.lifecycle != CloudMachineLifecycle.RUNNING) {
            publish(CloudWorkspaceSnapshot(machine, availability = NativeFeedAvailability.OFFLINE, authoritative = true))
        } else publish(CloudWorkspaceSnapshot(machine, lastCatalogs[machine.id] ?: CloudWorkspaceCatalog(emptyList(), emptyList()),
            if (failure == null) NativeFeedAvailability.CONNECTING else NativeFeedAvailability.OFFLINE, false, failure,
            mutable.value[machine.id]?.catalogRevision ?: 0))
    }
    fun refresh(id: String) {
        if (!current()) return
        val machine = machines.singleOrNull { it.id == id } ?: return
        cancelRead(id)
        if (!available || machine.lifecycle != CloudMachineLifecycle.RUNNING) { publishRetained(machine); return }
        val at = revisions.getValue(id)
        val task = scope.launch(start = CoroutineStart.LAZY) {
            var failures = 0
            try {
                while (admitted(id, at)) {
                    try {
                        val catalog = load(id)
                        ensureActive()
                        if (!admitted(id, at)) return@launch
                        lastCatalogs[id] = catalog
                        publish(CloudWorkspaceSnapshot(machines.single { it.id == id }, catalog, NativeFeedAvailability.CONNECTED, true, catalogRevision = at))
                        return@launch
                    } catch (failure: Exception) {
                        ensureActive()
                        if (!admitted(id, at)) return@launch
                        publishRetained(machines.single { it.id == id }, CloudSessionFailure.classify(failure, CloudFailureKind.LINK))
                        failures = (failures + 1).coerceAtMost(5)
                        delay(retryDelay(failures))
                    }
                }
            } finally { if (revisions[id] == at) reads.remove(id) }
        }
        reads[id] = task; task.start()
    }
    fun creationEpoch(id: String): Long? = machineEpochs[id]?.takeIf {
        current() && machines.any { it.id == id && it.lifecycle == CloudMachineLifecycle.RUNNING }
    }
    fun canCreate(id: String, workspaceId: String? = null): Boolean {
        val snapshot = mutable.value[id] ?: return false
        return creationEpoch(id) != null && available && snapshot.authoritative && snapshot.availability == NativeFeedAvailability.CONNECTED &&
            (workspaceId == null || snapshot.rows.any { it.remoteId == workspaceId })
    }
    fun publishCreatedWorkspace(id: String, workspaceId: String) {
        if (creationEpoch(id) == null) return
        val machine = machines.single { it.id == id }
        cancelRead(id)
        val previous = lastCatalogs[id] ?: CloudWorkspaceCatalog(emptyList(), emptyList())
        val updated = if (previous.workspaces.any { it.id == workspaceId }) previous else
            previous.copy(workspaces = previous.workspaces + CloudWorkspaceSummary(workspaceId))
        lastCatalogs[id] = updated
        publish(CloudWorkspaceSnapshot(machine, updated, NativeFeedAvailability.CONNECTED, false))
    }
    /** One fresh read after an acknowledged mutation; ordinary retry owns later recovery. */
    suspend fun reloadAfterCreation(id: String): CloudWorkspaceCatalog? {
        val epoch = creationEpoch(id) ?: return null
        if (!available) return null
        cancelRead(id)
        val at = revisions.getValue(id)
        return try {
            val loaded = load(id)
            currentCoroutineContext().ensureActive()
            if (!admitted(id, at) || creationEpoch(id) != epoch) null else {
                lastCatalogs[id] = loaded
                publish(CloudWorkspaceSnapshot(machines.single { it.id == id }, loaded, NativeFeedAvailability.CONNECTED, true, catalogRevision = at))
                loaded
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            if (admitted(id, at) && creationEpoch(id) == epoch) {
                publishRetained(machines.single { it.id == id }, CloudSessionFailure.classify(failure, CloudFailureKind.LINK))
                refresh(id)
            }
            null
        }
    }
    override fun close() {
        if (closed) return
        closed = true; available = false
        reads.keys.toList().forEach(::cancelRead)
        job.cancel(); machines = emptyList(); lastCatalogs.clear(); revisions.clear(); machineEpochs.clear(); mutable.value = emptyMap()
    }
    companion object {
        fun retryDelay(failures: Int) = minOf(5_000L shl (failures - 1).coerceIn(0, 4), 60_000L)
    }
}

/** Old daemons can answer the lists even when snapshot is unavailable. Cancellation is never a fallback. */
internal suspend fun loadCloudWorkspaceCatalog(read: suspend (CloudCatalogOperation) -> ByteArray): CloudWorkspaceCatalog {
    try { return CloudWorkspaceDecoding.snapshot(read(CloudCatalogOperation.SNAPSHOT)) }
    catch (failure: CancellationException) { throw failure }
    catch (_: Exception) { currentCoroutineContext().ensureActive() }
    return coroutineScope {
        val workspaces = async { CloudWorkspaceDecoding.workspaces(read(CloudCatalogOperation.WORKSPACES)) }
        val terminals = async { CloudWorkspaceDecoding.terminals(read(CloudCatalogOperation.TERMINALS)) }
        CloudWorkspaceCatalog(workspaces.await(), terminals.await())
    }
}

internal suspend fun CloudNativeSession.loadWorkspaceCatalog(): CloudWorkspaceCatalog = loadCloudWorkspaceCatalog { operation ->
    withContext(Dispatchers.IO) { catalog(operation) }
}
