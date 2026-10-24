package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Route independent replies through token seams, without relying on concurrent HTTP arrival order. */
class NativeTeamRefreshReadsTest {
    private val published=NativeSignInUser("user","Published profile","published@example.test","one")
    private fun profile(selected:String?="two",name:String="Fresh SDK profile")=MockResponse().setBody(JSONObject()
        .put("id","user").put("display_name",name).put("selected_team",selected?.let{JSONObject().put("id",it)} ?: JSONObject.NULL).toString())
    private fun members(ids:List<String> = listOf("one","two"))=MockResponse().setBody(JSONObject().put("items",org.json.JSONArray(ids.map {
        JSONObject().put("id",it).put("display_name",it)
    })).toString())
    private fun reference()=JSONObject(checkNotNull(javaClass.getResourceAsStream("/native-team-refresh-reference.json"))
        .bufferedReader().use{it.readText()})
    private class Fixture(val server:MockWebServer,timeout:Long=2000,initial:NativeAccountTeamsState?=null):AutoCloseable {
        var primary:()->MockResponse={MockResponse().setBody("{\"id\":\"user\",\"selected_team\":{\"id\":\"one\"}}")}
        var selection:()->MockResponse={MockResponse().setBody("{\"id\":\"user\",\"selected_team\":{\"id\":\"two\"}}")}
        var list:()->MockResponse={MockResponse().setBody("{\"items\":[{\"id\":\"one\",\"display_name\":\"One\"},{\"id\":\"two\",\"display_name\":\"Two\"}]}")}
        var tokenRead:suspend (String)->String?={it}
        val work=NativeAuthWork()
        val identity=AtomicReference(NativeAuthIdentity(0,"user"))
        private var root=JSONObject().put("task_session","login").put("refresh_token","fixture-refresh")
        private val cache=initial?.let {state->NativeAccountProfileCache({root},{update->root=JSONObject(root.toString()).also(update)}).also {
            it.save("login","${server.url("/api/v1/")}#${NativeAccount.PROJECT_ID}",state)
        }}
        val teams=NativeAccountTeams({tokenRead(currentCoroutineContext()[CoroutineName]?.name ?: "other")},{"login"},server.url("/api/v1/"),
            sessionIdentity=identity::get,phaseWork=work,networkTimeoutMillis=timeout,cache=cache)
        init {server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when {
            request.path=="/api/v1/teams?user_id=me"->list()
            request.path=="/api/v1/users/me" && request.getHeader("x-hexclave-access-token")=="team-selection-read"->selection()
            request.path=="/api/v1/users/me"->primary()
            else->MockResponse().setResponseCode(404)
        }}}
        override fun close(){teams.close();work.cancelAll()}
    }
    private fun requests(server:MockWebServer)=List(server.requestCount){checkNotNull(server.takeRequest(1,TimeUnit.SECONDS))}
    @Test fun callbackReadsFreshMembershipUserAndIndependentSelectionInsteadOfReusingItsPublishedUser()=runBlocking<Unit> {
        MockWebServer().use{server->Fixture(server).use{f->val result=f.teams.refreshAfterSignIn("login",published)
            assertEquals("two",result.selectedTeamId);assertEquals("two",result.scope?.teamId)
            assertEquals(published.name,result.displayName);assertEquals(published.email,result.email)
            val calls=requests(server);assertEquals(3,calls.size);assertEquals(2,calls.count{it.path=="/api/v1/users/me"})
            assertEquals(1,calls.count{it.path=="/api/v1/teams?user_id=me"})
        }}
    }
    @Test fun readsStartInParallelAndWaitForSelectionWithoutPublishingAPartialTeamScope()=runBlocking<Unit> {
        MockWebServer().use{server->Fixture(server).use{f->val primaryStarted=CountDownLatch(1);val selectionStarted=CountDownLatch(1);val release=CountDownLatch(1)
            f.primary={primaryStarted.countDown();check(selectionStarted.await(1,TimeUnit.SECONDS));profile("one")}
            f.selection={selectionStarted.countDown();check(primaryStarted.await(1,TimeUnit.SECONDS));check(release.await(5,TimeUnit.SECONDS));profile("two")}
            val pending=async{f.teams.refreshAfterSignIn("login",published)}
            try {assertTrue(withContext(Dispatchers.IO){selectionStarted.await(2,TimeUnit.SECONDS)})
                withTimeout(2000){while(server.requestCount<3)delay(1)};assertFalse(pending.isCompleted);assertNull(f.teams.state.value.scope)
                release.countDown();assertEquals("two",pending.await().scope?.teamId)
            }finally{release.countDown();pending.cancelAndJoin()}
        }}
    }
    @Test fun failedSelectedReadIsBestEffortAndDoesNotUseTheMembershipUsersSelectedField()=runBlocking<Unit> {
        for(reply in listOf(MockResponse().setResponseCode(503),MockResponse().setResponseCode(429).setHeader("Retry-After","3"),
            MockResponse().setBody("not-json"),MockResponse().setBody("{\"id\":\"user\",\"is_restricted\":true}")))
            MockWebServer().use{server->Fixture(server).use{f->
                f.teams.refreshAfterSignIn("login",published);assertEquals("two",f.teams.state.value.selectedTeamId)
                f.primary={profile("one")};f.selection={reply};val result=f.teams.refreshAfterSignIn("login",published)
                assertEquals("two",result.selectedTeamId);assertEquals("two",result.scope?.teamId);assertTrue(f.teams.hasLoadedMembership);assertNull(result.error)
            }}
    }
    @Test fun freshMembershipEligibilityFailureCannotBeBypassedByThePublishedUserOrSuccessfulSelection()=runBlocking<Unit> {
        MockWebServer().use{server->Fixture(server).use{f->f.primary={MockResponse().setBody("{\"id\":\"user\",\"is_anonymous\":true}")}
            assertTrue(runCatching{f.teams.refreshAfterSignIn("login",published)}.isFailure)
            assertFalse(f.teams.hasLoadedMembership);assertNull(f.teams.state.value.scope);assertEquals(published.name,f.teams.state.value.displayName)
            assertTrue(requests(server).none{it.path?.contains("/teams?")==true})
        }}
    }
    @Test fun successfulEmptyMembershipPreservesTheRememberedChoiceWithoutAnyConnectionAuthority()=runBlocking<Unit> {
        MockWebServer().use{server->Fixture(server).use{f->val first=f.teams.refreshAfterSignIn("login",published);val old=checkNotNull(first.scope)
            val proof=checkNotNull(f.teams.captureContinuation(old));f.list={members(emptyList())};f.selection={profile(null)}
            val empty=f.teams.refreshAfterSignIn("login",published)
            assertEquals("two",empty.selectedTeamId);assertTrue(empty.teams.isEmpty());assertNull(empty.scope)
            assertTrue(f.teams.hasLoadedMembership);assertFalse(f.teams.allowsContinuation(proof));assertNull(f.teams.captureContinuation(old))
        }}
    }
    @Test fun emptyMembershipKeepsServerSelectedIdsButNonemptyMembershipResolvesAnUnavailableSelection()=runBlocking<Unit> {
        MockWebServer().use{server->Fixture(server).use{f->f.list={members(emptyList())};f.selection={profile("unavailable")}
            assertEquals("unavailable",f.teams.refreshAfterSignIn("login",published).selectedTeamId)
            f.list={members()};val loaded=f.teams.refreshAfterSignIn("login",published)
            assertEquals("one",loaded.selectedTeamId);assertEquals("one",loaded.scope?.teamId)
        }}
    }
    @Test fun selectedProjectionUsesSdkTeamDefaultsAndIgnoresUnrelatedProfileFieldTypes()=runBlocking<Unit> {
        val rows=reference().getJSONArray("selectedProjection");assertEquals(10,rows.length())
        for(i in 0 until rows.length()) {val row=rows.getJSONObject(i);val payload=row.getJSONObject("payload")
            val expected=row.opt("selected").takeUnless{it===JSONObject.NULL}
            MockWebServer().use{server->Fixture(server).use{f->f.list={members(emptyList())};f.selection={MockResponse().setBody(JSONObject(payload.toString()).put("id","user").toString())}
                assertEquals(expected,f.teams.refreshAfterSignIn("login",published).selectedTeamId)
            }}
        }
    }
    @Test fun refreshProjectionMatchesTheExecutedOriginalCoordinator()=runBlocking<Unit> {
        val rows=reference().getJSONArray("refresh");assertEquals(10,rows.length())
        fun optional(row:JSONObject,key:String)=row.opt(key).takeUnless{it===JSONObject.NULL} as? String
        for(i in 0 until rows.length()) {val row=rows.getJSONObject(i);assertTrue(row.getBoolean("parallel"))
            MockWebServer().use{server->Fixture(server,initial=NativeAccountTeamsState("user",listOf(NativeTeam("old","Old")),optional(row,"remembered"))).use{f->
                val failure=optional(row,"failure");val entered=CompletableDeferred<Unit>()
                f.primary={if(failure=="membership")MockResponse().setResponseCode(503) else profile("one")}
                f.selection={if(failure=="selection")MockResponse().setResponseCode(503) else profile(optional(row,"serverSelected"))}
                if(failure=="selectedCancellation")f.tokenRead={if(it=="team-selection-read")throw CancellationException("Fixture selected read cancelled");it}
                f.list={entered.complete(Unit);members(row.getJSONArray("members").let{ids->List(ids.length()){ids.getString(it)}})
                    .apply{if(row.getBoolean("stale"))setBodyDelay(150,TimeUnit.MILLISECONDS)}}
                val pending=async{runCatching{f.teams.refreshAfterSignIn("login",published)}}
                if(row.getBoolean("stale")){entered.await();f.identity.set(NativeAuthIdentity(1,"user"));f.teams.reconcileLogin()}
                val result=pending.await();assertEquals(row.getString("case"),failure!="membership" && !row.getBoolean("stale"),result.isSuccess)
                val state=f.teams.state.value;val ids=row.getJSONArray("publishedMembers")
                assertEquals(row.getString("case"),List(ids.length()){ids.getString(it)},state.teams.map{it.id})
                assertEquals(row.getString("case"),optional(row,"selected"),state.selectedTeamId)
                assertEquals(row.getString("case"),row.getBoolean("loaded"),f.teams.hasLoadedMembership)
                assertEquals(0,f.teams.activeMembershipReads)
            }}
        }
    }
    @Test fun oneDeadlineBoundsASelectionReadThatIgnoresCancellationAndRetainsItsActualParentTask()=runBlocking<Unit> {
        MockWebServer().use{server->Fixture(server,150).use{f->val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
            f.tokenRead={name->if(name=="team-selection-read"){entered.complete(Unit);withContext(NonCancellable){gate.await()}};name}
            val pending=async{runCatching{f.teams.refreshAfterSignIn("login",published)}}
            try {entered.await();val failure=withTimeout(2000){pending.await()}.exceptionOrNull();assertTrue(failure is NativeSignInFailure)
                assertEquals(NativeSignInError.TIMED_OUT,(failure as NativeSignInFailure).reason);assertEquals(0,f.teams.activeMembershipReads)
                assertEquals(1,f.work.activeCount);assertNull(f.teams.state.value.scope)
                gate.complete(Unit);withTimeout(2000){while(f.work.activeCount>0)delay(1)};assertEquals(2,server.requestCount)
            }finally{gate.complete(Unit);pending.cancelAndJoin();withTimeout(2000){while(f.work.activeCount>0)delay(1)}}
        }}
    }
}
