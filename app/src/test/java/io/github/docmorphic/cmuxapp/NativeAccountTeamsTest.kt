package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NativeAccountTeamsTest {
    private fun user(selected: String? = "one", id: String = "user") = JSONObject().put("id", id)
        .put("selected_team", selected?.let { JSONObject().put("id", it) } ?: JSONObject.NULL)
    private fun teams(vararg ids: String) = JSONObject().put("items", JSONArray(ids.map {
        JSONObject().put("id", it).put("display_name", "Team $it")
    }))
    private fun MockWebServer.reply(value: JSONObject) = enqueue(MockResponse().setBody(value.toString()))
    private fun MockWebServer.profile(selected: String? = "one", vararg ids: String = arrayOf("one", "two")) {
        reply(user(selected)); reply(teams(*ids))
    }
    private fun controller(server: MockWebServer, owner: AtomicReference<String?> = AtomicReference("login"),
                           token: suspend (Boolean) -> String? = { "fixture-token" }) =
        NativeAccountTeams(token, { owner.get() }, server.url("/api/v1/"))

    private fun creator(server: MockWebServer, owner: AtomicReference<String?> = AtomicReference("login")) =
        NativeAccountTeams({ "fixture-access" }, { owner.get() }, server.url("/api/v1/"),
            refreshCredential = { "fixture-refresh" }, backendOrigin = server.url("/"))
    private fun created(id: String = "new") = JSONObject().put("team", JSONObject().put("id", id).put("name", "New team"))

    @Test fun createsThroughCmuxThenVerifiesSelectionEvenWithLaggingMembership() = runBlocking<Unit> {
        MockWebServer().use { server -> creator(server).use { account ->
            server.profile(); val original = checkNotNull(account.refresh().scope)
            server.takeRequest(); server.takeRequest()
            server.reply(created()); server.reply(teams("one", "two")); server.reply(user("new"))
            val result = account.create("  New team  ")
            val post = server.takeRequest()
            assertEquals("POST", post.method); assertEquals("/api/subrouter/teams", post.path)
            assertEquals("Bearer fixture-access", post.getHeader("Authorization"))
            assertEquals("fixture-refresh", post.getHeader("X-Stack-Refresh-Token"))
            assertEquals("New team", JSONObject(post.body.readUtf8()).getString("displayName"))
            assertEquals("/api/v1/teams?user_id=me", server.takeRequest().path)
            assertEquals("PATCH", server.takeRequest().method)
            assertEquals("new", result.selectedTeamId)
            assertEquals(listOf("one", "two", "new"), result.teams.map { it.id })
            assertEquals("new", result.createdTeam?.id)
            assertFalse(account.isCurrent(original))
        } }
    }

    @Test fun failedSelectionKeepsCreatedTeamAvailableWithoutAnotherPost() = runBlocking<Unit> {
        MockWebServer().use { server -> creator(server).use { account ->
            server.profile(); val original = checkNotNull(account.refresh().scope)
            server.reply(created()); server.reply(teams("one", "new")); server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(runCatching { account.create("New team") }.isFailure)
            assertTrue(account.isCurrent(original)); assertEquals("new", account.state.value.createdTeam?.id)
            assertTrue(account.state.value.error!!.startsWith("Team created"))
            server.reply(user("new")); assertEquals("new", account.select("new").selectedTeamId)
            val methods = (1..server.requestCount).map { server.takeRequest().method }
            assertEquals(1, methods.count { it == "POST" })
        } }
    }

    @Test fun firstTeamRemainsSelectableAfterMembershipRefreshFailure() = runBlocking<Unit> {
        MockWebServer().use { server -> creator(server).use { account ->
            server.profile(null, *emptyArray()); account.refresh()
            server.reply(created()); server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(runCatching { account.create("New team") }.isFailure)
            assertNull(account.state.value.scope)
            server.reply(user("new")); assertEquals("new", account.select("new").selectedTeamId)
        } }
    }

    @Test fun invalidNamesAndUnverifiedSessionsNeverCreateTeams() = runBlocking<Unit> {
        MockWebServer().use { server -> creator(server).use { account ->
            for (name in listOf("", " \n", "x".repeat(121), "Requires verified account"))
                assertTrue(runCatching { account.create(name) }.isFailure)
            assertEquals(0, server.requestCount)
        } }
    }

    @Test fun lostOrRejectedCreateResponseIsNeverRetried() = runBlocking<Unit> {
        for (response in listOf(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
            MockResponse().setResponseCode(401), MockResponse().setResponseCode(503),
            MockResponse().setResponseCode(307).setHeader("Location", "/elsewhere"))) {
            MockWebServer().use { server -> creator(server).use { account ->
                server.profile(); account.refresh(); server.enqueue(response)
                assertTrue(runCatching { account.create("New team") }.isFailure)
                assertEquals(3, server.requestCount)
                assertNull(account.state.value.createdTeam)
                assertTrue(account.state.value.error!!.contains("not confirmed"))
            } }
        }
    }

    @Test fun changedLoginCannotPublishCreatedTeamOrContinueSelection() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val owner = AtomicReference<String?>("login")
            creator(server, owner).use { account ->
                server.profile(); account.refresh(); server.takeRequest(); server.takeRequest()
                server.enqueue(MockResponse().setBody(created().toString()).setBodyDelay(250, TimeUnit.MILLISECONDS))
                val pending = async { runCatching { account.create("New team") } }
                withContext(Dispatchers.IO) { assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS)?.method) }
                owner.set("different-login")
                assertTrue(pending.await().exceptionOrNull() is CancellationException)
                assertEquals(3, server.requestCount); assertNull(account.state.value.createdTeam)
                assertNull(account.state.value.scope)
            }
        }
    }

    @Test fun loadsVerifiedMembershipAndKeepsScopeAcrossTokenRefresh() = runBlocking<Unit> {
        MockWebServer().use { server ->
            controller(server).use { account ->
                server.profile("two")
                val loaded = account.refresh()
                assertEquals("two", loaded.selectedTeamId)
                val captured = checkNotNull(loaded.scope)
                assertEquals("user", captured.userId)
                assertTrue(account.isCurrent(captured))
                assertEquals("/api/v1/users/me", server.takeRequest().path)
                val teamRequest = server.takeRequest()
                assertEquals("/api/v1/teams?user_id=me", teamRequest.path)
                assertEquals(NativeAccount.PROJECT_ID, teamRequest.getHeader("x-stack-project-id"))
                assertEquals("fixture-token", teamRequest.getHeader("x-stack-access-token"))
                server.profile("two")
                assertEquals(captured, account.refresh().scope)
            }
        }
    }

    @Test fun unavailableSelectionFallsBackOnlyToActualMembershipAndEmptyTeamsRemoveScope() = runBlocking<Unit> {
        MockWebServer().use { server ->
            controller(server).use { account ->
                server.profile("not-a-member")
                val before = account.refresh()
                assertEquals("one", before.selectedTeamId)
                server.profile(null, *emptyArray())
                val removed = account.refresh()
                assertNull(removed.scope)
                assertNull(removed.selectedTeamId)
                assertFalse(account.isCurrent(checkNotNull(before.scope)))
            }
        }
    }

    @Test fun selectionPersistsBeforeSwitchingAndReturningToSameTeamDoesNotReviveOldScope() = runBlocking<Unit> {
        MockWebServer().use { server ->
            controller(server).use { account ->
                server.profile()
                val first = checkNotNull(account.refresh().scope)
                server.takeRequest(); server.takeRequest()
                server.reply(user("two"))
                val second = account.select("two")
                val patch = server.takeRequest()
                assertEquals("PATCH", patch.method)
                assertEquals("two", JSONObject(patch.body.readUtf8()).getString("selected_team_id"))
                assertEquals("two", second.scope?.teamId)
                assertFalse(account.isCurrent(first))
                server.reply(user("one"))
                val returned = checkNotNull(account.select("one").scope)
                assertEquals(first.teamId, returned.teamId)
                assertNotEquals(first.generation, returned.generation)
                assertFalse(account.isCurrent(first))
            }
        }
    }

    @Test fun rejectedOrUnconfirmedSelectionKeepsPreviousTeamAndUnknownTeamIsNotSent() = runBlocking<Unit> {
        MockWebServer().use { server ->
            controller(server).use { account ->
                server.profile()
                val original = checkNotNull(account.refresh().scope)
                assertTrue(runCatching { account.select("stranger") }.isFailure)
                assertEquals(2, server.requestCount)
                server.enqueue(MockResponse().setResponseCode(403))
                assertTrue(runCatching { account.select("two") }.isFailure)
                assertTrue(account.isCurrent(original))
                server.reply(user("one"))
                assertTrue(runCatching { account.select("two") }.isFailure)
                assertTrue(account.isCurrent(original))
                assertFalse(account.state.value.loading)
            }
        }
    }

    @Test fun actualStatus401RefreshesOnceAndOtherResponsesAreNotRetried() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val forced = mutableListOf<Boolean>()
            controller(server, token = { force -> forced += force; if (force) "new-token" else "fixture-token" }).use { account ->
                server.enqueue(MockResponse().setHeader("x-stack-actual-status", "401").setBody("{}"))
                server.profile()
                account.refresh()
                assertEquals(listOf(false, true, false), forced)
                server.takeRequest()
                assertEquals("new-token", server.takeRequest().getHeader("x-stack-access-token"))
                server.takeRequest()
                server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/elsewhere")))
                assertTrue(runCatching { account.refresh() }.isFailure)
                assertEquals(4, server.requestCount)
            }
        }
    }

    @Test fun lostMembershipAccessClearsPreviouslyResolvedScope() = runBlocking<Unit> {
        MockWebServer().use { server ->
            controller(server).use { account ->
                server.profile()
                val original = checkNotNull(account.refresh().scope)
                server.reply(user())
                server.enqueue(MockResponse().setResponseCode(403))
                assertTrue(runCatching { account.refresh() }.isFailure)
                assertNull(account.state.value.scope)
                assertFalse(account.isCurrent(original))
            }
        }
    }

    @Test fun accountChangeDuringTokenFetchCannotSendOldCredentials() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val owner = AtomicReference<String?>("login")
            val requested = CompletableDeferred<Unit>()
            val token = CompletableDeferred<String>()
            controller(server, owner) { requested.complete(Unit); token.await() }.use { account ->
                val pending = async { runCatching { account.refresh() } }
                requested.await()
                owner.set("replacement-login")
                token.complete("old-token")
                assertTrue(withTimeout(2000) { pending.await() }.isFailure)
                assertEquals(0, server.requestCount)
                assertNull(account.state.value.scope)
            }
        }
    }

    @Test fun clearCancelsNetworkAndLateAccountReplyCannotRestoreScope() = runBlocking<Unit> {
        MockWebServer().use { server ->
            controller(server).use { account ->
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val pending = async { runCatching { account.refresh() } }
                withContext(Dispatchers.IO) { checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
                account.clear()
                assertTrue(withTimeout(2000) { pending.await() }.isFailure)
                assertEquals(NativeAccountTeamsState(), account.state.value)
            }
        }
    }

    @Test fun interruptedSelectionIsNotReplayedAndKeepsOldScope() = runBlocking<Unit> {
        MockWebServer().use { server ->
            controller(server).use { account ->
                server.profile()
                val original = checkNotNull(account.refresh().scope)
                server.takeRequest(); server.takeRequest()
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val pending = async { withTimeoutOrNull(250) { account.select("two") } }
                withContext(Dispatchers.IO) { checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
                assertNull(withTimeout(2000) { pending.await() })
                assertEquals(3, server.requestCount)
                assertTrue(account.isCurrent(original))
                assertFalse(account.state.value.loading)
            }
        }
    }

    @Test fun malformedMembershipCannotReplaceAnEstablishedScope() = runBlocking<Unit> {
        MockWebServer().use { server ->
            controller(server).use { account ->
                server.profile()
                val original = checkNotNull(account.refresh().scope)
                server.reply(user("two")); server.reply(teams("two", "two"))
                assertTrue(runCatching { account.refresh() }.isFailure)
                assertTrue(account.isCurrent(original))
            }
        }
    }
}
