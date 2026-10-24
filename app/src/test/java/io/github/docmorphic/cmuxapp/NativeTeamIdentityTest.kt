package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NativeTeamIdentityTest {
    private val a=NativeSignInUser("a","A",null,"one")
    private fun teams()=MockResponse().setBody("{\"items\":[{\"id\":\"one\",\"display_name\":\"One\"}]}")
    private fun user(id:String)=MockResponse().setBody("{\"id\":\"$id\",\"selected_team\":{\"id\":\"one\"}}")
    @Test fun publishedPrincipalChangesCloseAdmissionAndContinuationBeforeReconciliationAndCannotAbaRevive()=runBlocking<Unit> {
        MockWebServer().use { server ->
            val identity=AtomicReference(NativeAuthIdentity(0,"a"))
            NativeAccountTeams({"token"},{"login"},server.url("/api/v1/"),sessionIdentity=identity::get).use { controller ->
                server.enqueue(teams());val old=checkNotNull(controller.refreshAfterSignIn("login",a).scope)
                val proof=checkNotNull(controller.captureContinuation(old));assertTrue(controller.isCurrent(old))
                identity.set(NativeAuthIdentity(1,"b"));assertFalse(controller.isCurrent(old));assertFalse(controller.allowsContinuation(proof))
                identity.set(NativeAuthIdentity(2,"a"));assertFalse(controller.isCurrent(old));assertFalse(controller.allowsContinuation(proof))
                server.enqueue(teams());val fresh=checkNotNull(controller.refreshAfterSignIn("login",a).scope)
                assertNotEquals(old.generation,fresh.generation);assertTrue(controller.isCurrent(fresh));assertFalse(controller.allowsContinuation(proof))
            }
        }
    }
    @Test fun samePublishedIdentityKeepsScopeAndContinuationAcrossMembershipRefresh()=runBlocking<Unit> {
        MockWebServer().use { server ->
            val identity=AtomicReference(NativeAuthIdentity(0,"a"))
            NativeAccountTeams({"token"},{"login"},server.url("/api/v1/"),sessionIdentity=identity::get).use { controller ->
                server.enqueue(teams());val old=checkNotNull(controller.refreshAfterSignIn("login",a).scope)
                val proof=checkNotNull(controller.captureContinuation(old));server.enqueue(user("a"));server.enqueue(teams())
                val fresh=controller.refresh().scope;assertEquals(old,fresh);assertTrue(controller.allowsContinuation(proof))
            }
        }
    }
    @Test fun storedLoginAndCredentialsWithoutAPublishedIdentityCannotStartMembershipHttp()=runBlocking<Unit> {
        MockWebServer().use { server ->
            val identity=AtomicReference<NativeAuthIdentity?>(null)
            NativeAccountTeams({"token"},{"login"},server.url("/api/v1/"),sessionIdentity=identity::get).use { controller ->
                assertTrue(runCatching {controller.refresh()}.exceptionOrNull() is CancellationException)
                assertEquals(0,server.requestCount);assertNull(controller.state.value.scope)
                identity.set(NativeAuthIdentity(0,"a"));server.enqueue(teams());assertNotNull(controller.refreshAfterSignIn("login",a).scope)
            }
        }
    }
    @Test fun responseNamingAnotherUserRetiresOldMembershipBeforeFetchingAnyTeams()=runBlocking<Unit> {
        MockWebServer().use { server ->
            val identity=AtomicReference(NativeAuthIdentity(0,"a"))
            NativeAccountTeams({"token"},{"login"},server.url("/api/v1/"),sessionIdentity=identity::get).use { controller ->
                server.enqueue(teams());val old=checkNotNull(controller.refreshAfterSignIn("login",a).scope)
                val proof=checkNotNull(controller.captureContinuation(old));server.enqueue(user("b"))
                assertTrue(runCatching {controller.refresh()}.isFailure);assertEquals(2,server.requestCount)
                assertFalse(controller.isCurrent(old));assertFalse(controller.allowsContinuation(proof));assertNull(controller.state.value.scope)
            }
        }
    }
    @Test fun aColdPrivateControllerRestoresTheUserThenRequestsMembershipWithoutASecondUserFetch()=runBlocking<Unit> {
        MockWebServer().use { server ->
            val identity=AtomicReference<NativeAuthIdentity?>(null);var restores=0
            NativeAccountTeams({"token"},{"login"},server.url("/api/v1/"),sessionIdentity=identity::get,
                restoreUser={restores++;identity.set(NativeAuthIdentity(1,"a"));a}).use {controller->
                server.enqueue(teams());assertNotNull(controller.refresh().scope);assertEquals(1,restores)
                assertEquals(1,server.requestCount);assertEquals("/api/v1/teams?user_id=me",server.takeRequest().path)
            }
        }
    }
    @Test fun lateMembershipResponseCannotPublishOrOverwriteTheNewPrincipalsDisplayCache()=runBlocking<Unit> {
        MockWebServer().use { server ->
            val identity=AtomicReference(NativeAuthIdentity(0,"a"))
            var root=JSONObject().put("task_session","login").put("refresh_token","refresh")
            val environment="${server.url("/api/v1/")}#${NativeAccount.PROJECT_ID}"
            val stateLock=Any()
            val cache=NativeAccountProfileCache({synchronized(stateLock){root}},{change->synchronized(stateLock){root=JSONObject(root.toString()).also(change)}})
            NativeAccountTeams({"token"},{"login"},server.url("/api/v1/"),cache=cache,lock=stateLock,sessionIdentity=identity::get).use { controller ->
                server.enqueue(teams().setBodyDelay(200,TimeUnit.MILLISECONDS))
                val old=async(Dispatchers.IO) { runCatching {controller.refreshAfterSignIn("login",a)} }
                assertNotNull(withContext(Dispatchers.IO) {server.takeRequest(2,TimeUnit.SECONDS)})
                synchronized(stateLock) { NativeAccountProfileCache.writeVerifiedUser(root,"login",environment,a.copy(id="b",name="B"));identity.set(NativeAuthIdentity(1,"b")) }
                assertTrue(old.await().isFailure);assertNull(controller.state.value.scope)
                assertEquals("b",cache.read("login",environment)?.userId)
                controller.reconcileLogin();assertEquals("b",controller.state.value.userId);assertNull(controller.state.value.scope)
            }
        }
    }
}
