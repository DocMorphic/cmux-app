package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

internal enum class NativeAuthPhase { SEND_CODE, EMAIL_EXCHANGE, OAUTH_EXCHANGE, USER, TEAMS, REFRESH, FORCE_REFRESH, RESTORE_REFRESH, CLEANUP }
internal class NativeAuthDeadlineCancellation : CancellationException("The account request timed out. Please try again.")

/** A phase wait may end before its operation does. Keep the actual job, not its waiter. */
internal class NativeAuthWork(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val retryResetMillis: Long = 30_000,
) {
    internal enum class Admission { EXCLUSIVE, TOKEN, CLEANUP }
    private class Entry(val phase: NativeAuthPhase, val owner: String?, val admission: Admission, val job: Job) {
        var timedOutAt: Long? = null
        var blocksAdmission = true
    }
    private val lock = Any()
    private val active = mutableSetOf<Entry>()
    internal val activeCount: Int get() = synchronized(lock) { active.size }
    internal val cancelledCount: Int get() = synchronized(lock) { active.count { it.job.isCancelled } }

    suspend fun <T> run(phase: NativeAuthPhase, timeoutMillis: Long, owner: String? = null,
        admission: Admission = Admission.TOKEN, operation: suspend () -> T): T {
        require(timeoutMillis > 0)
        val context = currentCoroutineContext()
        context.ensureActive()
        val started = nowMillis()
        val lifetime = SupervisorJob()
        val scope = CoroutineScope(context.minusKey(Job) + lifetime)
        val task = scope.async(start = CoroutineStart.LAZY) {
            val value = operation()
            ensureActive()
            // Token phases carry an absolute deadline; prompt/exchange phases
            // follow the original race winner without an extra expiry verdict.
            if (admission == Admission.TOKEN && nowMillis() - started >= timeoutMillis)
                throw NativeSignInFailure(NativeSignInError.TIMED_OUT)
            value
        }
        val entry = Entry(phase, owner, admission, task)
        if (!register(entry)) {
            lifetime.cancel()
            throw NativeSignInFailure(NativeSignInError.TIMED_OUT)
        }
        task.invokeOnCompletion {
            synchronized(lock) { active -= entry }
            lifetime.cancel()
        }
        task.start()
        try {
            // task is owned by lifetime, not this timeout scope. Awaiting it
            // is cancellable without joining a cancellation-ignoring operation.
            return withTimeout(timeoutMillis) { task.await() }
        } catch (failure: Exception) {
            if (failure is CancellationException) {
                synchronized(lock) { if (entry in active) entry.timedOutAt = nowMillis() }
                task.cancel(if (failure is TimeoutCancellationException && context.isActive)
                    NativeAuthDeadlineCancellation() else CancellationException("Account work cancelled"))
            }
            // Preserve cancellation of the caller; only our deadline is a UI error.
            context.ensureActive()
            if (failure is TimeoutCancellationException) throw NativeSignInFailure(NativeSignInError.TIMED_OUT)
            throw failure
        }
    }

    /** Cleanup remains token work until the conditional store transaction really finishes. */
    fun cleanup(context: CoroutineContext, owner: String, operation: suspend () -> Unit): Job {
        val lifetime = SupervisorJob()
        val task = CoroutineScope(context.minusKey(Job) + lifetime).async(start = CoroutineStart.LAZY) {
            withContext(NonCancellable) { operation() }
        }
        val entry = Entry(NativeAuthPhase.CLEANUP, owner, Admission.CLEANUP, task)
        synchronized(lock) { active += entry }
        task.invokeOnCompletion {
            synchronized(lock) { active -= entry }
            lifetime.cancel()
        }
        task.start()
        return task
    }

    /** Cancel a snapshot and join the operations, including those whose UI wait already ended. */
    suspend fun quiesce(timeoutMillis: Long) {
        val jobs = synchronized(lock) { active.filter { it.phase != NativeAuthPhase.SEND_CODE }.map { it.job } }
        jobs.forEach { it.cancel() }
        try { withTimeout(timeoutMillis) { jobs.forEach { it.join() } } }
        catch (failure: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw NativeSignInFailure(NativeSignInError.TIMED_OUT)
        }
    }

    fun cancelOwners(owners: Set<String>) {
        val jobs = synchronized(lock) { active.filter { it.owner in owners }.map { it.job } }
        jobs.forEach { it.cancel() }
    }
    fun cancelAll() {
        val jobs = synchronized(lock) { active.map { it.job } }
        jobs.forEach { it.cancel() }
    }

    /** An explicit user action releases both dampers, never the actual work handles. */
    fun supersedeTimedOutPhases() = synchronized(lock) {
        active.filter { it.timedOutAt != null }.forEach { it.blocksAdmission = false }
    }

    private fun register(entry: Entry): Boolean = synchronized(lock) {
        active.filter { it.phase == entry.phase }.forEach { old ->
            if (old.admission == Admission.EXCLUSIVE && old.timedOutAt?.let { nowMillis() - it >= retryResetMillis } == true)
                old.blocksAdmission = false
        }
        val conflict = active.any { old -> old.phase == entry.phase && old.blocksAdmission && when (entry.admission) {
            Admission.EXCLUSIVE -> true
            Admission.TOKEN -> old.timedOutAt != null
            Admission.CLEANUP -> false
        } }
        if (conflict) false else { active += entry; true }
    }
}
