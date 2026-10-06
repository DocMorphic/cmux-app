package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal class PhoneHelperMaintenanceRecovery(
    private val transaction: ((PhoneHelperMaintenanceQueue) -> Unit) -> Unit,
    private val inspect: ((PhoneHelperMaintenanceQueue) -> Unit) -> Unit,
    private val send: suspend (String, () -> Boolean, String, JSONObject) -> PhoneHelperHttpResult,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun runPass(): Boolean {
        var pending = emptyList<QueuedHelperMaintenance>()
        transaction { pending = it.pending().take(8) }
        for (item in pending) {
            currentCoroutineContext().ensureActive()
            if (item.retryAt > now()) continue
            fun current(step: String? = null): Boolean {
                var result = false; inspect { result = it.current(item, step) }; return result
            }
            if (!current()) continue
            try {
                item.session({ current() }, now).use { session ->
                    var response = item.response
                    if (response == null) {
                        when (val result = send(session.endpoint, { current("maintain.begin") }, "maintain.begin", session.begin())) {
                            is PhoneHelperHttpResult.Success -> {
                                session.finish(result.body)
                                transaction { it.checkpoint(item, result.body) }
                                response = result.body
                            }
                            else -> { handle(item, result); return@use }
                        }
                    }
                    if (!current()) return@use
                    val proof = session.finish(checkNotNull(response), retry = item.response != null)
                    var aborting = false
                    transaction { queue -> aborting = queue.pending().singleOrNull { it.id == item.id && it.requestID == item.requestID }?.aborting == true }
                    val step = if (aborting) "maintain.abort" else "maintain.finish"
                    when (val result = send(session.endpoint, { current(step) }, step, proof)) {
                        is PhoneHelperHttpResult.Success -> {
                            val receipt = if (aborting) session.confirmAbort(result.body) else session.confirm(result.body)
                            transaction { it.complete(item, receipt, aborted = aborting) }
                        }
                        else -> handle(item, result)
                    }
                }
            } catch (_: IllegalArgumentException) {
                transaction { it.reject(item) }
            } catch (failure: IllegalStateException) {
                currentCoroutineContext().ensureActive()
                if (current()) throw failure // Storage failures preserve the operation for a worker retry.
            }
        }
        var remaining = false; transaction { remaining = it.pending().isNotEmpty() }
        return remaining
    }
    private fun handle(item: QueuedHelperMaintenance, result: PhoneHelperHttpResult) = transaction { queue ->
        when (result) {
            is PhoneHelperHttpResult.Retry -> queue.retry(item, result.notBeforeMillis)
            is PhoneHelperHttpResult.Rejected -> if (result.status == 428) queue.restartChallenge(item) else queue.reject(item)
            PhoneHelperHttpResult.Retired -> Unit // Reconciliation selects abort, replacement or retirement before the next pass.
            is PhoneHelperHttpResult.Success -> error("Unexpected maintenance result")
        }
    }
}
