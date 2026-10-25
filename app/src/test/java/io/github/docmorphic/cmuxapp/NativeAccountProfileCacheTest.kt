package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentLinkedQueue

class NativeAccountProfileCacheTest {
    @Test fun persistedSelectionNormalizesLikeTheCompleteOriginalSelectionStore() {
        val rows=JSONObject(checkNotNull(javaClass.getResourceAsStream("/native-team-refresh-reference.json"))
            .bufferedReader().use{it.readText()}).getJSONArray("persistence")
        assertEquals(9,rows.length())
        for(i in 0 until rows.length()) {val row=rows.getJSONObject(i);val store=Store()
            val input=row.opt("input").takeUnless{it===JSONObject.NULL} as? String
            val expected=row.opt("restored").takeUnless{it===JSONObject.NULL} as? String
            store.cache.save("login","fixture-env",NativeAccountTeamsState("user",emptyList(),input))
            assertEquals(expected,store.cache.read("login","fixture-env")!!.selectedTeamId)
            store.cache.updateSelection("login","fixture-env","user",input)
            assertEquals(expected,store.cache.read("login","fixture-env")!!.selectedTeamId)
        }
    }
    @Test fun sameOwnerUserPublicationPreservesTheRememberedSelectionWhileClearingSavedMembership() {
        val store=Store();val user=NativeSignInUser("user","Updated",null,null)
        store.cache.save("login","fixture-env",NativeAccountTeamsState("user",listOf(NativeTeam("one","One")),"remembered"))
        store.update{NativeAccountProfileCache.writeVerifiedUser(it,"login","fixture-env",user)}
        val saved=checkNotNull(store.cache.read("login","fixture-env"))
        assertEquals("remembered",saved.selectedTeamId);assertTrue(saved.teams.isEmpty());assertNull(saved.scope);assertTrue(saved.cached)
        store.update{NativeAccountProfileCache.writeVerifiedUser(it,"login","fixture-env",user.copy(id="replacement"))}
        val replacement=store.cache.read("login","fixture-env")!!
        assertEquals("remembered",replacement.selectedTeamId);assertEquals("replacement",replacement.userId)
        assertTrue(replacement.teams.isEmpty());assertNull(replacement.scope)
    }
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
        teamUsers(2){MockResponse().setBody(JSONObject().put("id", userId).put("display_name", name)
            .put("primary_email", "person@example.test").put("selected_team", selected?.let { JSONObject().put("id", it) } ?: JSONObject.NULL).toString())}
        enqueue(MockResponse().setBody(JSONObject().put("items", JSONArray(ids.map {
            JSONObject().put("id", it).put("display_name", "Team $it")
        })).toString()))
    }
    private suspend fun seed(server: MockWebServer, store: Store): NativeTeamScope {
        server.profile()
        return controller(server, store).use { checkNotNull(it.refresh().scope) }
    }

    @Test fun restartRestoresDisplayWithoutConnectionOrCachedSelectionAuthority() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val store = Store(); val previous = seed(server, store)
            controller(server, store).use { restored ->
                assertEquals(" Fixture Person ", restored.state.value.displayName)
                assertEquals("person@example.test", restored.state.value.email)
                assertEquals("two", restored.state.value.selectedTeamId)
                assertTrue(restored.state.value.cached); assertNull(restored.state.value.scope)
                assertFalse(restored.isCurrent(previous)); assertEquals(3, server.requestCount)
                assertNull(restored.captureContinuation(previous))
                assertTrue(runCatching { restored.select("one") }.isFailure)
                assertEquals(3, server.requestCount)
                assertTrue(restored.state.value.cached)
                restored.reconcileLogin() // Initial app-runtime observation must keep the cached card.
                assertEquals("two", restored.state.value.selectedTeamId)
            }
        }
    }

    @Test fun offlineFailureKeepsCachedDetailsAndVerifiedRefreshReconcilesMembershipAndSelection() = runBlocking<Unit> {
        MockWebServer().use { server ->
            data class Profile(val selected: String?, val ids: List<String>, val name: String, val offline: Boolean = false)
            val response = AtomicReference(Profile("two", listOf("one", "two"), " Fixture Person "))
            val requests = ConcurrentLinkedQueue<String>()
            // Either failed user read may cancel its sibling before dispatch.
            // Route by path/current phase instead of leaving a counted 503 in
            // the user queue for the following verified refresh to consume.
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.method + " " + request.path
                    val current = response.get()
                    return when (request.method + " " + request.path) {
                        "GET /api/v1/users/me" -> if (current.offline) MockResponse().setResponseCode(503) else MockResponse().setBody(
                            JSONObject().put("id", "user").put("display_name", current.name).put("primary_email", "person@example.test")
                                .put("selected_team", current.selected?.let { JSONObject().put("id", it) } ?: JSONObject.NULL).toString())
                        "GET /api/v1/teams?user_id=me" -> MockResponse().setBody(JSONObject().put("items", JSONArray(current.ids.map {
                            JSONObject().put("id", it).put("display_name", "Team $it")
                        })).toString())
                        else -> MockResponse().setResponseCode(599)
                    }
                }
            }
            val store = Store()
            controller(server, store).use { assertNotNull(it.refresh().scope) }
            controller(server, store).use { restored ->
                response.set(response.get().copy(offline = true))
                assertTrue(runCatching { restored.refresh() }.isFailure)
                assertTrue(restored.state.value.cached); assertNull(restored.state.value.scope)
                assertEquals(" Fixture Person ", restored.state.value.displayName)
                response.set(Profile(null, listOf("one", "two"), "Updated Person"))
                val fresh = restored.refresh()
                assertEquals("two", fresh.selectedTeamId) // Same-user persisted choice, still an actual membership.
                assertEquals("Updated Person", fresh.displayName); assertFalse(fresh.cached)
                assertTrue(restored.isCurrent(checkNotNull(fresh.scope)))
                response.set(response.get().copy(selected = "two", ids = listOf("one")))
                assertEquals("one", restored.refresh().selectedTeamId)
                response.set(response.get().copy(selected = null, ids = emptyList()))
                assertNull(restored.refresh().scope); assertTrue(restored.state.value.teams.isEmpty())
            }
            controller(server, store).use { assertTrue(it.state.value.cached); assertEquals("one",it.state.value.selectedTeamId) }
            assertEquals(4, requests.count { it == "GET /api/v1/teams?user_id=me" })
            assertTrue(requests.count { it == "GET /api/v1/users/me" } in 9..10)
            assertEquals(requests.size, requests.count { it == "GET /api/v1/users/me" || it == "GET /api/v1/teams?user_id=me" })
        }
    }

    @Test fun selectedTeamPersistsOnlyAfterConfirmedPatchAndKeepsAccountCard() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val store = Store(); server.profile()
            controller(server, store).use { account ->
                account.refresh()
                server.teamUsers{MockResponse().setBody("{\"id\":\"user\"}")}
                server.enqueue(MockResponse().setResponseCode(503))
                assertTrue(runCatching { account.select("one") }.isFailure)
                assertEquals("two", store.cache.read("login", "${server.url("/api/v1/")}#${NativeAccount.PROJECT_ID}")!!.selectedTeamId)
                server.teamSelection{MockResponse().setBody("{\"id\":\"user\",\"selected_team\":{\"id\":\"one\"}}")}
                assertEquals(" Fixture Person ", account.select("one").displayName)
            }
            controller(server, store).use { assertEquals("one", it.state.value.selectedTeamId); assertNull(it.state.value.scope) }
        }
    }

    @Test fun failedUserReadKeepsDisplayCacheAndLateOldResponseCannotClearNewLogin() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val store = Store(); seed(server, store)
            controller(server, store).use { account ->
                server.teamUsers(2){MockResponse().setResponseCode(403)}
                assertTrue(runCatching { account.refresh() }.isFailure)
                assertEquals("user",account.state.value.userId);assertTrue(account.state.value.cached)
                assertNull(account.state.value.scope);assertTrue(store.load().has(NativeAccountProfileCache.KEY))
            }
            seed(server, store)
            controller(server, store).use { account ->
                repeat(server.requestCount) { server.takeRequest() }
                server.teamUsers(2){MockResponse().setResponseCode(403).setBody("{}").setBodyDelay(250, TimeUnit.MILLISECONDS)}
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
                server.teamUsers(2){MockResponse().setBody("{\"id\":\"different-user\"}")}
                server.enqueue(MockResponse().setResponseCode(503))
                assertTrue(runCatching { account.refresh() }.isFailure)
                assertFalse(account.isCurrent(previous)); assertNull(account.state.value.scope)
                assertNull(account.state.value.userId)
                assertFalse(store.load().has(NativeAccountProfileCache.KEY))
                assertTrue(runCatching { account.select("one") }.isFailure)
                assertEquals(6, server.requestCount)
            }
        }
    }

    @Test fun nonstringProfileFieldDefaultsToNilOnlyAfterFreshMembership() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val store=Store();seed(server,store)
            controller(server,store).use { account ->
                assertTrue(account.state.value.cached);assertNull(account.state.value.scope)
                server.teamUsers(2){MockResponse().setBody("{\"id\":\"user\",\"display_name\":42}")}
                server.enqueue(MockResponse().setBody("{\"items\":[{\"id\":\"one\",\"display_name\":\"One\"}]}"))
                val refreshed=account.refresh();assertNull(refreshed.displayName);assertFalse(refreshed.cached)
                assertEquals("one",refreshed.scope?.teamId);assertTrue(account.isCurrent(refreshed.scope!!))
                assertEquals(6,server.requestCount)
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
                { it.put("version", 2) },
                { it.put("name", false) },
                { it.getJSONArray("teams").put(false) })) {
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
    @Test fun explicitSelectionPersistsSeparatelyFromReadonlyCachedMembership()=runBlocking<Unit> {
        MockWebServer().use{server->val store=Store();seed(server,store)
            val environment="${server.url("/api/v1/")}#${NativeAccount.PROJECT_ID}"
            for(selected in listOf(null,"not-a-member","one")) {
                store.cache.updateSelection("login",environment,"user",selected)
                val card=checkNotNull(store.cache.read("login",environment));assertEquals(selected,card.selectedTeamId)
                assertTrue(card.cached);assertNull(card.scope);assertEquals(listOf("one","two"),card.teams.map{it.id})
                assertEquals("fixture-refresh",store.load().getString("refresh_token"))
            }
            store.cache.save("login",environment,NativeAccountTeamsState("user",emptyList(),"remembered"))
            assertEquals("remembered",store.cache.read("login",environment)!!.selectedTeamId)
            assertNull(store.cache.read("login",environment)!!.scope)
        }
    }
    @Test fun cachedSelectionUpdatesCannotCrossLoginEnvironmentOrUser()=runBlocking<Unit> {
        MockWebServer().use{server->val store=Store();seed(server,store)
            val environment="${server.url("/api/v1/")}#${NativeAccount.PROJECT_ID}";val before=store.load().toString()
            for((owner,env,user) in listOf(Triple("other",environment,"user"),Triple("login","other","user"),Triple("login",environment,"other"))) {
                store.cache.updateSelection(owner,env,user,null);assertEquals(before,store.load().toString())
            }
            store.update{it.put("task_session","replacement")};val replacement=store.load().toString()
            store.cache.updateSelection("login",environment,"user",null);assertEquals(replacement,store.load().toString())
            assertFalse(store.load().has(NativeAccountProfileCache.KEY))
        }
    }
}
