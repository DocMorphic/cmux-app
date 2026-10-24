package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NativeTeamEligibilityTest {
    private val a=NativeSignInUser("account-a","Account A","a@example.test","one")
    private fun user(flag:String?=null,value:Any?=true)=JSONObject().put("id",a.id).put("display_name",a.name)
        .put("primary_email",a.email).put("selected_team",JSONObject().put("id","one"))
        .apply {if(flag!=null)put(flag,value)}
    private fun reply(value:JSONObject)=MockResponse().setBody(value.toString())
    private fun teams()=MockResponse().setBody("{\"items\":[{\"id\":\"one\",\"display_name\":\"One\"}]}")
    private fun controller(server:MockWebServer,identity:AtomicReference<NativeAuthIdentity> = AtomicReference(NativeAuthIdentity(0,a.id)))=
        NativeAccountTeams({"fixture-token"},{"login"},server.url("/api/v1/"),sessionIdentity=identity::get)
    private suspend fun establish(server:MockWebServer,c:NativeAccountTeams):Pair<NativeTeamScope,NativeTeamContinuation> {
        repeat(2){server.enqueue(reply(user()))};server.enqueue(teams());val scope=checkNotNull(c.refreshAfterSignIn("login",a).scope)
        return scope to checkNotNull(c.captureContinuation(scope))
    }
    @Test fun decoderMatchesTwentyNineCasesExecutedFromTheWholeOriginalSdkUserModel() {
        val data=checkNotNull(javaClass.getResourceAsStream("/native-team-user-eligibility-reference.json"))
            .bufferedReader().use {JSONObject(it.readText())}
        assertEquals("a5911a1f92f3eeb11c4d7f3e2ef89204718c5009",data.getString("upstream"))
        val rows=data.getJSONArray("rows");assertEquals(29,rows.length())
        for(index in 0 until rows.length()) {
            val row=rows.getJSONObject(index);val result=runCatching {NativeSignInUser.decode(row.getJSONObject("payload"))}
            assertEquals(row.getString("case"),row.getBoolean("eligible"),result.isSuccess)
            if(result.isFailure)assertEquals(NativeSignInError.UNAUTHORIZED,(result.exceptionOrNull() as NativeSignInFailure).reason)
        }
    }
    @Test fun ineligibleUsersCannotObtainFreshMembershipOrAMemoryProof()=runBlocking<Unit> {
        for(flag in listOf("is_anonymous","is_restricted"))MockWebServer().use {server->controller(server).use {c->
            repeat(2){server.enqueue(reply(user(flag)))}
            assertTrue(runCatching {c.refresh()}.exceptionOrNull() is NativeSessionUserUnavailable)
            assertEquals(2,server.requestCount);assertEquals("/api/v1/users/me",server.takeRequest().path)
            assertNull(c.state.value.scope);assertTrue(c.state.value.teams.isEmpty())
        }}
    }
    @Test fun anIneligibleReadKeepsOnlyTheAlreadyLoadedSameSessionAuthorityAndDoesNotFetchTeams()=runBlocking<Unit> {
        for(flag in listOf("is_anonymous","is_restricted"))MockWebServer().use {server->controller(server).use {c->
            val(scope,proof)=establish(server,c);repeat(2){server.enqueue(reply(user(flag)))}
            assertTrue(runCatching {c.refresh()}.exceptionOrNull() is NativeSessionUserUnavailable)
            assertEquals(5,server.requestCount);assertEquals(scope,c.state.value.scope)
            assertTrue(c.isCurrent(scope));assertTrue(c.allowsContinuation(proof));assertFalse(c.state.value.loading)
            repeat(2){server.enqueue(reply(user()))};server.enqueue(teams());assertEquals(scope,c.refresh().scope)
            assertTrue(c.allowsContinuation(proof));assertEquals(8,server.requestCount)
        }}
    }
    @Test fun missingOrStringFlagsStillAllowTheSameEligibleMembershipRead()=runBlocking<Unit> {
        for(value in listOf<Any>(false,0,2,"true","1",JSONObject.NULL))MockWebServer().use {server->controller(server).use {c->
            repeat(2){server.enqueue(reply(user("is_anonymous",value)))};server.enqueue(teams())
            assertNotNull(c.refresh().scope);assertEquals(3,server.requestCount)
        }}
    }
    @Test fun everyFailedUserReadPreservesLoadedMembershipButRateLimitRemainsDistinct()=runBlocking<Unit> {
        for(status in listOf(400,401,403,404,429,503))MockWebServer().use {server->controller(server).use {c->
            val(scope,proof)=establish(server,c);repeat(if(status==401)4 else 2){server.enqueue(MockResponse().setResponseCode(status))}
            val failure=runCatching {c.refresh()}.exceptionOrNull();assertNotNull(failure)
            if(status==429)assertTrue(failure is NativeAccountRateLimited)
            else assertTrue(failure is NativeSessionUserUnavailable)
            assertEquals(scope,c.state.value.scope);assertTrue(c.allowsContinuation(proof))
            assertTrue(server.requestCount in (if(status==401)5..7 else 4..5))
        }}
    }
    @Test fun failedMembershipIsNotEmptyMembershipAndSuccessfulRemovalCannotReviveAnOldProof()=runBlocking<Unit> {
        MockWebServer().use {server->controller(server).use {c->
            val(scope,proof)=establish(server,c);repeat(2){server.enqueue(reply(user()))};server.enqueue(MockResponse().setResponseCode(403))
            assertTrue(runCatching {c.refresh()}.isFailure);assertEquals(scope,c.state.value.scope);assertTrue(c.allowsContinuation(proof))
            repeat(2){server.enqueue(reply(user()))};server.enqueue(MockResponse().setBody("{\"items\":[]}"));c.refresh()
            assertNull(c.state.value.scope);assertFalse(c.allowsContinuation(proof))
            repeat(2){server.enqueue(reply(user()))};server.enqueue(teams());val fresh=checkNotNull(c.refresh().scope)
            assertTrue(c.isCurrent(fresh));assertFalse(c.allowsContinuation(proof));assertNotEquals(scope.generation,fresh.generation)
        }}
    }
    @Test fun cachedTeamPresentationCannotBecomeFreshAuthorityAfterAnIneligibleRead()=runBlocking<Unit> {
        MockWebServer().use {server->
            var root=JSONObject().put("task_session","login").put("refresh_token","fixture-refresh")
            val cache=NativeAccountProfileCache({root},{change->root=JSONObject(root.toString()).also(change)})
            val env="${server.url("/api/v1/")}#${NativeAccount.PROJECT_ID}"
            cache.save("login",env,NativeAccountTeamsState(a.id,listOf(NativeTeam("one","One")),"one"))
            NativeAccountTeams({"fixture-token"},{"login"},server.url("/api/v1/"),cache=cache,
                sessionIdentity={NativeAuthIdentity(0,a.id)}).use {c->
                assertTrue(c.state.value.cached);assertNull(c.state.value.scope)
                repeat(2){server.enqueue(reply(user("is_restricted")))}
                assertTrue(runCatching {c.refresh()}.isFailure);assertTrue(c.state.value.cached)
                assertNull(c.state.value.scope);assertEquals(2,server.requestCount);assertEquals("one",cache.read("login",env)?.selectedTeamId)
            }
        }
    }
    @Test fun freshPublicationKeepsTheVerifiedProfileOnFailureWithoutCarryingTheOldGenerationsProof()=runBlocking<Unit> {
        MockWebServer().use {server->
            var root=JSONObject().put("task_session","login").put("refresh_token","fixture-refresh")
            val cache=NativeAccountProfileCache({root},{change->root=JSONObject(root.toString()).also(change)})
            val identity=AtomicReference(NativeAuthIdentity(0,a.id));val env="${server.url("/api/v1/")}#${NativeAccount.PROJECT_ID}"
            NativeAccountTeams({"fixture-token"},{"login"},server.url("/api/v1/"),cache=cache,sessionIdentity=identity::get).use {c->
                val(_,proof)=establish(server,c);identity.set(NativeAuthIdentity(1,a.id));repeat(2){server.enqueue(MockResponse().setResponseCode(403))}
                assertTrue(runCatching {c.refreshAfterSignIn("login",a.copy(name="Updated A"))}.isFailure)
                assertNull(c.state.value.scope);assertFalse(c.allowsContinuation(proof));assertEquals("Updated A",c.state.value.displayName)
                assertFalse(c.state.value.cached);assertEquals("Updated A",cache.read("login",env)?.displayName)
            }
        }
    }
    @Test fun lateIneligibleReadCannotOverwriteTheReplacementPrincipalsDisplayCache()=runBlocking<Unit> {
        MockWebServer().use {server->
            var root=JSONObject().put("task_session","login").put("refresh_token","fixture-refresh");val lock=Any()
            val cache=NativeAccountProfileCache({synchronized(lock){root}},{change->synchronized(lock){root=JSONObject(root.toString()).also(change)}})
            val identity=AtomicReference(NativeAuthIdentity(0,a.id));val env="${server.url("/api/v1/")}#${NativeAccount.PROJECT_ID}"
            NativeAccountTeams({"fixture-token"},{"login"},server.url("/api/v1/"),cache=cache,lock=lock,sessionIdentity=identity::get).use {c->
                val(_,proof)=establish(server,c);repeat(3){server.takeRequest()}
                repeat(2){server.enqueue(reply(user("is_anonymous")).setBodyDelay(200,TimeUnit.MILLISECONDS))}
                val pending=async(Dispatchers.IO){runCatching {c.refresh()}}
                assertNotNull(withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS)})
                synchronized(lock){identity.set(NativeAuthIdentity(1,"account-b"));NativeAccountProfileCache.writeVerifiedUser(root,"login",env,a.copy(id="account-b",name="B"))}
                assertTrue(pending.await().isFailure);assertFalse(c.allowsContinuation(proof));assertEquals("B",cache.read("login",env)?.displayName)
                c.reconcileLogin();assertEquals("account-b",c.state.value.userId);assertNull(c.state.value.scope)
            }
        }
    }
    @Test fun malformedUserAndMembershipBodiesCannotLeakTheirContentsIntoPresentation()=runBlocking<Unit> {
        MockWebServer().use {server->controller(server).use {c->
            val(scope,proof)=establish(server,c)
            repeat(2){server.enqueue(MockResponse().setBody("private-user-body-canary"))};assertTrue(runCatching {c.refresh()}.exceptionOrNull() is NativeSessionUserUnavailable)
            assertFalse(c.state.value.error.orEmpty().contains("private-user-body-canary"));assertTrue(c.allowsContinuation(proof))
            repeat(2){server.enqueue(reply(user()))};server.enqueue(MockResponse().setBody("private-team-body-canary"));assertTrue(runCatching {c.refresh()}.isFailure)
            assertFalse(c.state.value.error.orEmpty().contains("private-team-body-canary"));assertEquals(scope,c.state.value.scope)
        }}
    }
}
