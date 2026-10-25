package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class NativeCoherentPair(val access:String,val refresh:String) {
    override fun toString()="NativeCoherentPair(redacted)"
}
internal data class NativeCloudContextStamp(val session:Long,val epoch:Long,val routing:Long?,val team:String?) {
    override fun toString()="NativeCloudContextStamp(redacted)"
}
internal class NativeCloudTokenContext(val pair:NativeCoherentPair,val team:String?) {
    override fun toString()="NativeCloudTokenContext(redacted)"
}

/** Complete original coherentTokenPairWithoutStateClear policy; the caller supplies its single phase. */
internal suspend fun nativeCoherentPair(available:()->Boolean,refresh:()->String?,access:suspend()->String?):NativeCoherentPair {
    val wasAvailable=available()
    fun readRefresh()=try{refresh()}catch(_:NativeCredentialReadException){null}
    fun empty():Nothing=throw NativeSignInFailure(if(wasAvailable)NativeSignInError.UNAUTHORIZED else NativeSignInError.NETWORK_ERROR)
    repeat(3) {
        currentCoroutineContext().ensureActive()
        val before=readRefresh()?.takeIf(String::isNotEmpty) ?: empty()
        currentCoroutineContext().ensureActive()
        val resolved=try{access()?.takeIf(String::isNotEmpty)}catch(_:NativeCredentialReadException){null}
        currentCoroutineContext().ensureActive()
        if(resolved==null) {
            if(!readRefresh().isNullOrEmpty())throw NativeSignInFailure(NativeSignInError.NETWORK_ERROR)
            empty()
        }
        if(readRefresh()==before)return NativeCoherentPair(resolved,before)
    }
    throw NativeSignInFailure(NativeSignInError.NETWORK_ERROR)
}

/** Cloud routing is pinned around live coherence, independently of stored-pair/control snapshots. */
internal suspend fun nativeCloudTokenContext(stamp:()->NativeCloudContextStamp,transition:()->Boolean,
    capture:suspend()->NativeCoherentPair):NativeCloudTokenContext {
    val before=stamp();val pair=capture()
    if(stamp()!=before || transition())throw NativeSignInFailure(NativeSignInError.NETWORK_ERROR)
    return NativeCloudTokenContext(pair,before.team)
}

/** currentTokens includes its read-only bootstrap wait in the same absolute capture budget. */
internal suspend fun <G> nativeCurrentTokens(work:NativeAuthWork,bootstrap:suspend()->Unit,writer:()->Boolean,
    generation:()->G,capture:suspend()->NativeCoherentPair,checkOwner:()->Unit={},timeoutMillis:Long=60_000,
    nowMillis:()->Long=work::clockMillis,phase:NativeAuthPhase=NativeAuthPhase.REFRESH,owner:String?=null,
    finish:()->Unit={}):NativeCoherentPair {
    currentCoroutineContext().ensureActive()
    val deadline=nowMillis()+timeoutMillis
    // Plain work is retained for actual retirement but never joined as token-writing ownership.
    work.run(phase,timeoutMillis,owner,admission=NativeAuthWork.Admission.PHASE,absoluteDeadlineMillis=deadline){bootstrap()}
    currentCoroutineContext().ensureActive()
    val remaining=deadline-nowMillis()
    if(remaining<=0)throw NativeSignInFailure(NativeSignInError.TIMED_OUT)
    return work.run(phase,remaining,owner,finish=finish,absoluteDeadlineMillis=deadline) {
        currentCoroutineContext().ensureActive();checkOwner()
        if(writer())throw NativeSignInFailure(NativeSignInError.NETWORK_ERROR)
        val before=generation();val pair=capture()
        currentCoroutineContext().ensureActive()
        if(generation()!=before)throw kotlinx.coroutines.CancellationException("Account changed")
        if(writer())throw NativeSignInFailure(NativeSignInError.NETWORK_ERROR)
        checkOwner();pair
    }
}

/** Cloud is backend-authorized and can have no team. This principal never supplies native Mac membership. */
internal data class NativeCloudPrincipal(val login:String,val user:String,val generation:Long,val epoch:Long) {
    override fun toString()="NativeCloudPrincipal(redacted)"
}
internal data class NativeCloudRoute(val owner:CloudAccountScope?,val generation:Long)
internal class NativeCloudScopeRegistry {
    private data class Key(val principal:NativeCloudPrincipal,val team:String?)
    private data class Routing(val owner:Key,val verified:NativeTeamScope?)
    private var key:Key?=null;private var routing:Routing?=null
    private var current:CloudAccountScope?=null;private var generation=0L
    var routingGeneration=0L;private set
    // A native proof change fences in-flight credential capture, without closing a Cloud backend session.
    fun scope(principal:NativeCloudPrincipal?,team:String?,verified:NativeTeamScope?):CloudAccountScope? {
        val next=principal?.let{Key(it,team)}
        val nextRouting=next?.let{Routing(it,verified)}
        if(nextRouting!=routing){routing=nextRouting;routingGeneration++}
        if(next!=key){key=next;current=next?.let{CloudAccountScope(it.principal.login,it.principal.user,it.team,++generation)}}
        return current
    }
}
