package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

internal enum class NativeSdkTokenFailure { CANCELLED, SESSION_CHANGED, TIMED_OUT }
internal data class NativeSdkTokens(val refresh:String?,val access:String?,val failure:NativeSdkTokenFailure?=null) {
    override fun toString()="NativeSdkTokens(redacted)"
}

/** APIClient token-selection policy; storage/refresh owners supply their fenced operations. */
internal suspend fun nativeSdkResolveTokens(force:Boolean,rejected:String?,refresh:()->String?,access:()->String?,
    fresh:(String?)->Boolean,expired:(String?)->Boolean,
    exchange:suspend(String,String?)->NativeSdkTokens):NativeSdkTokens {
    if(!force && !currentCoroutineContext().isActive)return NativeSdkTokens(null,null,NativeSdkTokenFailure.CANCELLED)
    val observedRefresh=refresh();val observedAccess=access()
    if(force) {
        if(observedRefresh==null)return NativeSdkTokens(null,null)
        if(rejected!=null && observedAccess!=null && observedAccess!=rejected && !expired(observedAccess))
            return NativeSdkTokens(observedRefresh,observedAccess)
    } else {
        if(observedRefresh==null) {
            if(refresh()!=null || access()!=observedAccess)return NativeSdkTokens(null,null,NativeSdkTokenFailure.SESSION_CHANGED)
            return NativeSdkTokens(null,observedAccess.takeUnless(expired))
        }
        if(refresh()!=observedRefresh)return NativeSdkTokens(null,null,NativeSdkTokenFailure.SESSION_CHANGED)
        if(fresh(observedAccess))return NativeSdkTokens(observedRefresh,observedAccess)
    }
    val result=exchange(checkNotNull(observedRefresh),observedAccess)
    val storedRefresh=refresh()
    if(!currentCoroutineContext().isActive)return NativeSdkTokens(null,null,NativeSdkTokenFailure.CANCELLED)
    if(result.failure==NativeSdkTokenFailure.TIMED_OUT && !force && storedRefresh==observedRefresh && !expired(observedAccess))
        return NativeSdkTokens(observedRefresh,observedAccess)
    if(storedRefresh!=result.refresh)return NativeSdkTokens(null,null,result.failure ?: NativeSdkTokenFailure.SESSION_CHANGED)
    if(result.failure==null && result.refresh!=null && result.access==null && !force && !expired(observedAccess))
        return NativeSdkTokens(observedRefresh,observedAccess)
    return result
}
