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

class NativeCachedComputersRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = NativeCredentialStore(context, "cached-computers-fixture")
    private val team = NativeTeamScope("cached-login", "cached-user", "cached-team", 1)
    private val identity = NativeMacIdentity("cached-mac", "default")
    private val row = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
        "cmux-ios://attach?v=2&r=100.64.0.8:58465", identity.deviceId, "Studio", identity.buildTag), team)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var selections = 0
    private var visibilityChanges = 0
    private var management by mutableStateOf(false)
    @Before fun setup() {
        store.clear()
        store.update { it.put("task_session", team.login).put("refresh_token", "fixture-refresh")
            .put("pairings", JSONArray(listOf(NativePairingRecords.encode(row)))) }
        NativeMacAppearanceStore.create(context, team).update(identity, { true }) { it.copy(name = "Offline Studio", icon = "💻") }
        store.update { NativeMacVersionHistory.record(it, team, mapOf(identity to "0.64.24")) { true } }
    }
    @After fun cleanup() {
        scope.cancel(); store.clear()
        NativeMacAppearanceStore.create(context, team).removeComputer(NativeComputerTarget("cached-mac", "default", "Studio")) { true }
    }
    private fun controller(server: MockWebServer) = NativeAccountTeams({ "fixture-access" }, { store.taskSession() },
        server.url("/api/v1/"), cache = NativeAccountProfileCache(store::load, store::update))
    private fun MockWebServer.profile() {
        enqueue(MockResponse().setBody("""{"id":"cached-user","selected_team":{"id":"cached-team"}}"""))
        enqueue(MockResponse().setBody("""{"items":[{"id":"cached-team","display_name":"Saved Team"}]}"""))
    }
    private fun content(teams: NativeAccountTeams, server: MockWebServer) = compose.setContent {
        CmuxTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding()) {
            val account by teams.state.collectAsState()
            val revision by store.revisions.collectAsState()
            val snapshot = remember(account, revision) { store.load() }
            val cached = NativeCachedComputers.project(snapshot, store.taskSession(), account,
                "${server.url("/api/v1/")}#${NativeAccount.PROJECT_ID}")
            val rows = cached?.macs ?: if (account.scope != null) listOf(row) else emptyList()
            val hidden = NativeComputerVisibility.hiddenOrigins(snapshot)
            val appearances = cached?.owner?.let { NativeMacAppearanceStore.display(context, it).collectAsState().value }
                ?: nativeMacAppearances(account.scope)
            CompositionLocalProvider(LocalMacCompatibilityWarnings provides
                (cached?.warnings(NativeMacCompatibilityPolicy.baked) ?: emptyMap())) {
                if (management) NativeComputersRoute(null, { management = false }, {}) {
                    if (cached != null) NativeCachedComputersNotice()
                    NativeManagedComputerRows(NativeComputerList.rows(rows, appearances, NativeMacPresenceState(), emptyMap(),
                        NativeMacConnectionPreferences(), emptyList(), emptyMap()), appearances, emptyMap(), emptyMap(),
                        NativeMacPresenceState(), onDetails = { selections++ }, onPair = {}, hiddenOrigins = hidden,
                        onVisibility = { _, _ -> visibilityChanges++ }, readOnly = cached != null)
                } else NativeComputerPicker(account, NativeComputersState(account.scope),
                    saved = rows.filterNot { NativeComputerVisibility.isHidden(hidden, it) },
                    hidden = rows.filter { NativeComputerVisibility.isHidden(hidden, it) },
                    onVisibility = { _, _ -> visibilityChanges++ }, cachedDisplay = cached != null, displayAppearances = appearances,
                    canSelectSaved = { account.scope?.let(teams::isCurrent) == true }, canSelectDiscovered = { false },
                    onSelectSaved = { selections++ }, onSelect = {}, onSettings = {}, onComputers = { management = true },
                    onRefresh = { scope.launch { runCatching { teams.refresh() } } }, onPairing = {}, onNewTask = {},
                    onUseHelper = {}, onLicenses = {}, onError = {})
            }
        } } }
    }
    @Test fun encryptedRestartShowsNamedSavedComputerAndWarningWithoutConnectingThenRevalidates() {
        MockWebServer().use { server ->
            server.profile(); controller(server).use { runBlocking { it.refresh() } }
            controller(server).use { restored ->
                content(restored, server)
                compose.onNodeWithTag("computers.cached").assertIsDisplayed()
                compose.onNodeWithText("Offline Studio").assertIsDisplayed().performClick()
                compose.onNodeWithTag("computer.visibility." + row.origin).assertIsNotEnabled().performClick()
                compose.onNodeWithText("Mac update required").assertIsDisplayed()
                assertNull(restored.state.value.scope); assertEquals(2, server.requestCount)
                assertEquals(0, selections); assertEquals(0, visibilityChanges)
                server.enqueue(MockResponse().setResponseCode(503))
                compose.onNodeWithText("Refresh computers").performScrollTo().performClick()
                compose.waitUntil(5000) { !restored.state.value.loading && restored.state.value.error != null }
                compose.onNodeWithText("Offline Studio").performScrollTo().assertIsDisplayed()
                capture("offline-computers")
                server.profile()
                compose.onNodeWithText("Refresh computers").performScrollTo().performClick()
                compose.waitUntil(5000) { restored.state.value.scope != null }
                compose.onNodeWithTag("computers.cached").assertDoesNotExist()
                compose.onNodeWithText("Offline Studio").performScrollTo().performClick()
                compose.runOnIdle { assertEquals(1, selections) }
            }
        }
    }
    @Test fun hiddenManagementRowsStayDisabledAndDefinitiveRejectionRemovesCachedComputers() {
        MockWebServer().use { server ->
            server.profile(); controller(server).use { runBlocking { it.refresh() } }
            store.update { NativeComputerVisibility.setVisible(it, team.login, row, false) { true } }
            controller(server).use { restored ->
                management = true; content(restored, server)
                compose.onNodeWithText("Hidden Computers").assertIsDisplayed()
                compose.onNodeWithText("Offline Studio").assertIsDisplayed()
                compose.onNodeWithTag("computer.visibility." + row.origin).assertIsNotEnabled().performClick()
                compose.runOnIdle { assertEquals(0, visibilityChanges); management = false }
                repeat(2) { server.enqueue(MockResponse().setResponseCode(401)) }
                compose.onNodeWithText("Refresh computers").performScrollTo().performClick()
                compose.waitUntil(5000) { restored.state.value.error != null && !restored.state.value.cached }
                compose.onNodeWithText("Offline Studio").assertDoesNotExist()
                compose.onNodeWithTag("computers.cached").assertDoesNotExist()
                assertNull(restored.state.value.scope)
                assertFalse(store.load()!!.has(NativeAccountProfileCache.KEY))
                assertEquals(listOf(row), store.pairedMacs())
            }
        }
    }
    private fun capture(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(context.getExternalFilesDir(null), "cached-computers").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
