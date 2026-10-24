package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSdkTokenResolutionTest {
    private fun normalized(value:Any?):Any?=when(value) {
        null,JSONObject.NULL->null
        is JSONObject->value.keys().asSequence().toList().sorted().associateWith{normalized(value.opt(it))}
        is JSONArray->(0 until value.length()).map{normalized(value.opt(it))}
        else->value
    }
    @Test fun originalCompleteResolverControlFlow()=runBlocking<Unit> {
        val ref=JSONObject(checkNotNull(javaClass.getResource("/auth/native-token-resolution-reference.json")).readText())
        val rows=ref.getJSONArray("cases");assertEquals(110,rows.length())
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i);val input=row.getJSONObject("input");val reads=JSONArray();val exchanges=JSONArray()
            var ri=0;var ai=0;var result:NativeSdkTokens?=null
            fun getter(key:String,index:Int):String? {val values=input.getJSONArray(key);return values.opt(index.coerceAtMost(values.length()-1)) as? String}
            val task=launch(start=CoroutineStart.UNDISPATCHED) {
                if(input.optBoolean("cancelBefore"))currentCoroutineContext().job.cancel()
                result=nativeSdkResolveTokens(input.getBoolean("force"),input.opt("rejected") as? String,
                    refresh={reads.put("refresh");getter("refreshReads",ri++)},access={reads.put("access");getter("accessReads",ai++)},
                    fresh={it in setOf("fresh","new")},expired={it==null || it=="expired"}) {refresh,access->
                    exchanges.put(JSONObject().put("refresh",refresh).put("access",access ?: JSONObject.NULL))
                    if(input.optBoolean("cancelDuring"))currentCoroutineContext().job.cancel()
                    val planned=input.getJSONObject("result")
                    NativeSdkTokens(planned.opt("refresh") as? String,planned.opt("access") as? String,when(planned.optString("failure")) {
                        "timedOut"->NativeSdkTokenFailure.TIMED_OUT;"sessionChanged"->NativeSdkTokenFailure.SESSION_CHANGED
                        "cancelled"->NativeSdkTokenFailure.CANCELLED;else->null})
                }
            }
            task.join();val pair=checkNotNull(result);val actual=JSONObject().put("refresh",pair.refresh ?: JSONObject.NULL)
                .put("access",pair.access ?: JSONObject.NULL).put("failure",when(pair.failure) {
                    NativeSdkTokenFailure.TIMED_OUT->"timedOut";NativeSdkTokenFailure.SESSION_CHANGED->"sessionChanged"
                    NativeSdkTokenFailure.CANCELLED->"cancelled";null->JSONObject.NULL})
            val name=input.getString("case")
            assertEquals(name,normalized(row.getJSONObject("pair")),normalized(actual))
            assertEquals(name,normalized(row.getJSONArray("reads")),normalized(reads))
            assertEquals(name,normalized(row.getJSONArray("exchangeInputs")),normalized(exchanges))
        }
    }
    @Test fun pendingOwnersRemainExcludedFromVerifiedViewsAndDoNotEqualPublishedOwners() {
        val root=JSONObject().put("task_session","receipt").put(NativeSignInCompletion.PENDING,"receipt").put("refresh_token","refresh")
        assertNull(NativeRefreshOwner.from(root,2));val pending=checkNotNull(NativeRefreshOwner.stored(root,2));assertTrue(pending.pending)
        root.remove(NativeSignInCompletion.PENDING);val published=checkNotNull(NativeRefreshOwner.from(root,2))
        assertFalse(published.pending);assertNotEquals(pending,published)
        root.put(NativeSignInCompletion.PENDING,"different");assertNull(NativeRefreshOwner.stored(root,2))
    }
}
