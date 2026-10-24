package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NativeTeamMutationTest {
    private val user=NativeSignInUser("user","User",null,"one")
    private fun profile(flag:String?=null)=MockResponse().setBody(JSONObject().put("id","user").apply{if(flag!=null)put(flag,true)}.toString())
    private fun members(ids:String="one,two")=MockResponse().setBody(JSONObject().put("items",org.json.JSONArray(ids.split(',').filter{it.isNotEmpty()}
        .map{JSONObject().put("id",it).put("display_name",it)})).toString())
    private fun created()=MockResponse().setBody("{\"team\":{\"id\":\"new\",\"name\":\"New team\"}}")
    private fun controller(server:MockWebServer,identity:AtomicReference<NativeAuthIdentity> = AtomicReference(NativeAuthIdentity(0,"user")),cache:NativeAccountProfileCache?=null)=
        NativeAccountTeams({"fixture-access"},{"login"},server.url("/api/v1/"),refreshCredential={"fixture-refresh"},
            backendOrigin=server.url("/"),sessionIdentity=identity::get,cache=cache)
    private suspend fun establish(server:MockWebServer,c:NativeAccountTeams):NativeTeamScope {
        repeat(2){server.enqueue(profile())};server.enqueue(members());return checkNotNull(c.refreshAfterSignIn("login",user).scope)
    }
    @Test fun effectiveSelectionMatchesSevenCasesExecutedFromTheWholeOriginalResolveDeclaration() {
        val rows=JSONObject(checkNotNull(javaClass.getResourceAsStream("/native-team-selection-reference.json"))
            .bufferedReader().use{it.readText()}).getJSONArray("resolved");assertEquals(7,rows.length())
        for(i in 0 until rows.length()){val row=rows.getJSONObject(i);val members=row.getJSONArray("members")
            val teams=(0 until members.length()).map{NativeTeam(members.getString(it),members.getString(it))}
            assertEquals(row.toString(),if(row.isNull("resolved"))null else row.getString("resolved"),
                nativeResolvedTeamId(if(row.isNull("selected"))null else row.getString("selected"),teams))
        }
    }
    @Test fun selectionWireAndOptionalResponseHandlingMatchNineExecutedOriginalCases()=runBlocking<Unit> {
        val data=JSONObject(checkNotNull(javaClass.getResourceAsStream("/native-team-selection-reference.json"))
            .bufferedReader().use{it.readText()});assertEquals("a5911a1f92f3eeb11c4d7f3e2ef89204718c5009",data.getString("upstream"))
        val rows=data.getJSONArray("rows");assertEquals(9,rows.length())
        for(i in 0 until rows.length()){val row=rows.getJSONObject(i);MockWebServer().use{server->controller(server).use{c->
            establish(server,c);repeat(3){server.takeRequest()};server.enqueue(profile())
            server.enqueue(MockResponse().setBody(when(row.getString("responseKind")){"json"->"{\"id\":\"other-user\"}";"empty"->"";else->"private-response-canary"}))
            val id=if(row.isNull("selected"))null else row.getString("selected");assertTrue(row.getBoolean("succeeded"));assertEquals(id,c.select(id).selectedTeamId)
            assertEquals("GET",server.takeRequest().method);val patch=server.takeRequest();val expected=row.getJSONObject("request")
            assertEquals("/api/v1"+expected.getString("path"),patch.path);assertEquals(expected.getString("method"),patch.method)
            assertEquals(expected.getJSONObject("body").toString(),JSONObject(patch.body.readUtf8()).toString())
            assertTrue(expected.getBoolean("authenticated"));assertEquals("fixture-access",patch.getHeader("x-stack-access-token"))
        }}}
    }
    @Test fun selectionRevalidatesEligibilityBeforeSendingAnyPatchAndPreservesTheLoadedScopeOnFailure()=runBlocking<Unit> {
        for(flag in listOf("is_anonymous","is_restricted"))MockWebServer().use{server->controller(server).use{c->
            val before=establish(server,c);val proof=checkNotNull(c.captureContinuation(before));repeat(3){server.takeRequest()}
            server.enqueue(profile(flag));assertTrue(runCatching{c.select("two")}.exceptionOrNull() is NativeSessionUserUnavailable)
            assertEquals(4,server.requestCount);assertEquals("GET",server.takeRequest().method);assertEquals(before,c.state.value.scope)
            assertTrue(c.allowsContinuation(proof));assertTrue(c.hasLoadedMembership)
        }}
    }
    @Test fun ordinaryAndMalformedUserFailuresStopBeforePatchButRateLimitRemainsTyped()=runBlocking<Unit> {
        for(reply in listOf(MockResponse().setResponseCode(503),MockResponse().setBody("private-canary"),
            MockResponse().setResponseCode(429).setHeader("Retry-After","3")))MockWebServer().use{server->controller(server).use{c->
            val before=establish(server,c);server.enqueue(reply);val e=runCatching{c.select("two")}.exceptionOrNull()
            assertTrue(e is NativeSessionUserUnavailable || e is NativeAccountRateLimited)
            assertFalse(c.state.value.error.orEmpty().contains("canary"));assertEquals(4,server.requestCount);assertEquals(before,c.state.value.scope)
        }}
    }
    @Test fun aSuccessfulPatchDoesNotRequireOptionalResponseJsonOrAnEchoedSelection()=runBlocking<Unit> {
        for(reply in listOf(MockResponse().setResponseCode(204),MockResponse().setBody("private-not-json"),
            MockResponse().setBody("{}"),MockResponse().setBody("{\"id\":\"other\",\"selected_team\":{\"id\":\"one\"}}")))
            MockWebServer().use{server->controller(server).use{c->
                establish(server,c);server.enqueue(profile());server.enqueue(reply)
                assertEquals("two",c.select("two").selectedTeamId);assertEquals("two",c.state.value.scope?.teamId)
                assertFalse(c.state.value.toString().contains("private-not-json"));assertEquals(5,server.requestCount)
            }}
    }
    @Test fun reselectingTheSameTeamStillPersistsWithoutReplacingItsAdmissionScope()=runBlocking<Unit> {
        MockWebServer().use{server->controller(server).use{c->val before=establish(server,c);repeat(3){server.takeRequest()}
            server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204));assertEquals(before,c.select("one").scope)
            assertEquals("GET",server.takeRequest().method);val patch=server.takeRequest();assertEquals("PATCH",patch.method)
            assertEquals("one",JSONObject(patch.body.readUtf8()).getString("selected_team_id"))
        }}
    }
    @Test fun nilSelectionMatchesTheSdkOmittedFieldAndDoesNotForgetLoadedMembership()=runBlocking<Unit> {
        MockWebServer().use{server->controller(server).use{c->val before=establish(server,c);val proof=checkNotNull(c.captureContinuation(before));repeat(3){server.takeRequest()}
            server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204));val value=c.select(null)
            assertNull(value.selectedTeamId);assertEquals(before,value.scope);assertTrue(c.hasLoadedMembership);assertTrue(c.allowsContinuation(proof))
            server.takeRequest();assertEquals(0,JSONObject(server.takeRequest().body.readUtf8()).length())
        }}
    }
    @Test fun backendCreationDoesNotAddAPreflightUserRequestAndContinuesAfterAFailedList()=runBlocking<Unit> {
        MockWebServer().use{server->controller(server).use{c->establish(server,c);repeat(3){server.takeRequest()}
            server.enqueue(created());server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204));assertEquals("new",c.create("New team").selectedTeamId)
            val requests=(1..5).map{server.takeRequest()};assertEquals(listOf("POST","GET","GET","GET","PATCH"),requests.map{it.method})
            assertEquals("/api/subrouter/teams",requests.first().path);assertEquals(listOf("one","two","new"),c.state.value.teams.map{it.id})
        }}
    }
    @Test fun aFailedEligibilityReadDuringPostCreateListingStillAllowsAFreshSelectionAttempt()=runBlocking<Unit> {
        MockWebServer().use{server->controller(server).use{c->establish(server,c);repeat(3){server.takeRequest()}
            server.enqueue(created());server.enqueue(profile("is_restricted"));server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204))
            assertEquals("new",c.create("New team").selectedTeamId)
            assertEquals(listOf("POST","GET","GET","PATCH"),(1..4).map{server.takeRequest().method})
        }}
    }
    @Test fun creationAfterInitialMembershipFailureDoesNotInventLoadedMembershipOrConnectionAuthority()=runBlocking<Unit> {
        MockWebServer().use{server->controller(server).use{c->repeat(2){server.enqueue(MockResponse().setResponseCode(503))}
            assertTrue(runCatching{c.refreshAfterSignIn("login",user)}.isFailure);assertFalse(c.hasLoadedMembership)
            server.enqueue(created());server.enqueue(profile());server.enqueue(members("new"));server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204))
            val value=c.create("New team");assertEquals("new",value.selectedTeamId);assertEquals(listOf("new"),value.teams.map{it.id})
            assertNull(value.scope);assertFalse(c.hasLoadedMembership)
            repeat(2){server.enqueue(profile())};server.enqueue(members("new"));assertNotNull(c.refreshAfterSignIn("login",user.copy(selectedTeam="new")).scope)
            assertTrue(c.hasLoadedMembership)
        }}
    }
    @Test fun refreshedMembershipRetiresRemovedProofEvenWhenTheFinalSelectionFails()=runBlocking<Unit> {
        MockWebServer().use{server->controller(server).use{c->val before=establish(server,c);val proof=checkNotNull(c.captureContinuation(before))
            server.enqueue(created());server.enqueue(profile());server.enqueue(members("two,new"));server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(403))
            assertTrue(runCatching{c.create("New team")}.isFailure);assertEquals(listOf("two","new"),c.state.value.teams.map{it.id})
            assertEquals("two",c.state.value.scope?.teamId);assertFalse(c.isCurrent(before));assertFalse(c.allowsContinuation(proof));assertTrue(c.hasLoadedMembership)
        }}
    }
    @Test fun aPrimedCachedAccountCanCreateThroughTheBackendWithoutInventingMembershipAuthority()=runBlocking<Unit> {
        MockWebServer().use{server->
            var root=JSONObject().put("task_session","login").put("refresh_token","fixture-refresh")
            val cache=NativeAccountProfileCache({root},{update->root=JSONObject(root.toString()).also(update)})
            controller(server,cache=cache).use{establish(server,it)}
            controller(server,cache=cache).use{c->assertTrue(c.state.value.cached);assertFalse(c.hasLoadedMembership)
                server.enqueue(created());server.enqueue(profile());server.enqueue(members());server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204))
                assertEquals("new",c.create("New team").selectedTeamId);assertNull(c.state.value.scope);assertFalse(c.hasLoadedMembership)
                assertEquals("POST",(1..4).map{server.takeRequest().method}.last())
            }
        }
    }
    @Test fun freshUserFromAReplacementPrincipalCannotAuthorizeAPatch()=runBlocking<Unit> {
        MockWebServer().use{server->controller(server).use{c->val before=establish(server,c)
            server.enqueue(MockResponse().setBody("{\"id\":\"other\"}"));assertTrue(runCatching{c.select("two")}.exceptionOrNull() is CancellationException)
            assertEquals(4,server.requestCount);assertFalse(c.isCurrent(before));assertNull(c.state.value.scope)
        }}
    }
    @Test fun parallelSelectionResponsesCannotRebindAfterASameUserGenerationChange()=runBlocking<Unit> {
        MockWebServer().use{server->val identity=AtomicReference(NativeAuthIdentity(0,"user"));controller(server,identity).use{c->
            establish(server,c);repeat(3){server.takeRequest()};repeat(2){server.enqueue(profile().setBodyDelay(150,TimeUnit.MILLISECONDS))}
            val first=async{runCatching{c.select("two")}};assertNotNull(withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS)})
            val second=async(start=CoroutineStart.UNDISPATCHED){runCatching{c.select("one")}}
            assertNotNull(withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS)});identity.set(NativeAuthIdentity(1,"user"))
            assertTrue(first.await().isFailure);assertTrue(second.await().isFailure);assertEquals(5,server.requestCount)
        }}
    }
    @Test fun aNewChoiceCompletesWhileTheSupersededPatchIsBlockedButCreateWaitsForAllSwitches()=runBlocking<Unit> {
        MockWebServer().use{server->controller(server).use{c->establish(server,c)
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val patches=AtomicInteger();val posts=AtomicInteger()
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.method=="POST"){posts.incrementAndGet();return MockResponse().setResponseCode(503)}
                if(request.method=="GET")return profile()
                if(patches.incrementAndGet()==1){entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
                return MockResponse().setResponseCode(204)
            }}
            val first=async{runCatching{c.select("two")}}
            try {
                assertTrue(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)})
                assertEquals("one",withTimeout(2000){c.select("one")}.selectedTeamId);assertFalse(first.isCompleted)
                assertTrue(c.state.value.loading);assertTrue(runCatching{c.create("Blocked")}.isFailure);assertEquals(0,posts.get())
                release.countDown();assertTrue(first.await().isFailure);assertFalse(c.state.value.loading)
                assertEquals("one",c.state.value.selectedTeamId);assertTrue(runCatching{c.create("Now admitted")}.isFailure);assertEquals(1,posts.get())
            } finally {release.countDown();first.cancelAndJoin()}
        }}
    }
    @Test fun aLaterValidChoiceWinsAndAnUnknownChoiceCannotInvalidateAnAlreadySentSelection()=runBlocking<Unit> {
        MockWebServer().use{server->controller(server).use{c->establish(server,c);repeat(3){server.takeRequest()}
            server.enqueue(profile());server.enqueue(MockResponse().setBody("{}").setBodyDelay(150,TimeUnit.MILLISECONDS))
            val first=async{runCatching{c.select("two")}};withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS);server.takeRequest(2,TimeUnit.SECONDS)}
            assertTrue(runCatching{c.select("missing")}.isFailure)
            server.enqueue(profile());server.enqueue(MockResponse().setBody("{}"))
            val last=async(start=CoroutineStart.UNDISPATCHED){c.select("one")}
            assertTrue(first.await().isFailure);assertEquals("one",last.await().selectedTeamId);assertEquals(7,server.requestCount)
        }}
    }
    @Test fun anUnknownChoiceDoesNotInvalidateAnAlreadySentValidMutation()=runBlocking<Unit> {
        MockWebServer().use{server->controller(server).use{c->establish(server,c);repeat(3){server.takeRequest()}
            server.enqueue(profile());server.enqueue(MockResponse().setBody("{}").setBodyDelay(150,TimeUnit.MILLISECONDS))
            val first=async{c.select("two")};withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS);server.takeRequest(2,TimeUnit.SECONDS)}
            assertTrue(runCatching{c.select("missing")}.isFailure);assertEquals("two",first.await().selectedTeamId);assertEquals(5,server.requestCount)
        }}
    }
    @Test fun createAndSelectionExcludeEachOtherWithoutQueuingDuplicateCreation()=runBlocking<Unit> {
        MockWebServer().use{server->controller(server).use{c->establish(server,c);repeat(3){server.takeRequest()}
            server.enqueue(created().setBodyDelay(150,TimeUnit.MILLISECONDS));server.enqueue(profile());server.enqueue(members());server.enqueue(profile());server.enqueue(MockResponse().setBody("{}"))
            val pending=async{c.create("New team")};assertEquals("POST",withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS)!!.method})
            assertTrue(runCatching{c.create("Duplicate")}.isFailure);assertTrue(runCatching{c.select("two")}.isFailure)
            assertEquals("new",pending.await().selectedTeamId);assertEquals(8,server.requestCount)
            server.enqueue(profile().setBodyDelay(150,TimeUnit.MILLISECONDS));server.enqueue(MockResponse().setBody("{}"))
            val selection=async{c.select("one")};withContext(Dispatchers.IO){repeat(4){server.takeRequest()};server.takeRequest(2,TimeUnit.SECONDS)}
            assertTrue(runCatching{c.create("Concurrent")}.isFailure);assertEquals("one",selection.await().selectedTeamId)
        }}
    }
}
