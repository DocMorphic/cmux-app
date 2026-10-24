package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

internal enum class NativeAuthPhase { SEND_CODE, EMAIL_EXCHANGE, OAUTH_EXCHANGE, USER, TEAMS, TEAM_LIST, TEAM_SELECTION, REFRESH, FORCE_REFRESH, REFRESH_EXCHANGE, RESTORE_REFRESH, VALIDATE_SESSION, POST_SIGN_IN, ACCOUNT_DELETION, SIGN_OUT, CLEANUP, STATE_CLEAR, SESSION_RESTORE }
internal class NativeAuthDeadlineCancellation : CancellationException("The account request timed out. Please try again.")

/** A phase wait may end before its operation does. Keep the actual job, not its waiter. */
internal class NativeAuthWork(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val retryResetMillis: Long = 30_000,
    private val teardownDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    internal enum class Admission { EXCLUSIVE, TOKEN, SHARED_REFRESH, PHASE, CLEANUP }
    private class Entry(val phase: NativeAuthPhase, val owner: String?, val admission: Admission, val job: Job) {
        var timedOutAt: Long? = null
        var blocksAdmission = true
    }
    private val lock = Any()
    private val active = mutableSetOf<Entry>()
    internal val activeCount: Int get() = synchronized(lock) { active.size }
    internal val cancelledCount: Int get() = synchronized(lock) { active.count { it.job.isCancelled } }

    suspend fun <T> run(phase: NativeAuthPhase, timeoutMillis: Long, owner: String? = null,
        admission: Admission = Admission.TOKEN, finish:()->Unit={}, operation: suspend () -> T): T {
        require(timeoutMillis > 0)
        val context = currentCoroutineContext()
        context.ensureActive()
        val started = nowMillis()
        val lifetime = SupervisorJob()
        val scope = CoroutineScope(context.minusKey(Job) + lifetime)
        val task = scope.async(start = CoroutineStart.LAZY) {
            val result:Result<T> = try {Result.success(operation())} catch(failure:Exception){Result.failure(failure)}
            finish()
            ensureActive()
            // Token phases carry an absolute deadline; prompt/exchange phases
            // follow the original race winner without an extra expiry verdict.
            if (admission in setOf(Admission.TOKEN,Admission.SHARED_REFRESH) && nowMillis() - started >= timeoutMillis)
                throw NativeSignInFailure(NativeSignInError.TIMED_OUT)
            result.getOrThrow()
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
        try {
            return awaitPhaseWinner(scope, task, entry, timeoutMillis)
        } catch (failure: Exception) {
            if (failure is PhaseTimeout || failure is CancellationException) {
                synchronized(lock) { if (entry in active) entry.timedOutAt = nowMillis() }
                task.cancel(if ((failure is PhaseTimeout || failure is TimeoutCancellationException) && context.isActive)
                    NativeAuthDeadlineCancellation() else CancellationException("Account work cancelled"))
            }
            // Preserve cancellation of the caller; only our deadline is a UI error.
            context.ensureActive()
            if (failure is PhaseTimeout || failure is TimeoutCancellationException) throw NativeSignInFailure(NativeSignInError.TIMED_OUT)
            throw failure
        }
    }

    private class PhaseTimeout : Exception()

    /** Settle the race before dispatching the caller; delayed delivery cannot change its winner. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun <T> awaitPhaseWinner(scope: CoroutineScope, task: Deferred<T>, entry: Entry,
        timeoutMillis: Long): T = suspendCancellableCoroutine { continuation ->
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        val timer = scope.launch(start = CoroutineStart.LAZY) {
            delay(timeoutMillis)
            if (settled.compareAndSet(false, true)) {
                synchronized(lock) { if (entry in active) entry.timedOutAt = nowMillis() }
                task.cancel(NativeAuthDeadlineCancellation())
                continuation.resumeWith(Result.failure(PhaseTimeout()))
            }
        }
        continuation.invokeOnCancellation {
            if (settled.compareAndSet(false, true)) {
                timer.cancel()
                task.cancel(CancellationException("Account work cancelled"))
            }
        }
        task.invokeOnCompletion { failure ->
            if (settled.compareAndSet(false, true)) {
                timer.cancel()
                continuation.resumeWith(if (failure != null) Result.failure(failure) else runCatching { task.getCompleted() })
            }
        }
        timer.start()
        task.start()
    }

    // Order is transitionGate -> work monitor; registration callbacks may reenter their already-held gate.
    // Final clear commits may hold the credential monitor, but encryption happens before entering this gate.
    private val transitionGate=Any()
    private val currentSignIns=mutableMapOf<Any,()->Boolean>()
    internal val currentSignInOwnsStore get()=synchronized(transitionGate){currentSignIns.values.any{it()}}
    internal val isCapturingSignOutCredentials get()=signOutCaptures>0
    internal fun <T> registerSessionTransition(operation:()->T):T=synchronized(transitionGate){operation()}
    internal fun <T> commitSessionRead(operation:()->T):T=synchronized(transitionGate){operation()}

    @Volatile private var signOutCaptures = 0
    private val signInFlows = java.util.concurrent.atomic.AtomicInteger(0)
    private val unpublishedSignIns = java.util.concurrent.atomic.AtomicInteger(0)
    /** These reads never acquire the work monitor while a credential transaction is held. */
    val storedSessionCaptureBlocked get() = signOutCaptures > 0 || signInFlows.get() > 0 || unpublishedSignIns.get() > 0
    val sessionWriteInProgress get() = signOutCaptures > 0 || unpublishedSignIns.get() > 0
    internal class SignInLease(private val published: () -> Unit, private val finished: () -> Unit) {
        private val didPublish = java.util.concurrent.atomic.AtomicBoolean(false)
        private val didFinish = java.util.concurrent.atomic.AtomicBoolean(false)
        fun publish() { if (didPublish.compareAndSet(false,true)) published() }
        fun finish() { publish();if (didFinish.compareAndSet(false,true)) finished() }
    }
    /** ownsStore must read only the flow's volatile generation and atomic epoch, without credential IO. */
    fun beginSignIn(ownsStore:()->Boolean={true}): SignInLease=synchronized(transitionGate) {
        val id=Any();currentSignIns[id]=ownsStore
        unpublishedSignIns.incrementAndGet();signInFlows.incrementAndGet()
        SignInLease({ synchronized(transitionGate){unpublishedSignIns.decrementAndGet()} }, {
            synchronized(transitionGate){currentSignIns.remove(id);signInFlows.decrementAndGet()}
        })
    }
    private var captureFinished = CompletableDeferred<Unit>().apply { complete(Unit) }
    fun beginSignOutCapture(): () -> Unit {
        synchronized(transitionGate){synchronized(lock) { if(signOutCaptures++ == 0) captureFinished = CompletableDeferred() }}
        val finished=java.util.concurrent.atomic.AtomicBoolean(false)
        return {
            val wake=if(finished.compareAndSet(false,true))synchronized(transitionGate){synchronized(lock) {
                if(--signOutCaptures==0)captureFinished else null
            }} else null
            wake?.complete(Unit);Unit
        }
    }
    /** Snapshot ownership atomically with the capture gate, without credential IO under this monitor. */
    suspend fun <T> afterSignOutCapture(snapshot:()->T):T {
        while(true) {
            val wait=synchronized(transitionGate){synchronized(lock) { if(signOutCaptures == 0) return snapshot();captureFinished }}
            wait.await()
        }
    }
    /** Shared actual job: a new exchange joins its bounded attempt through quiesce(). */
    fun launchSignOutTeardown(timeoutMillis:Long=5_000, operation:suspend ()->Unit):Job {
        require(timeoutMillis>0)
        val lifetime=SupervisorJob()
        val task=CoroutineScope(teardownDispatcher+lifetime).async(start=CoroutineStart.LAZY) {
            try {
                val hooks=synchronized(lock) { active.filter { it.phase in setOf(NativeAuthPhase.TEAMS,NativeAuthPhase.TEAM_LIST,NativeAuthPhase.POST_SIGN_IN) }.map { it.job } }
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

    /** A caller can stop waiting; actual encrypted clear work stays tracked until its guarded commit finishes. */
    suspend fun <T> clearState(owner:String?,operation:suspend(()->Unit)->T):T {
        val caller=currentCoroutineContext();caller.ensureActive()
        val lifetime=SupervisorJob()
        val task=CoroutineScope(caller.minusKey(Job)+Dispatchers.IO+lifetime).async(start=CoroutineStart.LAZY) {
            val producer=currentCoroutineContext()
            withContext(NonCancellable){operation {caller.ensureActive();producer.ensureActive()}}
        }
        val entry=Entry(NativeAuthPhase.STATE_CLEAR,owner,Admission.CLEANUP,task)
        synchronized(lock){active+=entry}
        task.invokeOnCompletion{synchronized(lock){active-=entry};lifetime.cancel()}
        task.start()
        try{return task.await()}finally{if(!caller.isActive)task.cancel()}
    }

    /** Restoration may retire behind synchronous storage IO; its caller must still be able to cancel its wait. */
    suspend fun <T> restoreSession(dispatcher:CoroutineDispatcher?=null,onFinished:()->Unit={},operation:suspend()->T):T {
        val caller=currentCoroutineContext()
        val lifetime=SupervisorJob()
        val context=caller.minusKey(Job)+lifetime
        val started=java.util.concurrent.atomic.AtomicBoolean(false)
        val task=CoroutineScope(if(dispatcher==null)context else context+dispatcher).async(start=CoroutineStart.LAZY){
            started.set(true)
            try {caller.ensureActive();operation()} finally {onFinished()}
        }
        val entry=Entry(NativeAuthPhase.SESSION_RESTORE,null,Admission.CLEANUP,task)
        synchronized(lock){active+=entry}
        task.invokeOnCompletion{
            // Cancellation before coroutine entry must still retire an admitted validation.
            if(!started.get())runCatching(onFinished)
            synchronized(lock){active-=entry};lifetime.cancel()
        }
        task.start()
        try{return task.await()}finally{if(!caller.isActive)task.cancel()}
    }

    /** Cancel a snapshot and join the operations, including those whose UI wait already ended. */
    suspend fun quiesce(timeoutMillis: Long) {
        val entries = synchronized(lock) { active.filter { it.phase != NativeAuthPhase.SEND_CODE }.toList() }
        // Captured-session teardown gets its own bounded attempt; a fresh login joins it.
        entries.filter { it.phase != NativeAuthPhase.SIGN_OUT }.forEach { it.job.cancel() }
        // Plain team-selection/backend requests cannot publish after retirement.
        // Their token-refresh children remain separately tracked and joined.
        try { withTimeout(timeoutMillis) { entries.filter {it.admission!=Admission.PHASE}.forEach { it.job.join() } } }
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
            Admission.SHARED_REFRESH -> false // The shared registry fences retired writes; retain actual jobs for sign-in joining.
            Admission.PHASE -> false // A plain source phase permits another attempt while a retired operation finishes.
            Admission.CLEANUP -> false
        } }
        if (conflict) false else { active += entry; true }
    }
}
