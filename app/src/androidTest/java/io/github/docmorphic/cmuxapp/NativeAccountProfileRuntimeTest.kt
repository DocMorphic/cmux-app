package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NativeAccountProfileRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = NativeCredentialStore(context, "profile-cache-fixture")
    private val cache get() = NativeAccountProfileCache(store::load, store::update)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Before fun setup() { store.clear(); store.update { it.put("task_session", "fixture-login").put("refresh_token", "fixture-refresh") } }
    @After fun cleanup() { scope.cancel(); store.clear() }
    private fun controller(server: MockWebServer) = NativeAccountTeams({ "fixture-access" }, { store.taskSession() },
        server.url("/api/v1/"), cache = cache)
    private fun MockWebServer.profile(name: String = "Fixture Person", selected: String = "two") {
        enqueue(MockResponse().setBody(JSONObject().put("id", "fixture-user").put("display_name", name)
            .put("primary_email", "fixture@example.test").put("selected_team", JSONObject().put("id", selected)).toString()))
        enqueue(MockResponse().setBody(JSONObject().put("items", JSONArray(listOf("one", "two").map {
            JSONObject().put("id", it).put("display_name", "Team $it")
        })).toString()))
    }

    @Test fun encryptedColdRestoreDisplaysAccountWhileOfflineThenRevalidatesBeforeTeamActions() {
        MockWebServer().use { server ->
            server.profile()
            controller(server).use { runBlocking { it.refresh() } }
            val stored = context.getSharedPreferences("profile-cache-fixture", 0).getString("state", "")!!
            assertFalse(stored.contains("Fixture Person")); assertFalse(stored.contains("fixture@example.test"))
            controller(server).use { restored ->
                compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.statusBarsPadding()) {
                    val state by restored.state.collectAsState()
                    NativeAccountTeamSection(state, onRefresh = {
                        scope.launch { runCatching { restored.refresh() } }
                    }, onSelect = { id -> scope.launch { restored.select(id) } }, onCreate = { error("No team creation expected") })
                } } } }
                compose.onNodeWithText("Fixture Person").assertIsDisplayed()
                compose.onNodeWithText("fixture@example.test").assertIsDisplayed()
                compose.onNodeWithText("Team two").assertIsNotEnabled().performClick()
                compose.onNodeWithText("Create Team").assertIsNotEnabled().performClick()
                assertNull(restored.state.value.scope); assertEquals(2, server.requestCount)
                server.enqueue(MockResponse().setResponseCode(503))
                compose.onNodeWithText("Refresh account").performClick()
                compose.waitUntil(5000) { !restored.state.value.loading && restored.state.value.error != null }
                compose.onNodeWithText("Showing saved account details. Connect to refresh your teams.").assertIsDisplayed()
                capture("account-offline-cache")
                assertTrue(restored.state.value.cached); assertNull(restored.state.value.scope)
                server.profile("Updated Person", "one")
                compose.onNodeWithText("Refresh account").performClick()
                compose.waitUntil(5000) { !restored.state.value.cached }
                compose.onNodeWithText("Updated Person").assertIsDisplayed()
                compose.onNodeWithText("Team one").assertIsEnabled()
                compose.onNodeWithText("Create Team").assertIsEnabled()
                assertTrue(restored.isCurrent(restored.state.value.scope!!))
                server.enqueue(MockResponse().setBody("{\"id\":\"fixture-user\",\"selected_team\":{\"id\":\"two\"}}"))
                compose.onNodeWithText("Team one").performClick(); compose.onNodeWithText("Team two").performClick()
                compose.waitUntil(5000) { restored.state.value.selectedTeamId == "two" }
                compose.onNodeWithText("Updated Person").assertIsDisplayed()
                capture("account-verified-cache")
            }
            controller(server).use { next ->
                assertEquals("two", next.state.value.selectedTeamId)
                assertEquals("Updated Person", next.state.value.displayName)
                assertTrue(next.state.value.cached); assertNull(next.state.value.scope)
            }
        }
    }

    @Test fun signOutAndReplacementLoginRetireEncryptedProfileWithoutClearingOtherCredentials() {
        MockWebServer().use { server ->
            server.profile(); controller(server).use { runBlocking { it.refresh() } }
            controller(server).use { restored ->
                assertTrue(restored.state.value.cached)
                assertTrue(NativeAccount(store).signOut("fixture-login"))
                restored.reconcileLogin()
                assertNull(restored.state.value.userId)
                assertFalse(store.load()!!.has(NativeAccountProfileCache.KEY))
                store.update { it.put("task_session", "replacement").put("refresh_token", "replacement-refresh") }
                restored.reconcileLogin(); assertEquals(NativeAccountTeamsState(), restored.state.value)
                assertFalse(NativeAccount(store).signOut("fixture-login"))
                assertEquals("replacement-refresh", store.load()!!.getString("refresh_token"))
                assertEquals(2, server.requestCount)
            }
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        androidx.test.uiautomator.UiDevice.getInstance(instrumentation).waitForIdle(1000)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = java.io.File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        try { java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
