package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*

internal suspend fun checkNativeCloudContextReference(text:String):Int=coroutineScope {
    val rows=JSONObject(text).getJSONArray("cases");assertEquals(323,rows.length())
    for(i in 0 until rows.length()) {
        val row=rows.getJSONObject(i);val input=row.getJSONObject("input");val role=input.getString("role")
        var session=3L;var routing=7L;var team=input.opt("team") as? String
        var available=input.getString("state")!="unavailable";var refresh:String?="refresh";var resolved:String?="resolved"
        if(input.getString("state") in setOf("missingRefresh","empty"))refresh=null
        if(input.getString("state") in setOf("empty","transient"))resolved=null
        var capturing=role=="capture";var resolutions=0;var bootstraps=0;var finishes=0;var elapsed=0L;var fired=false
        fun event(name:String){
            if(input.optString("event")!=name)return
            val action=input.getString("action");if(fired && action!="churn")return;fired=true
            when(action){
                "session"->session++;"teamScope"->routing++;"team"->team="team-b";"transition"->capturing=true
                "dropRefresh"->refresh=null;"rotate","churn"->refresh="rotation-$resolutions"
                "unavailable"->available=false;"restore"->{available=true;refresh="restored-refresh";resolved="restored"}
            }
        }
        val work=NativeAuthWork(nowMillis={elapsed})
        suspend fun inner()=nativeCoherentPair(available={val value=available;event("availability");value},
            refresh={if(!available)throw NativeCredentialReadException();refresh},access={
                resolutions++;val value=if(available)resolved else null
                elapsed+=input.optLong("resolveMillis",0);event("resolved")
                if(input.optBoolean("cancelDuring"))currentCoroutineContext().job.cancel()
                value
            })
        suspend fun pair()=work.run(NativeAuthPhase.REFRESH,60_000,finish={finishes++}){inner()}
        var actual:NativeCoherentPair?=null;var actualTeam:String?=null;var error:String?=null
        val task=launch(start=CoroutineStart.UNDISPATCHED) {
            if(input.optBoolean("cancelBefore"))currentCoroutineContext().job.cancel()
            try {when(input.getString("method")) {
                "context"->nativeCloudTokenContext({NativeCloudContextStamp(session,1,routing,team)},
                    {role=="signIn" && session==3L || role=="validation" || capturing},::pair).let{actual=it.pair;actualTeam=it.team}
                "current"->actual=nativeCurrentTokens(work,bootstrap={bootstraps++;elapsed+=input.optLong("bootstrapMillis",0);event("bootstrap")},
                    writer={role in setOf("signIn","staleSignIn") || capturing},generation={session},capture=::inner,nowMillis={elapsed},finish={finishes++})
                else->actual=pair()
            }}catch(_:CancellationException){error="cancelled"}
              catch(failure:NativeSignInFailure){error=when(failure.reason){
                  NativeSignInError.UNAUTHORIZED->"unauthorized";NativeSignInError.NETWORK_ERROR->"networkError"
                  NativeSignInError.TIMED_OUT->"timedOut";else->throw failure}}
        }
        task.join();val expected=row.getJSONObject("outcome");val label="row $i: $input"
        assertEquals(label,expected.opt("error") as? String,error)
        assertEquals(label,expected.opt("access") as? String,actual?.access)
        assertEquals(label,expected.opt("refresh") as? String,actual?.refresh)
        assertEquals(label,expected.opt("team") as? String,actualTeam)
        assertEquals(label,row.getInt("resolutions"),resolutions);assertEquals(label,row.getInt("bootstraps"),bootstraps)
        assertEquals(label,row.getInt("finishCalls"),finishes);assertEquals(label,row.getLong("elapsedMillis"),elapsed)
        assertEquals(label,0,work.activeCount)
    }
    rows.length()
}
