package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

internal enum class NativeAuthPhase { SEND_CODE, EMAIL_EXCHANGE, OAUTH_EXCHANGE, USER, TEAMS, REFRESH, FORCE_REFRESH, RESTORE_REFRESH, VALIDATE_SESSION, POST_SIGN_IN, SIGN_OUT, CLEANUP }
internal class NativeAuthDeadlineCancellation : CancellationException("The account request timed out. Please try again.")

/** A phase wait may end before its operation does. Keep the actual job, not its waiter. */
internal class NativeAuthWork(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val retryResetMillis: Long = 30_000,
    private val teardownDispatcher: CoroutineDispatcher = Dispatchers.IO,
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

    private var signOutCaptures = 0
    private var captureFinished = CompletableDeferred<Unit>().apply { complete(Unit) }
    fun beginSignOutCapture(): () -> Unit {
        synchronized(lock) { if(signOutCaptures++ == 0) captureFinished = CompletableDeferred() }
        val finished=java.util.concurrent.atomic.AtomicBoolean(false)
        return { if(finished.compareAndSet(false,true)) synchronized(lock) {
            if(--signOutCaptures == 0) captureFinished.complete(Unit)
        };Unit }
    }
    /** Snapshot ownership atomically with the capture gate, without credential IO under this monitor. */
    suspend fun <T> afterSignOutCapture(snapshot:()->T):T {
        while(true) {
            val wait=synchronized(lock) { if(signOutCaptures == 0) return snapshot();captureFinished }
            wait.await()
        }
    }
    /** Shared actual job: a new exchange joins its bounded attempt through quiesce(). */
    fun launchSignOutTeardown(timeoutMillis:Long=5_000, operation:suspend ()->Unit):Job {
        require(timeoutMillis>0)
        val lifetime=SupervisorJob()
        val task=CoroutineScope(teardownDispatcher+lifetime).async(start=CoroutineStart.LAZY) {
            try {
                val hooks=synchronized(lock) { active.filter { it.phase in setOf(NativeAuthPhase.TEAMS,NativeAuthPhase.POST_SIGN_IN) }.map { it.job } }
                withTimeoutOrNull(timeoutMillis) { hooks.forEach { it.join() } }
                ensureActive()
                withTimeout(timeoutMillis) { operation() }
            } catch(_:Exception) { /* Best effort; local logout has already committed. */ }
        }
        val entry=Entry(NativeAuthPhase.SIGN_OUT,null,Admission.CLEANUP,task)
        synchronized(lock) { active+=entry }
        task.invokeOnCompletion { synchronized(lock) { active-=entry };lifetime.cancel() }
        task.start()
        return task
    }
    suspend fun awaitSignOutTeardown() {
        val jobs=synchronized(lock) { active.filter { it.phase==NativeAuthPhase.SIGN_OUT }.map { it.job } }
        jobs.forEach { it.join() }
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
        val entries = synchronized(lock) { active.filter { it.phase != NativeAuthPhase.SEND_CODE }.toList() }
        // Captured-session teardown gets its own bounded attempt; a fresh login joins it.
        entries.filter { it.phase != NativeAuthPhase.SIGN_OUT }.forEach { it.job.cancel() }
        try { withTimeout(timeoutMillis) { entries.forEach { it.job.join() } } }
        catch (failure: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw NativeSignInFailure(NativeSignInError.TIMED_OUT)
        }
    }

    fun cancelOwners(owners: Set<String>) {
        val jobs = synchronized(lock) { active.filter { it.owner in owners }.map { it.job } }
        jobs.forEach { it.cancel() }
    }
    fun cancelAll(includingSignOut: Boolean = false) {
        val jobs = synchronized(lock) { active.filter { includingSignOut || it.phase != NativeAuthPhase.SIGN_OUT }.map { it.job } }
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
