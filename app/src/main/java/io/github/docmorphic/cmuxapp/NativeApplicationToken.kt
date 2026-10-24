package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** AuthCoordinator's release token miss projection; strict storage adapters preserve unknown reads separately. */
internal fun nativeApplicationTokenResult(access:String?,refresh:String?,storageWasAvailable:Boolean):String {
    if(access!=null)return access
    throw NativeSignInFailure(if(refresh!=null || !storageWasAvailable)NativeSignInError.NETWORK_ERROR else NativeSignInError.UNAUTHORIZED)
}
internal fun nativeApplicationTransition(currentSignIn:Boolean,revalidating:Boolean,restoring:Boolean,authenticated:Boolean,capturingSignOut:Boolean)=
    currentSignIn || revalidating && (restoring || authenticated) || capturingSignOut

/** Only a winning unauthorized verdict can clear presentation, and a current writer retains ownership. */
internal suspend fun nativeApplicationAccess(resolve:suspend()->String,transitioning:()->Boolean,clear:suspend()->Unit):String {
    try{return resolve()}
    catch(failure:NativeSignInFailure) {
        if(failure.reason!=NativeSignInError.UNAUTHORIZED)throw failure
        currentCoroutineContext().ensureActive()
        if(transitioning())throw NativeSignInFailure(NativeSignInError.NETWORK_ERROR)
        clear()
        throw failure
    }
}
