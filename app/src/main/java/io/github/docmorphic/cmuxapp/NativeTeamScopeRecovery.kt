package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.math.ceil

internal interface NativeSessionRecoveryObserver {
    fun preserved(generation:Long)
    fun validationFailed(generation:Long,error:Exception)
    fun validationCompleted()
    fun signedIn()
    fun cleared()
}

/** One process-owned recovery loop. Reads and retry policy are injected, not UI polling. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class NativeTeamScopeRecovery(
    private val scope:CoroutineScope,
    private val generation:()->Long,
    private val authenticated:()->Boolean,
    private val membershipLoaded:()->Boolean,
    private val activeMembershipReads:()->Int,
    private val validate:suspend (admit:()->Boolean)->Unit,
    private val nowMillis:()->Long={System.nanoTime()/1_000_000},
    private val jitter:()->Double={kotlin.random.Random.nextDouble(.5,1.0)},
    private val observe:(NativeSessionCheckObservation)->Unit=NativeSessionCheckReporting::limited,
    private val succeeded:()->Unit=NativeSessionCheckReporting::succeeded,
    private val lock:Any=Any(),
) : NativeSessionRecoveryObserver,AutoCloseable {
    private class Pending(val wake:Channel<Unit>) {lateinit var job:Job}
    private data class Limit(val streak:Int,val seconds:Double,val notBefore:Long)
    private var pending:Pending?=null
    private var limit:Limit?=null
    private var closed=false
    val hasPendingRecovery get()=synchronized(lock){pending!=null}
    val hasRateLimit get()=synchronized(lock){limit!=null}
    val needsRecovery get()=synchronized(lock){needs()}
    private fun needs()=!closed && authenticated() && activeMembershipReads()==0 && !membershipLoaded()
    override fun preserved(generation:Long) {schedule(generation)}
    override fun validationFailed(generation:Long,error:Exception) {noteFailure(generation,error)}
    override fun validationCompleted() {reset(onlyIfLoaded=true)}
    override fun signedIn()=reset()
    override fun cleared()=reset()
    fun membershipCompleted(identity:NativeAuthIdentity?,error:Exception?) {
        if(identity==null)return
        if(error==null) {
            val accepted=synchronized(lock){if(closed || identity.generation!=generation())false else {limit=null;true}}
            if(accepted)runCatching(succeeded)
        } else if(noteFailure(identity.generation,error))schedule(identity.generation)
    }
    private fun noteFailure(expected:Long,error:Exception):Boolean {
        val observation=synchronized(lock) {
            if(closed || expected!=generation())return false
            val rate=nativeSessionCheckLimit(error)
            if(rate==null) {limit=null;null} else {
                val streak=(limit?.streak ?: 0).let {if(it==Int.MAX_VALUE)it else it+1}
                val seconds=nativeTeamRateLimitDelaySeconds(rate.retryAfterSeconds,streak,jitter())
                limit=Limit(streak,seconds,nowMillis()+ceil(seconds*1000).toLong())
                NativeSessionCheckObservation(rate.endpoint,rate.bodyKind,rate.retryAfterSeconds,seconds,streak)
            }
        }
        observation?.let {runCatching {observe(it)}}
        return true
    }
    private fun schedule(expected:Long) {
        val start=synchronized(lock) {
            if(expected!=generation() || !needs() || pending!=null)return
            Pending(Channel(Channel.CONFLATED)).also { entry ->
                entry.job=scope.launch(start=CoroutineStart.LAZY){run(entry)};pending=entry
            }
        }
        start.job.start()
    }
    private suspend fun waitUntil(entry:Pending,deadline:Long) {
        while(true) {
            currentCoroutineContext().ensureActive()
            val remaining=synchronized(lock) {
                if(closed || pending!==entry)throw CancellationException("Recovery retired")
                // A newer throttle cannot be bypassed by an already-sleeping retry.
                maxOf(deadline,limit?.notBefore ?: deadline)-nowMillis()
            }
            if(remaining<=0)return
            select<Unit> {onTimeout(remaining){};entry.wake.onReceiveCatching{}}
        }
    }
    private suspend fun run(entry:Pending) {
        var attempt=0
        try {
            while(true) {
                val wait=synchronized(lock){maxOf(nativeTeamRecoveryDelayMillis(attempt),ceil((limit?.seconds ?: 0.0)*1000).toLong())}
                waitUntil(entry,nowMillis()+wait)
                if(synchronized(lock){pending!==entry || !needs()})break
                attempt=(attempt+1).coerceAtMost(6)
                try {validate {synchronized(lock){pending===entry && needs() &&
                    (limit?.notBefore?.let {it<=nowMillis()} ?: true)}}}
                catch(_:Exception) {currentCoroutineContext().ensureActive()}
            }
        } catch(_:CancellationException) { /* The owning session/connection scope retired. */ }
        finally {
            val retry=synchronized(lock) {
                if(pending!==entry)null else {
                    pending=null
                    if(scope.isActive && needs())generation() else null
                }
            }
            entry.wake.close()
            retry?.let(::schedule)
        }
    }
    /** Network/wake recovery signals expedite only missing scope, and never bypass a429. */
    suspend fun recoverIfNeeded() {
        wakeClock()
        if(synchronized(lock){!needs() || limit!=null})return
        try {validate {synchronized(lock){needs() && limit==null}}}
        catch(_:Exception) {currentCoroutineContext().ensureActive()}
    }
    /** Android delay timers use uptime; foreground/network signals recheck elapsed-time deadlines. */
    fun wakeClock() {synchronized(lock){pending?.wake?.trySend(Unit)}}
    private fun reset(onlyIfLoaded:Boolean=false) {
        val old=synchronized(lock){if(onlyIfLoaded && !membershipLoaded())return;limit=null;pending.also {pending=null}}
        old?.job?.cancel();old?.wake?.close()
    }
    override fun close() {synchronized(lock){closed=true};reset()}
}
