package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class NativeApplicationTokenRuntimeTest {
    @org.junit.Before fun emulatorOnly(){check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))}
    @Test fun originalCoordinatorReleaseProjectionAndOriginalInnerPhaseDeadline()=runBlocking<Unit> {
        val rows=JSONObject(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("native-application-token-reference.json").bufferedReader().use{it.readText()}).getJSONArray("cases")
        assertEquals(464,rows.length())
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i);val input=row.getJSONObject("input");val role=input.getString("role")
            val transition=nativeApplicationTransition(role=="signIn",role.startsWith("validation") || role=="staleValidation",
                role=="validationRestoring",role=="validationAuthenticated",role=="capture")
            assertEquals(input.toString(),row.getBoolean("transitionActive"),transition)
            var access:String?=null;var error:String?=null;var clears=0;var finishCalls=0
            val elapsed=AtomicLong();val work=NativeAuthWork(nowMillis=elapsed::get)
            val task=launch(start=CoroutineStart.UNDISPATCHED) {
                if(input.optBoolean("cancelBefore"))currentCoroutineContext().job.cancel()
                try {access=nativeApplicationAccess(resolve={
                    if(input.optBoolean("preExpired"))throw NativeSignInFailure(NativeSignInError.TIMED_OUT)
                    work.run(if(input.getBoolean("force"))NativeAuthPhase.FORCE_REFRESH else NativeAuthPhase.REFRESH,60_000,
                        finish={finishCalls++;if(input.optBoolean("retired"))throw CancellationException("Fixture retired phase")}) {
                        if(input.optBoolean("late"))elapsed.set(60_001)
                        if(input.optBoolean("cancelDuring"))currentCoroutineContext().job.cancel()
                        val pair=NativeSdkTokens(input.opt("refresh") as? String,input.opt("access") as? String,when(input.optString("failure")) {
                            "cancelled"->NativeSdkTokenFailure.CANCELLED;"sessionChanged"->NativeSdkTokenFailure.SESSION_CHANGED
                            "timedOut"->NativeSdkTokenFailure.TIMED_OUT;else->null})
                        nativeApplicationTokenResult(nativeSdkResolvedAccess(pair),pair.refresh,input.getBoolean("available"))
                    }
                },transitioning={transition},clear={clears++})}
                catch(failure:CancellationException){error="cancelled"}
                catch(failure:NativeSignInFailure){error=when(failure.reason){
                    NativeSignInError.UNAUTHORIZED->"unauthorized";NativeSignInError.NETWORK_ERROR->"networkError"
                    NativeSignInError.TIMED_OUT->"timedOut";else->throw failure}}
            }
            task.join();val expected=row.getJSONObject("outcome");val projection=row.getJSONObject("projection")
            val label=input.toString();assertEquals(label,expected.opt("access") as? String,access);assertEquals(label,expected.opt("error") as? String,error)
            assertEquals(label,projection.getInt("userCacheClears"),clears);assertEquals(label,projection.getInt("sessionCacheClears"),clears)
            assertEquals(label,projection.getInt("applies"),clears);assertEquals(label,projection.getInt("transitions"),clears)
            assertEquals(label,projection.getInt("generation"),3+clears);assertEquals(label,"challenge",projection.getString("nonce"))
            assertEquals(label,projection.getInt("finishCalls"),finishCalls)
        }
    }
}
