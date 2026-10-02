package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class NativeAccountProfileCacheTest {
    private class Store {
        var value = JSONObject().put("task_session", "login").put("refresh_token", "fixture-refresh")
        var fail = false
        val cache = NativeAccountProfileCache(::load, ::update)
        @Synchronized fun load() = JSONObject(value.toString())
        @Synchronized fun update(block: (JSONObject) -> Unit) {
            if (fail) error("Fixture disk full")
            val next = load(); block(next); NativeAccountProfileCache.prune(next); value = next
        }
        fun login(): String? = load().optString("task_session").takeIf { it.isNotBlank() }
    }
    private fun controller(server: MockWebServer, store: Store) = NativeAccountTeams({ "fixture-access" }, store::login,
        server.url("/api/v1/"), cache = store.cache)
    private fun MockWebServer.profile(selected: String? = "two", ids: List<String> = listOf("one", "two"),
                                      name: Any = " Fixture Person ", userId: String = "user") {
        enqueue(MockResponse().setBody(JSONObject().put("id", userId).put("display_name", name)
            .put("primary_email", "person@example.test").put("selected_team", selected?.let { JSONObject().put("id", it) } ?: JSONObject.NULL).toString()))
        enqueue(MockResponse().setBody(JSONObject().put("items", JSONArray(ids.map {
            JSONObject().put("id", it).put("display_name", "Team $it")
        })).toString()))
    }
    private suspend fun seed(server: MockWebServer, store: Store): NativeTeamScope {
        server.profile()
        return controller(server, store).use { checkNotNull(it.refresh().scope) }
    }

    @Test fun restartRestoresDisplayButCannotAuthorizeOrMutateBeforeMembershipRefresh() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val store = Store(); val previous = seed(server, store)
            controller(server, store).use { restored ->
                assertEquals("Fixture Person", restored.state.value.displayName)
                assertEquals("person@example.test", restored.state.value.email)
                assertEquals("two", restored.state.value.selectedTeamId)
                assertTrue(restored.state.value.cached); assertNull(restored.state.value.scope)
                assertFalse(restored.isCurrent(previous)); assertEquals(2, server.requestCount)
                assertTrue(runCatching { restored.select("one") }.isFailure)
                assertTrue(runCatching { restored.create("Never sent") }.isFailure)
                assertEquals(2, server.requestCount)
                assertTrue(restored.state.value.cached)
                restored.reconcileLogin() // Initial app-runtime observation must keep the cached card.
                assertEquals("two", restored.state.value.selectedTeamId)
            }
        }
    }

    @Test fun offlineFailureKeepsCachedDetailsAndVerifiedRefreshReconcilesMembershipAndSelection() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val store = Store(); seed(server, store)
            controller(server, store).use { restored ->
                server.enqueue(MockResponse().setResponseCode(503))
                assertTrue(runCatching { restored.refresh() }.isFailure)
                assertTrue(restored.state.value.cached); assertNull(restored.state.value.scope)
                assertEquals("Fixture Person", restored.state.value.displayName)
                server.profile(selected = null, name = "Updated Person")
                val fresh = restored.refresh()
                assertEquals("two", fresh.selectedTeamId) // Same-user persisted choice, still an actual membership.
                assertEquals("Updated Person", fresh.displayName); assertFalse(fresh.cached)
                assertTrue(restored.isCurrent(checkNotNull(fresh.scope)))
                server.profile(selected = "two", ids = listOf("one"))
                assertEquals("one", restored.refresh().selectedTeamId)
                server.profile(selected = null, ids = emptyList())
                assertNull(restored.refresh().scope); assertTrue(restored.state.value.teams.isEmpty())
            }
            controller(server, store).use { assertTrue(it.state.value.cached); assertNull(it.state.value.selectedTeamId) }
        }
    }

    @Test fun selectedTeamPersistsOnlyAfterConfirmedPatchAndKeepsAccountCard() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val store = Store(); server.profile()
            controller(server, store).use { account ->
                account.refresh()
                server.enqueue(MockResponse().setResponseCode(503))
                assertTrue(runCatching { account.select("one") }.isFailure)
                assertEquals("two", store.cache.read("login", "${server.url("/api/v1/")}#${NativeAccount.PROJECT_ID}")!!.selectedTeamId)
                server.enqueue(MockResponse().setBody("{\"id\":\"user\",\"selected_team\":{\"id\":\"one\"}}"))
                assertEquals("Fixture Person", account.select("one").displayName)
            }
            controller(server, store).use { assertEquals("one", it.state.value.selectedTeamId); assertNull(it.state.value.scope) }
        }
    }

    @Test fun definitiveRejectionEvictsCacheButLateOldResponseCannotClearNewLogin() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val store = Store(); seed(server, store)
            controller(server, store).use { account ->
                server.enqueue(MockResponse().setResponseCode(403))
                assertTrue(runCatching { account.refresh() }.isFailure)
                assertNull(account.state.value.userId); assertFalse(store.load().has(NativeAccountProfileCache.KEY))
            }
            seed(server, store)
            controller(server, store).use { account ->
                repeat(server.requestCount) { server.takeRequest() }
                server.enqueue(MockResponse().setResponseCode(403).setBody("{}").setBodyDelay(250, TimeUnit.MILLISECONDS))
                val old = async { runCatching { account.refresh() } }
                withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
                store.update { it.put("task_session", "new-login") }
                store.cache.save("new-login", "new-environment", NativeAccountTeamsState(userId = "new-user"))
                account.reconcileLogin()
                assertTrue(old.await().isFailure)
                assertEquals("new-user", store.cache.read("new-login", "new-environment")!!.userId)
                assertNull(account.state.value.scope); assertNull(account.state.value.userId)
            }
        }
    }

    @Test fun changedServerIdentityRetiresOldScopeEvenWhenNewMembershipFails() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val store = Store(); server.profile()
            controller(server, store).use { account ->
                val previous = account.refresh().scope!!
                server.enqueue(MockResponse().setBody("{\"id\":\"different-user\"}"))
                server.enqueue(MockResponse().setResponseCode(503))
                assertTrue(runCatching { account.refresh() }.isFailure)
                assertFalse(account.isCurrent(previous)); assertNull(account.state.value.scope)
                assertNull(account.state.value.userId)
                assertFalse(store.load().has(NativeAccountProfileCache.KEY))
                assertTrue(runCatching { account.select("one") }.isFailure)
                assertEquals(4, server.requestCount)
            }
        }
    }

    @Test fun invalidProfileCannotPromoteCachedMembershipToLiveAuthority() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val store = Store(); seed(server, store)
            controller(server, store).use { account ->
                server.enqueue(MockResponse().setBody("{\"id\":\"user\",\"display_name\":42}"))
                assertTrue(runCatching { account.refresh() }.isFailure)
                assertTrue(account.state.value.cached); assertNull(account.state.value.scope)
                assertTrue(runCatching { account.select("one") }.isFailure)
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun cacheIsBoundToLoginEnvironmentAndSchemaAndNeverImportsScopeFields() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val store = Store(); seed(server, store)
            val environment = "${server.url("/api/v1/")}#${NativeAccount.PROJECT_ID}"
            assertNull(store.cache.read("other-login", environment)); assertNull(store.cache.read("login", "other-server"))
            store.update { it.getJSONObject(NativeAccountProfileCache.KEY).put("scope", JSONObject().put("generation", 42)).put("cached", false) }
            assertNull(store.cache.read("login", environment)!!.scope); assertTrue(store.cache.read("login", environment)!!.cached)
            val original = store.load()
            for (change in listOf<(JSONObject) -> Unit>(
                { it.put("version", 2) }, { it.put("selected", "not-a-member") },
                { it.put("name", "x".repeat(513)) },
                { it.getJSONArray("teams").put(it.getJSONArray("teams").getJSONObject(0)) })) {
                store.value = JSONObject(original.toString())
                store.update { change(it.getJSONObject(NativeAccountProfileCache.KEY)) }
                assertNull(store.cache.read("login", environment))
                assertEquals("fixture-refresh", store.load().getString("refresh_token"))
            }
            store.value = original; store.update { it.put("refresh_token", "").remove("task_session") }
            assertFalse(store.load().has(NativeAccountProfileCache.KEY))
        }
    }

    @Test fun diskFailureDoesNotUndoVerifiedMembershipAndCannotSaveForReplacementLogin() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val store = Store(); store.fail = true
            controller(server, store).use { account ->
                server.profile(); val result = account.refresh()
                assertFalse(result.cached); assertNotNull(result.scope)
                assertTrue(account.isCurrent(result.scope!!)); assertTrue(result.error!!.contains("offline use"))
            }
            store.fail = false; store.update { it.put("task_session", "other-login") }
            store.cache.save("login", "server", NativeAccountTeamsState(userId = "old-user"))
            assertFalse(store.load().has(NativeAccountProfileCache.KEY))
        }
    }
}
