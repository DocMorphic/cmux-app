package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CloudSessionFailure(val detail: String, val status: Int? = null, val action: String? = null, val signedOut: Boolean = false) {
    val retryable get() = !signedOut && (status == null || status >= 500 || status == 408 || status == 429)
    companion object {
        fun classify(failure: Exception) = when (failure) {
            is CloudNotSignedIn -> CloudSessionFailure(failure.message!!, signedOut = true)
            is CloudApiFailure -> CloudSessionFailure(failure.message.orEmpty(), failure.status, failure.action, failure.unauthorized)
            else -> CloudSessionFailure(failure.message?.take(2048) ?: "Cloud is unavailable. Try again.")
        }
    }
}
internal enum class CloudMachineAction {
    PAUSE, RESUME, DELETE;
    fun allows(machine: CloudMachine) = when (this) {
        PAUSE -> machine.lifecycle.canPause
        RESUME -> machine.lifecycle.canResume
        DELETE -> machine.lifecycle.canDelete
    }
}
internal data class CloudMachineActionFailure(val machineId: String, val action: CloudMachineAction, val failure: CloudSessionFailure)
internal enum class CloudCatalogPhase { IDLE, LOADING, LOADED, FAILED }
internal data class CloudMachinesState(
    val phase: CloudCatalogPhase = CloudCatalogPhase.IDLE,
    val catalog: CloudMachineCatalog = CloudMachineCatalog(emptyList(), null, null),
    val failure: CloudSessionFailure? = null,
    val creating: Boolean = false, val createFailure: CloudSessionFailure? = null,
    val actions: Set<String> = emptySet(), val actionFailure: CloudMachineActionFailure? = null
)

/** One retained account/team owner. Invoke on its UI dispatcher, including close.
 * Observers can cancel their await without cancelling an admitted mutation.
 * Account retirement closes the service and cancels every owned job.
 */
internal class CloudMachinesController(
    parent: CoroutineScope, private val service: CloudMachinesService, private val journal: CloudCreateJournal,
    private val isCurrent: () -> Boolean,
    private val retireConnections: (Set<String>) -> Unit = {},
    private val provisioningPollLimit: Int = 60, private val listRetryLimit: Int = 8,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutable = MutableStateFlow(CloudMachinesState())
    val state = mutable.asStateFlow()
    private var closed = false
    private var foreground = true
    private var generation = 0L
    private var listJob: Job? = null
    private var nextRead: Job? = null
    private var failures = 0
    private var polls = 0

    init { require(provisioningPollLimit >= 0 && listRetryLimit > 0) }
    private fun current() = !closed && job.isActive && isCurrent()
    private fun requireCurrent() { if (!current()) throw CancellationException("Cloud account changed") }

    fun refresh() = refresh(resetBudget = true)
    private fun refresh(resetBudget: Boolean) {
        if (!current()) return
        if (resetBudget) polls = 0
        nextRead?.cancel(); nextRead = null
        listJob?.cancel()
        val attempt = ++generation
        val previous = mutable.value.catalog.machines.associateBy { it.id }
        mutable.value = mutable.value.copy(phase = CloudCatalogPhase.LOADING, failure = null)
        listJob = scope.launch {
            try {
                val catalog = service.catalog()
                ensureActive(); requireCurrent()
                if (attempt != generation) return@launch
                val live = catalog.machines.filter { it.lifecycle != CloudMachineLifecycle.DESTROYED }
                val rows = live.associateBy { it.id }
                val retired = previous.keys.filter { rows[it] == null || rows[it]?.lifecycle != previous[it]?.lifecycle }.toSet()
                if (retired.isNotEmpty()) retireConnections(retired)
                failures = 0
                mutable.value = mutable.value.copy(phase = CloudCatalogPhase.LOADED, catalog = catalog.copy(machines = live), failure = null)
                scheduleProvisioning()
            } catch (failure: Exception) {
                if (failure is CancellationException || !current() || attempt != generation) return@launch
                val reason = CloudSessionFailure.classify(failure)
                failures++
                val retry = reason.retryable && failures < listRetryLimit
                if (!(retry && failures == 1 && mutable.value.catalog.machines.isEmpty()))
                    mutable.value = mutable.value.copy(phase = CloudCatalogPhase.FAILED, failure = reason)
                if (retry) schedule(retryDelay(failures))
            }
        }
    }

    fun setForeground(value: Boolean) {
        if (!current() || foreground == value) return
        foreground = value
        if (!value) { nextRead?.cancel(); nextRead = null; return }
        val state = mutable.value
        if (state.phase == CloudCatalogPhase.LOADING || state.failure?.retryable == true) refresh()
        else scheduleProvisioning()
    }

    private fun scheduleProvisioning() {
        nextRead?.cancel(); nextRead = null
        if (mutable.value.catalog.machines.none { it.lifecycle == CloudMachineLifecycle.PROVISIONING }) { polls = 0; return }
        if (polls >= provisioningPollLimit) {
            mutable.value = mutable.value.copy(phase = CloudCatalogPhase.FAILED,
                failure = CloudSessionFailure("The machine is still provisioning after the retry window.", action = "Refresh to check again."))
            return
        }
        if (foreground) { polls++; schedule(5000) }
    }
    private fun schedule(milliseconds: Long) {
        nextRead?.cancel(); nextRead = null
        if (!foreground || !current()) return
        nextRead = scope.launch { sleep(milliseconds); ensureActive(); nextRead = null; refresh(resetBudget = false) }
    }

    /** Call only for an explicit user create intent. A retry with identical effective options keeps its key. */
    fun create(options: CloudMachineCreateOptions): Deferred<CloudMachine?>? {
        if (!current() || mutable.value.creating) return null
        mutable.value = mutable.value.copy(creating = true, createFailure = null)
        return scope.async {
            try {
                val normalized = options.normalized()
                val key = journal.resolve(normalized)
                ensureActive(); requireCurrent()
                val machine = service.create(normalized, key)
                ensureActive(); requireCurrent()
                journal.complete(key)
                ensureActive(); requireCurrent()
                refresh()
                machine
            } catch (failure: Exception) {
                if (failure is CancellationException || !current()) return@async null
                mutable.value = mutable.value.copy(createFailure = CloudSessionFailure.classify(failure))
                // A timed-out create may already exist; reconcile, but never resubmit it automatically.
                refresh()
                null
            }
        }.also { operation -> operation.invokeOnCompletion {
            if (current()) mutable.value = mutable.value.copy(creating = false)
        } }
    }

    fun act(id: String, action: CloudMachineAction): Deferred<Boolean>? {
        if (!current() || id in mutable.value.actions) return null
        val machine = mutable.value.catalog.machines.singleOrNull { it.id == id } ?: return null
        if (!action.allows(machine)) return null
        if (action == CloudMachineAction.DELETE) retireConnections(setOf(id))
        mutable.value = mutable.value.copy(actions = mutable.value.actions + id,
            actionFailure = mutable.value.actionFailure?.takeUnless { it.machineId == id })
        return scope.async {
            try {
                requireCurrent()
                when (action) {
                    CloudMachineAction.PAUSE -> service.pause(id)
                    CloudMachineAction.RESUME -> service.resume(id)
                    CloudMachineAction.DELETE -> service.delete(id)
                }
                ensureActive(); requireCurrent()
                refresh()
                true
            } catch (failure: Exception) {
                if (failure is CancellationException || !current()) return@async false
                mutable.value = mutable.value.copy(actionFailure = CloudMachineActionFailure(id, action, CloudSessionFailure.classify(failure)))
                refresh()
                false
            }
        }.also { operation -> operation.invokeOnCompletion {
            if (current()) mutable.value = mutable.value.copy(actions = mutable.value.actions - id)
        } }
    }
    fun clearActionFailure() { if (current()) mutable.value = mutable.value.copy(actionFailure = null) }
    override fun close() {
        if (closed) return
        closed = true; generation++
        job.cancel(); service.close()
        retireConnections(mutable.value.catalog.machines.map { it.id }.toSet())
        mutable.value = CloudMachinesState()
    }
    companion object {
        internal fun retryDelay(failures: Int): Long = if (failures <= 1) 2000 else minOf(5000L shl (failures - 2).coerceIn(0, 4), 60_000)
    }
}
