package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NativeTeamPhaseTest {
    @Test fun productionNetworkBudgetMatchesTheWholeOriginalTimeoutsModel() {
        val reference=JSONObject(checkNotNull(javaClass.getResourceAsStream("/native-team-phase-reference.json"))
            .bufferedReader().use{it.readText()})
        assertEquals("a5911a1f92f3eeb11c4d7f3e2ef89204718c5009",reference.getString("upstream"))
        assertEquals(reference.getJSONObject("default").getLong("networkMillis"),NativeAccountTeams.NETWORK_TIMEOUT_MILLIS)
        assertEquals(60000,reference.getLong("foundationRequestDefaultMillis"))
    }
    private val user=NativeSignInUser("user","Original",null,"one")
    private fun profile()=MockResponse().setBody("{\"id\":\"user\"}")
    private fun members(ids:String="one,two")=MockResponse().setBody(JSONObject().put("items",org.json.JSONArray(ids.split(',').filter{it.isNotEmpty()}
        .map{JSONObject().put("id",it).put("display_name",it)})).toString())
    private fun created()=MockResponse().setBody("{\"team\":{\"id\":\"new\",\"name\":\"New\"}}")
    private class Fixture(val server:MockWebServer,val timeout:Long=300,val now:()->Long={System.nanoTime()/1_000_000}) : AutoCloseable {
        var token:suspend (Boolean)->String?={"fixture-access"}
        val work=NativeAuthWork(nowMillis=now)
        val identity=AtomicReference(NativeAuthIdentity(0,"user"))
        val teams=NativeAccountTeams({token(it)},{"login"},server.url("/api/v1/"),refreshCredential={"fixture-refresh"},
            backendOrigin=server.url("/"),sessionIdentity=identity::get,phaseNowMillis=now,phaseWork=work,networkTimeoutMillis=timeout)
        override fun close(){teams.close();work.cancelAll()}
    }
    private suspend fun establish(f:Fixture):NativeTeamScope {f.server.enqueue(members());return checkNotNull(f.teams.refreshAfterSignIn("login",user).scope)}
    private fun timeout(error:Throwable?) {assertTrue(error.toString(),error is NativeSignInFailure);assertEquals(NativeSignInError.TIMED_OUT,(error as NativeSignInFailure).reason)}
    private suspend fun drained(work:NativeAuthWork)=withTimeout(3000){while(work.activeCount!=0)delay(1)}

    @Test fun timedOutSelectionReleasesItsClaimWhileTheActualReadRemainsAndCannotSendLateHttp()=runBlocking<Unit> {
        MockWebServer().use{server->Fixture(server).use{f->val original=establish(f);val entered=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>()
            f.token={entered.complete(Unit);withContext(NonCancellable){gate.await()};"old-access"}
            val old=async{runCatching{f.teams.select("two")}}
            try {entered.await();timeout(withTimeout(2000){old.await()}.exceptionOrNull());assertFalse(f.teams.state.value.loading)
                assertEquals(original,f.teams.state.value.scope);assertEquals(1,f.work.activeCount);assertEquals(1,server.requestCount)
                f.token={"fresh-access"};server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204))
                assertEquals(original,f.teams.select("one").scope)
                gate.complete(Unit);drained(f.work);assertEquals(3,server.requestCount);assertEquals(original,f.teams.state.value.scope)
            }finally{gate.complete(Unit);old.cancelAndJoin();drained(f.work)}
        }}
    }
    @Test fun membershipPublicCountEndsAtDeadlineButTokenTouchingWorkKeepsTheSourceRetryGate()=runBlocking<Unit> {
        MockWebServer().use{server->Fixture(server).use{f->val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
            f.token={entered.complete(Unit);withContext(NonCancellable){gate.await()};"fixture-access"}
            val first=async{runCatching{f.teams.refreshAfterSignIn("login",user)}}
            try{entered.await();assertEquals(1,f.teams.activeMembershipReads);timeout(first.await().exceptionOrNull())
                assertEquals(0,f.teams.activeMembershipReads);assertEquals(1,f.work.activeCount);assertFalse(f.teams.hasLoadedMembership)
                assertEquals(user.name,f.teams.state.value.displayName);assertFalse(f.teams.state.value.loading)
                f.token={"fresh"};timeout(runCatching{f.teams.refreshAfterSignIn("login",user)}.exceptionOrNull());assertEquals(0,server.requestCount)
                gate.complete(Unit);drained(f.work);assertEquals(0,server.requestCount)
                server.enqueue(members());assertNotNull(f.teams.refreshAfterSignIn("login",user).scope)
            }finally{gate.complete(Unit);first.cancelAndJoin();drained(f.work)}
        }}
    }
    @Test fun aSecondMembershipReadIsNotQueuedBehindAStalledFirstRead()=runBlocking<Unit> {
        MockWebServer().use{server->Fixture(server).use{f->val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();val tokens=AtomicInteger()
            f.token={if(tokens.incrementAndGet()==1){entered.complete(Unit);withContext(NonCancellable){gate.await()}};"fixture"}
            val first=async{runCatching{f.teams.refreshAfterSignIn("login",user)}}
            try{entered.await();server.enqueue(members());val winning=withTimeout(2000){f.teams.refreshAfterSignIn("login",user)}
                assertNotNull(winning.scope);assertFalse(first.isCompleted);timeout(first.await().exceptionOrNull())
                assertEquals(0,f.teams.activeMembershipReads);assertEquals(winning.scope,f.teams.state.value.scope)
                gate.complete(Unit);drained(f.work);assertEquals(1,server.requestCount)
            }finally{gate.complete(Unit);first.cancelAndJoin();drained(f.work)}
        }}
    }
    @Test fun oneListBudgetCoversBothFreshUserAndMembershipHttp()=runBlocking<Unit> {
        MockWebServer().use{server->Fixture(server).use{f->server.enqueue(profile().setBodyDelay(190,TimeUnit.MILLISECONDS));server.enqueue(members().setBodyDelay(190,TimeUnit.MILLISECONDS))
            timeout(runCatching{f.teams.refresh()}.exceptionOrNull());assertNull(f.teams.state.value.scope)
            assertEquals(0,f.teams.activeMembershipReads);assertFalse(f.teams.state.value.loading);assertEquals(2,server.requestCount)
            drained(f.work)
        }}
    }
    @Test fun createBackendListAndFinalSelectionEachReceiveTheirOwnBudget()=runBlocking<Unit> {
        MockWebServer().use{server->Fixture(server).use{f->establish(f)
            server.enqueue(created().setBodyDelay(190,TimeUnit.MILLISECONDS));server.enqueue(profile().setBodyDelay(80,TimeUnit.MILLISECONDS))
            server.enqueue(members().setBodyDelay(80,TimeUnit.MILLISECONDS));server.enqueue(profile().setBodyDelay(80,TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(80,TimeUnit.MILLISECONDS))
            val started=System.nanoTime();assertEquals("new",f.teams.create("New").scope?.teamId)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)>f.timeout);assertEquals(6,server.requestCount)
        }}
    }
    @Test fun postCreateListTimeoutFallsBackAndStillSelectsWithoutReplayingThePost()=runBlocking<Unit> {
        MockWebServer().use{server->Fixture(server).use{f->establish(f);val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();val tokens=AtomicInteger()
            f.token={if(tokens.incrementAndGet()==2){entered.complete(Unit);withContext(NonCancellable){gate.await()}};"fixture"}
            server.enqueue(created());server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204))
            val create=async{f.teams.create("New")}
            try{entered.await();assertEquals("new",withTimeout(2000){create.await()}.scope?.teamId);assertEquals(1,f.work.activeCount)
                gate.complete(Unit);drained(f.work);assertEquals(4,server.requestCount)
                assertEquals(1,(1..server.requestCount).count{server.takeRequest().method=="POST"})
            }finally{gate.complete(Unit);create.cancelAndJoin();drained(f.work)}
        }}
    }
    @Test fun failedCreateListUsesAnInterveningRefreshAndDoesNotRestoreItsOldProfileOrMembership()=runBlocking<Unit> {
        MockWebServer().use{server->Fixture(server).use{f->val original=establish(f);val proof=checkNotNull(f.teams.captureContinuation(original))
            val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();val tokens=AtomicInteger()
            f.token={if(tokens.incrementAndGet()==2){entered.complete(Unit);withContext(NonCancellable){gate.await()}};"fixture"}
            server.enqueue(created());server.enqueue(members("two"));server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204))
            val create=async{f.teams.create("New")}
            try{entered.await();f.teams.refreshAfterSignIn("login",user.copy(name="Updated",selectedTeam="two"))
                val result=withTimeout(2000){create.await()};assertEquals(listOf("two","new"),result.teams.map{it.id});assertEquals("Updated",result.displayName)
                assertFalse(f.teams.allowsContinuation(proof));gate.complete(Unit);drained(f.work);assertEquals(5,server.requestCount)
            }finally{gate.complete(Unit);create.cancelAndJoin();drained(f.work)}
        }}
    }
    @Test fun aTimedOutBackendNeverPublishesItsLateCreatedTeamOrSendsAPostAfterTheTokenRead()=runBlocking<Unit> {
        MockWebServer().use{server->Fixture(server).use{f->establish(f);val gate=CompletableDeferred<Unit>();f.token={withContext(NonCancellable){gate.await()};"fixture"}
            val attempt=async{runCatching{f.teams.create("New")}}
            try{timeout(attempt.await().exceptionOrNull());assertNull(f.teams.state.value.createdTeam);assertFalse(f.teams.state.value.loading)
                assertTrue(f.teams.state.value.error!!.contains("not confirmed"));gate.complete(Unit);drained(f.work);assertEquals(1,server.requestCount)
            }finally{gate.complete(Unit);attempt.cancelAndJoin();drained(f.work)}
        }}
    }
    @Test fun finalSelectionTimeoutKeepsTheConfirmedTeamAndASeparateSelectionCanFinishWithoutAnotherCreate()=runBlocking<Unit> {
        MockWebServer().use{server->Fixture(server).use{f->val original=establish(f);val gate=CompletableDeferred<Unit>();val tokens=AtomicInteger()
            f.token={if(tokens.incrementAndGet()==4)withContext(NonCancellable){gate.await()};"fixture"}
            server.enqueue(created());server.enqueue(profile());server.enqueue(members())
            val create=async{runCatching{f.teams.create("New")}}
            try{timeout(create.await().exceptionOrNull());assertEquals("new",f.teams.state.value.createdTeam?.id);assertEquals(original,f.teams.state.value.scope)
                assertTrue(f.teams.state.value.error!!.contains("switching timed out"));server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204))
                assertEquals("new",f.teams.select("new").scope?.teamId);gate.complete(Unit);drained(f.work)
                assertEquals(1,(1..server.requestCount).count{server.takeRequest().method=="POST"})
            }finally{gate.complete(Unit);create.cancelAndJoin();drained(f.work)}
        }}
    }
    @Test fun callerCancellationRemainsCancellationAndReleasesThePublicSelectionClaim()=runBlocking<Unit> {
        MockWebServer().use{server->Fixture(server).use{f->val original=establish(f);val entered=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>()
            f.token={entered.complete(Unit);withContext(NonCancellable){gate.await()};"fixture"}
            val attempt=async{f.teams.select("two")}
            try{entered.await();attempt.cancel();assertTrue(runCatching{attempt.await()}.exceptionOrNull() is CancellationException)
                assertFalse(f.teams.state.value.loading);assertEquals(original,f.teams.state.value.scope)
                gate.complete(Unit);drained(f.work);assertEquals(1,server.requestCount)
            }finally{gate.complete(Unit);attempt.cancelAndJoin();drained(f.work)}
        }}
    }
    @Test fun tokenDeadlineRejectsWorkAfterElapsedExpiryButAPlainPhaseUsesItsActualRaceWinner()=runBlocking<Unit> {
        MockWebServer().use{server->val now=AtomicLong();Fixture(server,now=now::get).use{f->
            f.token={now.addAndGet(301);"fixture"};timeout(runCatching{f.teams.refreshAfterSignIn("login",user)}.exceptionOrNull());assertEquals(0,server.requestCount)
            f.token={"fixture"};establish(f);f.token={now.addAndGet(301);"fixture"}
            server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204));assertEquals("two",f.teams.select("two").scope?.teamId)
        }}
    }
    @Test fun aSelectionFinishingAfterMembershipRemovalCannotRestoreItsOldMemberOrProfileSnapshot()=runBlocking<Unit> {
        MockWebServer().use{server->Fixture(server,timeout=2000).use{f->server.enqueue(members());val original=checkNotNull(f.teams.refreshAfterSignIn("login",user.copy(selectedTeam="two")).scope)
            val proof=checkNotNull(f.teams.captureContinuation(original));val entered=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.method=="PATCH"){entered.countDown();check(release.await(5,TimeUnit.SECONDS));return MockResponse().setResponseCode(204)}
                return if(request.path!!.startsWith("/api/v1/teams"))members("one") else profile()
            }}
            val select=async{f.teams.select("two")}
            try{assertTrue(withContext(Dispatchers.IO){entered.await(2,TimeUnit.SECONDS)})
                f.teams.refreshAfterSignIn("login",user.copy(name="Updated"));assertFalse(f.teams.allowsContinuation(proof))
                release.countDown();val result=select.await();assertEquals(listOf("one"),result.teams.map{it.id});assertEquals("Updated",result.displayName)
                assertEquals("one",result.scope?.teamId);assertFalse(f.teams.allowsContinuation(proof))
            }finally{release.countDown();select.cancelAndJoin();drained(f.work)}
        }}
    }
    @Test fun cachedOptionalSelectionRequiresFreshHttpButCannotPromoteSavedRowsToAuthority()=runBlocking<Unit> {
        MockWebServer().use{server->var root=JSONObject().put("task_session","login").put("refresh_token","fixture")
            val cache=NativeAccountProfileCache({root},{update->root=JSONObject(root.toString()).also(update)})
            val env="${server.url("/api/v1/")}#${NativeAccount.PROJECT_ID}"
            cache.save("login",env,NativeAccountTeamsState("user",listOf(NativeTeam("one","One"),NativeTeam("two","Two")),"two"))
            NativeAccountTeams({"fixture"},{"login"},server.url("/api/v1/"),cache=cache,sessionIdentity={NativeAuthIdentity(0,"user")}).use{c->
                server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204));val result=c.select(null)
                assertNull(result.scope);assertNull(result.selectedTeamId);assertTrue(result.cached);assertFalse(c.hasLoadedMembership)
                assertNull(result.error);assertNotNull(cache.read("login",env));assertNull(cache.read("login",env)!!.selectedTeamId)
                assertTrue(runCatching{c.select("one")}.isFailure);assertEquals(2,server.requestCount)
            }
        }
    }
    @Test fun cachePersistenceObservesMembershipAndPublishedScopeAsOneAtomicProjection()=runBlocking<Unit> {
        MockWebServer().use{server->
            val lock=Any();var root=JSONObject().put("task_session","login").put("refresh_token","fixture")
            val owner=AtomicReference<NativeAccountTeams?>();var observations=0
            val cache=NativeAccountProfileCache({root},{update->
                val c=owner.get();val active=c?.state?.value?.scope
                if(active!=null){assertNotNull("A published scope must retain its matching membership proof during persistence",c.captureContinuation(active));observations++}
                root=JSONObject(root.toString()).also(update)
            })
            NativeAccountTeams({"fixture"},{"login"},server.url("/api/v1/"),cache=cache,lock=lock,
                sessionIdentity={NativeAuthIdentity(0,"user")}).use{c->owner.set(c)
                server.enqueue(members());val old=checkNotNull(c.refreshAfterSignIn("login",user.copy(selectedTeam="two")).scope)
                val proof=checkNotNull(c.captureContinuation(old));server.enqueue(members("one"))
                assertEquals("one",c.refreshAfterSignIn("login",user.copy(name="Updated")).scope?.teamId)
                assertFalse(c.allowsContinuation(proof));assertTrue(observations>0)
            }
        }
    }
}
