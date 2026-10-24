package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeTokenAdapterTest {
    @Test fun completeOriginalAdapterOptionalAndTypedOutcomes()=runBlocking<Unit> {
        val rows=JSONObject(checkNotNull(javaClass.getResource("/auth/native-token-adapter-reference.json")).readText()).getJSONArray("cases")
        assertEquals(64,rows.length())
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i);val input=row.getJSONObject("input");val expected=row.getJSONObject("outcome")
            val pair=NativeSdkTokens("refresh",input.opt("access") as? String,when(input.optString("failure")) {
                "cancelled"->NativeSdkTokenFailure.CANCELLED;"sessionChanged"->NativeSdkTokenFailure.SESSION_CHANGED
                "timedOut"->NativeSdkTokenFailure.TIMED_OUT;else->null})
            var actual:String?=null;var failure:String?=null
            val task=launch(start=CoroutineStart.UNDISPATCHED) {
                if(input.getBoolean("cancelBefore"))currentCoroutineContext().job.cancel()
                try {actual=if(input.getString("mode")=="resolved")nativeSdkResolvedAccess(pair)else nativeSdkAccess(pair)}
                catch(e:CancellationException){failure="cancelled"}
                catch(e:NativeSignInFailure){assertEquals(NativeSignInError.TIMED_OUT,e.reason);failure="timedOut"}
            }
            task.join();assertEquals(input.toString(),expected.opt("access") as? String,actual)
            assertEquals(input.toString(),expected.opt("error") as? String,failure)
        }
    }
}
