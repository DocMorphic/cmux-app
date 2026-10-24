package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*

/** Inputs drive inert storage/identity seams; expected behavior comes from complete original Swift declarations. */
internal suspend fun checkNativeSnapshotReference(text:String):Int {
    val rows=JSONObject(text).getJSONArray("cases")
    assertEquals(471,rows.length())
    for(i in 0 until rows.length()) {
        val row=rows.getJSONObject(i);val input=row.getJSONObject("input");val method=input.getString("method")
        val role=input.getString("role");var id=input.opt("identity") as? String;var generation=3L
        var available=input.getString("state")!="unavailable"
        var access:String?="stored";var refresh:String?="refresh";var resolved:String?="resolved"
        when(input.getString("state")) {
            "missingAccess"->access=null;"missingRefresh"->refresh=null
            "empty"->{access=null;refresh=null;resolved=null};"transient"->{access=null;resolved=null}
        }
        var capturing=role=="capture";var resolutions=0;var bootstraps=0;var phases=0;var fired=false;var storedRead=0
        fun owner()=id?.let{NativeAuthenticatedOwner(NativeAuthIdentity(generation,it),"fixture-login",1)}
        fun event(name:String) {
            if(input.optString("event")!=name)return
            val action=input.getString("action");if(fired && action!="churn")return;fired=true
            when(action) {
                "user"->id="account-b";"generation"->generation++;"transition"->capturing=true
                "dropRefresh"->refresh=null;"unavailable"->available=false
                "restore"->{available=true;access="restored";refresh="restored-refresh";resolved="restored"}
                "rotate","churn"->refresh="rotation-$resolutions"
            }
        }
        val work=NativeAuthWork()
        val snapshots=NativeAuthenticatedSnapshots(read={
            if(!available)throw NativeCredentialReadException()
            val view=NativeStoredAuthSession(owner(),access,refresh)
            if(method=="refresh")event("refreshCapture")
            else if(bootstraps==0 && ++storedRead==1 && !refresh.isNullOrEmpty())event("storedAccess")
            view
        },storedCaptureBlocked={input.getBoolean("clear") || role in setOf("signIn","staleSignIn") || capturing},
            transitionActive={role=="signIn" && generation==3L || role.startsWith("validation") &&
                (role=="validationRestoring" || id!=null) || capturing},
            awaitBootstrapped={bootstraps++;event("bootstrap")},accessToken={
                resolutions++;val value=if(available)resolved else null;event("resolved");value
            },publishedOwner=::owner,work=work,availability={
                if(method=="session"){phases++;assertEquals(1,work.activeCount)}
                val value=available;event("availability");value
            })
        var actualAccess:String?=null;var actualRefresh:String?=null;var actualOwner:NativeAuthenticatedOwner?=null;var error:String?=null
        try {
            if(method=="session")snapshots.session().let{actualAccess=it.accessToken;actualRefresh=it.refreshToken;actualOwner=it.owner}
            else snapshots.refresh().let{actualRefresh=it.refreshToken;actualOwner=it.owner}
        }catch(failure:NativeSignInFailure) {error=when(failure.reason){
            NativeSignInError.NETWORK_ERROR->"networkError";NativeSignInError.UNAUTHORIZED->"unauthorized";else->throw failure}}
        val expected=row.getJSONObject("outcome");val label="row $i: $input"
        assertEquals(label,expected.opt("error") as? String,error)
        assertEquals(label,expected.opt("access") as? String,actualAccess)
        assertEquals(label,expected.opt("refresh") as? String,actualRefresh)
        assertEquals(label,expected.opt("account") as? String,actualOwner?.identity?.userId)
        assertEquals(label,(expected.opt("generation") as? Number)?.toLong(),actualOwner?.identity?.generation)
        assertEquals(label,row.getInt("resolutions"),resolutions)
        assertEquals(label,row.getInt("bootstraps"),bootstraps)
        assertEquals(label,row.getInt("phases"),phases)
        assertEquals(label,0,work.activeCount)
    }
    return rows.length()
}
