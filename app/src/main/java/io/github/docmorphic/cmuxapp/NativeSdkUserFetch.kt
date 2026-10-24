package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** getUser(.throw) has a different outcome from successful JSON that contains no user. */
internal class NativeSdkUserNotSignedIn : NativeAccountRequestFailure("USER_NOT_SIGNED_IN",false,"User is not signed in.")

/** Token resolution and HTTP transfer belong to their owners; this is the SDK user projection. */
internal suspend fun nativeSdkGetUser(throwOnMissing:Boolean=true,hasTokens:Boolean=true,
    read:suspend ()->JSONObject?):NativeSignInUser? {
    fun noUser():NativeSignInUser? {if(throwOnMissing)throw NativeSdkUserNotSignedIn();return null}
    if(!hasTokens)return noUser()
    return try {
        val payload=read() ?: return null
        if(!NativeSignInUser.isEligible(payload))noUser() else NativeSignInUser.decode(payload)
    } catch(failure:Exception) {
        currentCoroutineContext().ensureActive()
        // The Swift catch is specifically StackAuthError, not every error protocol
        // or foreign refresh failure whose code happens to spell RATE_LIMITED.
        if(failure is NativeSdkRequestFailure && failure.code=="RATE_LIMITED")throw failure
        noUser()
    }
}
