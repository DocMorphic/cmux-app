package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.coroutines.EmptyCoroutineContext

internal data class NativeRefreshOwner(val login:String,val refresh:String,val epoch:Long) {
    override fun toString()="NativeRefreshOwner(redacted)"
    companion object {
        fun from(root:JSONObject?,epoch:Long):NativeRefreshOwner? {
            if(root==null || NativeSignInCompletion.isPending(root))return null
            val login=root.optString("task_session").takeIf(String::isNotBlank) ?: return null
            val refresh=root.optString("refresh_token").takeIf(String::isNotBlank) ?: return null
            return NativeRefreshOwner(login,refresh,epoch)
        }
    }
}
internal data class NativeRefreshView(val owner:NativeRefreshOwner?,val access:String?) {
    override fun toString()="NativeRefreshView(redacted)"
}
internal sealed interface NativeRefreshResult {
    class Success(val access:String):NativeRefreshResult { override fun toString()="Success(redacted)" }
    data object Rejected:NativeRefreshResult
    class Failure(val error:Exception):NativeRefreshResult { override fun toString()="RefreshFailure" }
    data object TimedOut:NativeRefreshResult
    data object Changed:NativeRefreshResult
    data object Cancelled:NativeRefreshResult
}

/** One exchange and failure per encrypted document/environment. No crypto or auth-work IO under lock. */
internal class NativeTokenRefresh(
    private val now:()->Long={System.nanoTime()/1_000_000},
    private val timeoutMillis:Long=30_000,
    private val dispatcher:CoroutineDispatcher=Dispatchers.IO,
    private val sleepUntil:suspend (Long)->Unit={ deadline -> delay((deadline-now()).coerceAtLeast(0)) },
    private val jitter:()->Double={kotlin.random.Random.nextDouble(.5,1.0)},
    private val observe:(NativeRefreshRateLimitObservation)->Unit=NativeRefreshReporting::limited,
    private val recovered:()->Unit=NativeRefreshReporting::recovered,
):AutoCloseable {
    private class Attempt(val owner:NativeRefreshOwner,val deadline:Long) {
        val result=CompletableDeferred<NativeRefreshResult>()
        var waiters=0
        var operation:Job?=null
        var timer:Job?=null
    }
    private val lock=Any()
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    private var active:Attempt?=null
    private var limitedUntil=0L
    private var streak=0
    internal val waiterCount get()=synchronized(lock) { active?.waiters ?: 0 }
    /** May run inside a credential transaction. Retire now, deliver/cancel without nesting auth-work locks. */
    fun ownerChanged(owner:NativeRefreshOwner?) {
        val retired=synchronized(lock) { active?.takeIf { it.owner!=owner }?.also { active=null } }
        if(retired!=null)dispatcher.dispatch(EmptyCoroutineContext,Runnable {
            retired.result.complete(NativeRefreshResult.Changed)
            retired.operation?.cancel();retired.timer?.cancel()
        })
    }
    private fun finish(attempt:Attempt,result:NativeRefreshResult) {
        val won=synchronized(lock) { if(active!==attempt)false else { active=null;true } }
        if(won) {
            attempt.result.complete(result)
            attempt.operation?.cancel();attempt.timer?.cancel()
        }
    }
    private fun check(attempt:Attempt) = synchronized(lock) {
        if(active!==attempt)throw CancellationException("Refresh attempt retired")
        if(now()>=attempt.deadline)throw NativeSignInFailure(NativeSignInError.TIMED_OUT)
    }
    /** Holds only the final preference commit, never encryption, credential reads or job cancellation. */
    private fun commit(attempt:Attempt,operation:()->Boolean):Boolean=synchronized(lock) {
        check(attempt)
        operation()
    }
    suspend fun resolve(owner:NativeRefreshOwner,observedAccess:String?,work:NativeAuthWork,
        read:()->NativeRefreshView,
        apply:(NativeRefreshOwner,String?,((()->Boolean)->Boolean))->Boolean,
        exchange:suspend ()->String,
        expired:(String?)->Boolean,
    ):NativeRefreshResult {
        currentCoroutineContext().ensureActive()
        val view=read()
        if(view.owner!=owner)return NativeRefreshResult.Changed
        var previous:Attempt?=null;var created=false
        val attempt=synchronized(lock) {
            val old=active
            if(old!=null && old.owner!=owner) { active=null;previous=old }
            if(old==null && view.access!=observedAccess && !expired(view.access))return NativeRefreshResult.Success(checkNotNull(view.access))
            (active ?: Attempt(owner,now()+timeoutMillis).also { active=it;created=true }).also { it.waiters++ }
        }
        previous?.let { it.result.complete(NativeRefreshResult.Changed);it.operation?.cancel();it.timer?.cancel() }
        if(created) {
            val operation=scope.launch(start=CoroutineStart.LAZY) {
                val result=try {
                    work.run(NativeAuthPhase.REFRESH_EXCHANGE,timeoutMillis,admission=NativeAuthWork.Admission.SHARED_REFRESH) {
                        perform(attempt,read,apply,exchange)
                    }
                } catch(failure:Exception) { failureResult(failure) }
                finish(attempt,result)
            }
            val timer=scope.launch(start=CoroutineStart.LAZY) { sleepUntil(attempt.deadline);finish(attempt,NativeRefreshResult.TimedOut) }
            val start=synchronized(lock) {
                attempt.operation=operation;attempt.timer=timer
                active===attempt
            }
            if(start) { timer.start();operation.start() } else { timer.cancel();operation.cancel() }
        }
        try { return attempt.result.await() }
        finally {
            val last=synchronized(lock) { if(active===attempt) { attempt.waiters--;attempt.waiters==0 } else false }
            if(last)finish(attempt,NativeRefreshResult.Cancelled)
        }
    }
    private fun failureResult(failure:Exception):NativeRefreshResult=when {
        failure is NativeSignInFailure && failure.reason==NativeSignInError.TIMED_OUT -> NativeRefreshResult.TimedOut
        failure is CancellationException -> NativeRefreshResult.Cancelled
        else -> NativeRefreshResult.Failure(failure)
    }
    private suspend fun perform(attempt:Attempt,read:()->NativeRefreshView,
        apply:(NativeRefreshOwner,String?,((()->Boolean)->Boolean))->Boolean,
        exchange:suspend ()->String):NativeRefreshResult {
        try {
            check(attempt)
            if(synchronized(lock) { now()<limitedUntil })throw NativeRefreshCoolingDown()
            val access=try {
                val value=exchange();currentCoroutineContext().ensureActive();check(attempt)
                require(value.isNotBlank()) { "Empty refresh response" }
                val signal=synchronized(lock) { val was=streak>0;streak=0;limitedUntil=0;was }
                if(signal)runCatching(recovered)
                value
            } catch(failure:NativeRefreshRateLimited) {
                currentCoroutineContext().ensureActive();check(attempt)
                val signal=synchronized(lock) {
                    streak++
                    val seconds=nativeRefreshBackoffSeconds(failure.retryAfterSeconds,streak,jitter())
                    limitedUntil=now()+(seconds*1000).toLong()
                    NativeRefreshRateLimitObservation(failure.bodyKind,failure.retryAfterSeconds,seconds,streak)
                }
                runCatching { observe(signal) };throw failure
            }
            currentCoroutineContext().ensureActive();check(attempt)
            if(read().owner!=attempt.owner)return NativeRefreshResult.Changed
            val context=currentCoroutineContext()
            if(!apply(attempt.owner,access) { action->context.ensureActive();commit(attempt,action) })return NativeRefreshResult.Changed
            check(attempt)
            return if(read()==NativeRefreshView(attempt.owner,access))NativeRefreshResult.Success(access) else NativeRefreshResult.Changed
        } catch(failure:Exception) {
            currentCoroutineContext().ensureActive()
            check(attempt)
            if(failure is NativeAccount.InvalidRefreshToken || failure is NativeSignInFailure && failure.reason==NativeSignInError.UNAUTHORIZED) {
                synchronized(lock) { streak=0;limitedUntil=0 }
                val context=currentCoroutineContext()
                if(!apply(attempt.owner,null) { action->context.ensureActive();commit(attempt,action) })return NativeRefreshResult.Changed
                check(attempt)
                return NativeRefreshResult.Rejected
            }
            return failureResult(failure)
        }
    }
    override fun close() {
        synchronized(lock) { active }?.let { finish(it,NativeRefreshResult.Cancelled) }
        scope.cancel()
    }
}
