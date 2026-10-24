package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class NativeSdkModelTest {
    private val reference by lazy{JSONObject(checkNotNull(javaClass.getResourceAsStream("/native-sdk-model-reference.json"))
        .bufferedReader().use{it.readText()})}
    private fun JSONObject.text(key:String)=opt(key).takeUnless{it===JSONObject.NULL} as? String
    private fun assertProjection(expected:JSONObject,actual:NativeSignInUser) {
        assertEquals(expected.getString("id"),actual.id);assertEquals(expected.text("name"),actual.name)
        assertEquals(expected.text("email"),actual.email);assertEquals(expected.text("image"),actual.profileImageUrl)
        assertEquals(expected.getBoolean("demo"),actual.demonstrationContentEnabled)
    }
    @Test fun userProjectionAndCacheMatchThirtyFourExecutedOriginalCases() {
        val rows=reference.getJSONArray("users");assertEquals(34,rows.length())
        for(i in 0 until rows.length()){val row=rows.getJSONObject(i);val payload=row.getJSONObject("payload")
            val result=runCatching{NativeSignInUser.decode(payload)}
            assertEquals(row.toString(),row.getBoolean("eligible"),result.isSuccess)
            if(result.isSuccess){val user=result.getOrThrow();assertProjection(row.getJSONObject("user"),user)
                assertEquals(row.text("selected"),user.selectedTeam)
                val root=JSONObject();NativeAuthUserCache.write(root,"fixture",user)
                val restored=NativeAuthUserCache.read(root,"fixture")!!;assertProjection(row.getJSONObject("restored"),restored)
                assertEquals(user.selectedTeam,restored.selectedTeam)
            }else assertEquals(NativeSignInError.UNAUTHORIZED,(result.exceptionOrNull() as NativeSignInFailure).reason)
        }
    }
    @Test fun cachedIdentityTypesAndDefaultsMatchThirteenExecutedOriginalCases() {
        val rows=reference.getJSONArray("caches");assertEquals(13,rows.length())
        for(i in 0 until rows.length()){val row=rows.getJSONObject(i);val payload=row.getJSONObject("payload")
            val cache=JSONObject().put("version",1).put("environment","fixture")
            for((source,target) in listOf("id" to "user","displayName" to "name","primaryEmail" to "email",
                "profileImageURL" to "profile_image_url","demonstrationContentEnabled" to "demonstration_content_enabled"))
                if(payload.has(source))cache.put(target,payload.opt(source))
            val actual=NativeAuthUserCache.read(JSONObject().put(NativeAuthUserCache.KEY,cache),"fixture")
            if(row.isNull("user"))assertNull(row.toString(),actual)else assertProjection(row.getJSONObject("user"),checkNotNull(actual))
        }
    }
    @Test fun teamRowsMatchNineteenExecutedOriginalListAndConstructorCases() {
        val rows=reference.getJSONArray("lists");assertEquals(19,rows.length())
        for(i in 0 until rows.length()){val row=rows.getJSONObject(i)
            val input=runCatching{JSONObject(row.getString("raw"))}.getOrElse{JSONObject()}
            val actual=NativeSdkTeamRows.decode(input);val expected=row.getJSONArray("teams")
            assertEquals(row.toString(),expected.length(),actual.size)
            actual.forEachIndexed{n,team->assertEquals(expected.getJSONObject(n).getString("id"),team.id)
                assertEquals(expected.getJSONObject(n).getString("name"),team.name)}
        }
    }
    private fun MockWebServer.membership(user:String="user",raw:String="{\"items\":[{\"id\":\"one\",\"display_name\":\"One\"}]}") {
        teamUsers(2){MockResponse().setBody(JSONObject().put("id",user).toString())};enqueue(MockResponse().setBody(raw))
    }
    @Test fun successfulMalformedListLoadsEmptyAndRetiresPriorProofInsteadOfPreservingIt()=runBlocking<Unit> {
        for(raw in listOf("broken","[]","{}","{\"items\":[{},null]}"))MockWebServer().use{server->
            NativeAccountTeams({"fixture"},{"login"},server.url("/api/v1/")).use{c->
                server.membership();val first=c.refresh().scope!!;val proof=c.captureContinuation(first)!!
                server.membership(raw=raw);val empty=c.refresh();assertTrue(empty.teams.isEmpty());assertNull(empty.scope)
                assertTrue(c.hasLoadedMembership);assertFalse(c.allowsContinuation(proof));assertFalse(c.isCurrent(first));assertNull(empty.error)
            }}
    }
    @Test fun transportFailureStillPreservesLoadedMembershipAndItsProof()=runBlocking<Unit> {
        assertTrue(reference.getJSONObject("boundaries").getBoolean("transportFailureThrows"))
        MockWebServer().use{server->NativeAccountTeams({"fixture"},{"login"},server.url("/api/v1/")).use{c->
            server.membership();val first=c.refresh().scope!!;val proof=c.captureContinuation(first)!!
            server.teamUsers(2){MockResponse().setBody("{\"id\":\"user\"}")};server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(runCatching{c.refresh()}.isFailure);assertEquals(first,c.state.value.scope);assertTrue(c.allowsContinuation(proof))
        }}
    }
    @Test fun largeSuccessfulMembershipMatchesTheOriginalUnboundedRowsAndBody()=runBlocking<Unit> {
        val count=reference.getJSONObject("boundaries").getInt("rows");assertEquals(4097,count)
        val raw=JSONObject().put("items",JSONArray((0 until count).map{JSONObject().put("id","t$it").put("display_name","N")}))
            .put("padding","x".repeat(2_100_000)).toString()
        assertTrue(raw.toByteArray().size>2*1024*1024)
        MockWebServer().use{server->NativeAccountTeams({"fixture"},{"login"},server.url("/api/v1/")).use{c->
            server.membership(raw=raw);val result=c.refresh();assertEquals(count,result.teams.size);assertEquals("t0",result.scope?.teamId)
        }}
    }
    @Test fun largeUserResponsePreservesRawProfileMetadataAndCacheBeyondOldBounds()=runBlocking<Unit> {
        val payload=JSONObject().put("id","u".repeat(129)).put("display_name"," n ".repeat(200)).put("primary_email","")
            .put("profile_image_url"," image ").put("client_read_only_metadata",JSONObject().put("cmuxReviewDemoContent",true))
            .put("padding","x".repeat(270_000))
        MockWebServer().use{server->server.enqueue(MockResponse().setBody(payload.toString()))
            val user=NativeOAuthClient("fixture","public",server.url("/")).currentUser("fixture-access")
            assertEquals(payload.getString("id"),user.id);assertEquals(payload.getString("display_name"),user.name)
            assertEquals("",user.email);assertEquals(" image ",user.profileImageUrl);assertTrue(user.demonstrationContentEnabled)
            val root=JSONObject();NativeAuthUserCache.write(root,"fixture",user);assertEquals(user,NativeAuthUserCache.read(root,"fixture"))
        }
    }
    @Test fun emptyUserCanLoadMembershipWithoutAnyConnectionScopeAndOldGenerationCannotPublish()=runBlocking<Unit> {
        var generation=0L;val user=NativeSignInUser("","Empty id",null,null)
        MockWebServer().use{server->NativeAccountTeams({"fixture"},{"login"},server.url("/api/v1/"),sessionIdentity={null},
            sessionUser={user},sessionGeneration={generation},accountUser={user}).use{c->
            server.membership(user="");val value=c.refreshAfterSignIn("login",user)
            assertEquals("",value.userId);assertTrue(c.hasLoadedMembership);assertNull(value.scope);assertEquals("one",value.selectedTeamId)
            repeat(3){server.takeRequest()};server.teamUsers(2){MockResponse().setBody("{\"id\":\"\"}").setBodyDelay(150,TimeUnit.MILLISECONDS)}
            val old=async{runCatching{c.refresh()}};withContext(Dispatchers.IO){assertNotNull(server.takeRequest(2,TimeUnit.SECONDS))}
            generation++;assertFalse(c.hasLoadedMembership);c.reconcileLogin();assertNull(c.state.value.scope)
            assertTrue(old.await().isFailure);assertTrue(c.state.value.teams.isEmpty())
        }}
    }
    @Test fun emptyTeamCannotCreateScopeButWhitespaceIdsRemainRawLikeTheOriginal()=runBlocking<Unit> {
        for((id,expected) in listOf("" to null," " to " "))MockWebServer().use{server->
            NativeAccountTeams({"fixture"},{"login"},server.url("/api/v1/")).use{c->
                server.membership(raw=JSONObject().put("items",JSONArray().put(JSONObject().put("id",id))).toString())
                val result=c.refresh();assertEquals(id,result.selectedTeamId);assertEquals(expected,result.scope?.teamId);assertTrue(c.hasLoadedMembership)
            }}
    }
    @Test fun unverifiedEmptyCachedUserMustValidateEvenThoughBothConnectionIdentitiesAreNil()=runBlocking<Unit> {
        val user=NativeSignInUser("","Cached",null,null);var fail=true
        val model=NativeSessionRestore(true,user,{},{user},{true},{true},{if(fail)throw NativeSessionUserUnavailable() else user},
            {"fixture-refresh"},{true},{},{})
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try{assertNull(model.identity());assertNull(model.verifiedUser());assertTrue(model.needsIdentityValidation)
            model.start(scope);withTimeout(3000){model.awaitBootstrapped()};assertNull(model.verifiedUser());assertTrue(model.needsIdentityValidation)
            fail=false;model.revalidate();assertTrue(model.state.value.authenticated);assertNull(model.identity())
            assertEquals(user,model.verifiedUser());assertFalse(model.needsIdentityValidation)
            model.clear();assertNull(model.verifiedUser());assertFalse(model.state.value.authenticated)
            model.publishedSignIn(user.copy(id=" "));assertEquals(" ",model.identity()?.userId)
        }finally{scope.cancel();scope.coroutineContext[Job]!!.join()}
    }
    @Test fun rawEmptyAndDuplicateTeamRowsRoundTripAsReadonlyCacheWithoutScope() {
        var root=JSONObject().put("task_session","login").put("refresh_token","fixture")
        val cache=NativeAccountProfileCache({root},{change->root=JSONObject(root.toString()).also(change)})
        val teams=listOf(NativeTeam("",""),NativeTeam("same","First"),NativeTeam("same","Second"),NativeTeam("x".repeat(129),"n".repeat(513)))
        cache.save("login","fixture",NativeAccountTeamsState("",teams,"same",displayName=" ",email=""))
        val restored=cache.read("login","fixture")!!;assertEquals(teams,restored.teams);assertEquals("",restored.userId)
        assertEquals(" ",restored.displayName);assertEquals("",restored.email);assertTrue(restored.cached);assertNull(restored.scope)
    }
}
