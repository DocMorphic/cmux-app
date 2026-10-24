package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class NativeSettingsTeamSwitchRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Wire : MobileRpcTransport {
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        val methods = CopyOnWriteArrayList<String>()
        override suspend fun connect() {}
        override suspend fun read() = input.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            val request = JSONObject(MobileFrameDecoder().feed(bytes).single().decodeToString())
            val method = request.getString("method"); methods += method
            val result = if (method == "mobile.host.status") JSONObject().put("mac_device_id", "device")
                .put("mac_instance_tag", "default").put("mac_app_version", "0.65.0").put("capabilities", JSONArray()) else JSONObject()
            input.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id"))
                .put("ok", true).put("result", result).toString().toByteArray()))
        }
        override fun close() { input.close() }
    }
    private fun MockWebServer.profile(selected: String, originalMember: Boolean = true) {
        teamUsers(2){MockResponse().setBody("""{"id":"user","selected_team":{"id":"$selected"}}""")}
        enqueue(MockResponse().setBody(JSONObject().put("items", JSONArray((if (originalMember) listOf("original", "picker") else listOf("picker"))
            .map { JSONObject().put("id", it).put("display_name", it) })).toString()))
    }

    @Test fun teamPickerKeepsOriginalRootAndSettingsOpensItsOwnerThroughRoundTrip() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preference = "settings-team-switch-" + UUID.randomUUID()
        val store = NativeCredentialStore(context, preference)
        val dials = AtomicInteger()
        val connector = NativeConnector { _, _ -> dials.incrementAndGet(); error("Must not dial another Mac") }
        try { MockWebServer().use { server ->
            store.update { it.put("task_session", "fixture-login").put("refresh_token", "fixture-refresh") }
            server.profile("original")
            NativeAccountTeams({ "fixture-access" }, store::taskSession, server.url("/api/v1/"), lock = store.accountStateLock).use { teams ->
                teams.refresh(); val original = checkNotNull(teams.state.value.scope)
                val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
                    "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user", "device", "Original Mac", "default"), original)
                val pickerScope = original.copy(teamId = "picker", generation = 2)
                val picker = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(mac.code, "device", "Picker Mac", "default"), pickerScope)
                store.update { it.put("pairings", JSONArray().put(NativePairingRecords.encode(mac)).put(NativePairingRecords.encode(picker))) }
                lateinit var feed: NativeFeedSession
                compose.runOnUiThread { feed = NativeFeedSession(connector, NativeAccount(store), store, teams) }
                val wire = Wire()
                MobileRpcClient(wire, { "fixture-root" }).use { root ->
                    root.connect()
                    compose.runOnUiThread {
                        val state = feed.connectionState(mac.code)
                        state.client = root; state.admittedMac = mac; state.admittedRetry = 0
                        state.connectionReady = true; state.connectedCode = mac.code
                        feed.admitForeground(original, mac, root)
                    }
                    NativeIrohRuntime(teams.state, teams::isCurrent, { "fixture-access" }, { _, _ ->
                        object : IrohAccountBackend {
                            override val state = MutableStateFlow(IrohV2ControlState(ready = true, permissionExpiresAt = 2000))
                            override val privatePaths = NativePrivatePathStore({ null }, {})
                            override suspend fun start() {}
                            override suspend fun refresh() {}
                            override fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport = error("Must not dial")
                            override fun close() {}
                        }
                    }, now = { 1000 }).use { runtime ->
                        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                            val team by teams.state.collectAsState()
                            val computer by runtime.state.collectAsState()
                            val revision by store.revisions.collectAsState()
                            val scope = rememberCoroutineScope()
                            val connection = feed.connectionState(mac.code)
                            val selected = if (team.scope?.teamId == "picker") listOf(picker) else listOf(mac)
                            val foreground = mac.takeIf { feed.hasAdmittedForeground() }
                            val inventory = listOfNotNull(foreground) + selected.filterNot { it == foreground }
                            val projection = nativeRecordComputerConnections(inventory, emptyMap(), emptyList(), MobileConnectionTransport.TAILSCALE,
                                isForeground = { row -> row == mac && feed.hasAdmittedForeground() }, isPending = { false })
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                NativeAccountTeamSection(team, {}, { id -> feed.selectTeam(teams, id);Unit },
                                    onCreate = { name -> feed.createTeam(teams, name); true })
                                NativeSettingsConnections(NativeComputerMenuOwner(store.taskSession(), team.scope),
                                    nativeSettingsConnectionRows(inventory, projection), NativeMacAppearances(), onDetails = { row ->
                                        feed.computerDetailsState.value = feed.foregroundDetailsPresentation(row, null)
                                    }, onComputers = {})
                            }
                            val page = feed.computerDetailsState.value
                            NativeSavedComputerDetailsHost(runtime, computer, page, projection.selectedValues(selected),
                                NativeComputerForgetCallbacks(), store, connector, feed.coordinator,
                                confirm = feed::confirmComputerForget, prepareDetached = feed::prepareComputerForget,
                                connectionFor = projection::get) { if (feed.computerDetailsState.value === page) feed.computerDetailsState.value = null }
                        } } }
                        server.teamSelection{MockResponse().setBody("""{"id":"user","selected_team":{"id":"picker"}}""")}
                        compose.onNodeWithTag("settings.team:picker").performClick()
                        compose.waitUntil(5000) { teams.state.value.scope?.teamId == "picker" }
                        assertSame(root, feed.connectionState(mac.code).client); assertTrue(feed.hasAdmittedForeground())
                        compose.onNodeWithText("Original Mac").performScrollTo().performClick()
                        compose.waitUntil(5000) { feed.computerDetailsState.value != null }
                        assertEquals(original, feed.computerDetailsState.value!!.team)
                        compose.onNodeWithText("Check Connection").performScrollTo().performClick()
                        compose.waitUntil(5000) { wire.methods.contains("mobile.workspace.list") }
                        compose.onNodeWithText("‹  Back").performClick()
                        server.teamSelection{MockResponse().setBody("""{"id":"user","selected_team":{"id":"original"}}""")}
                        compose.onNodeWithTag("settings.team:original").performScrollTo().performClick()
                        compose.waitUntil(5000) { teams.state.value.scope?.teamId == "original" }
                        assertNotEquals(original.generation, teams.state.value.scope!!.generation)
                        compose.onNodeWithText("Original Mac").performScrollTo().performClick()
                        compose.waitUntil(5000) { feed.computerDetailsState.value != null }
                        assertEquals(original, feed.computerDetailsState.value!!.team)
                        assertEquals(0, dials.get()); assertFalse(root.isClosed)
                        compose.onNodeWithText("‹  Back").performClick()
                        server.profile("original", originalMember = false); teams.refresh()
                        compose.runOnUiThread { assertFalse(feed.selectedTeamChanged()) }
                        assertTrue(root.isClosed); assertEquals("", feed.connectionState(mac.code).code)
                        compose.onNodeWithText("Original Mac").assertDoesNotExist()
                    }
                    compose.runOnUiThread { feed.clear() }
                }
            }
        } } finally { store.clear(); context.deleteSharedPreferences(preference) }
    }

    @Test fun teamChangeRetiresPendingUnadmittedAndRetriedHandles() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preference = "settings-pending-" + UUID.randomUUID()
        val store = NativeCredentialStore(context, preference)
        try {
            store.update { it.put("task_session", "fixture-login").put("refresh_token", "fixture-refresh") }
            lateinit var feed: NativeFeedSession
            compose.runOnUiThread { feed = NativeFeedSession(NativeConnector { _, _ -> error("No dial") }, NativeAccount(store), store) }
            val root = MobileRpcClient(Wire(), { "fixture" }); root.connect()
            compose.runOnUiThread {
                val state = feed.connectionState("pending")
                state.client = root; state.connectionReady = false
                assertFalse(feed.selectedTeamChanged())
                assertTrue(root.isClosed); assertNull(state.client); assertEquals("", state.code)
                feed.clear()
            }
        } finally { store.clear(); context.deleteSharedPreferences(preference) }
    }

    @Test fun confirmedTeamCreationKeepsExactRootAndDoesNotRepeatCreationPost() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preference = "settings-create-" + UUID.randomUUID()
        val store = NativeCredentialStore(context, preference)
        try { MockWebServer().use { server ->
            store.update { it.put("task_session", "fixture-login").put("refresh_token", "fixture-refresh") }
            server.profile("original")
            NativeAccountTeams({ "fixture-access" }, store::taskSession, server.url("/api/v1/"),
                refreshCredential = { "fixture-refresh" }, backendOrigin = server.url("/"), lock = store.accountStateLock).use { teams ->
                teams.refresh(); val original = checkNotNull(teams.state.value.scope)
                val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
                    "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user", "device", "Original", "default"), original)
                store.update { it.put("pairings", JSONArray().put(NativePairingRecords.encode(mac))) }
                lateinit var feed: NativeFeedSession
                compose.runOnUiThread { feed = NativeFeedSession(NativeConnector { _, _ -> error("No dial") }, NativeAccount(store), store, teams) }
                MobileRpcClient(Wire(), { "fixture-root" }).use { root ->
                    root.connect()
                    compose.runOnUiThread {
                        val state = feed.connectionState(mac.code)
                        state.client = root; state.admittedMac = mac; state.admittedRetry = 0
                        state.connectionReady = true; state.connectedCode = mac.code
                        feed.admitForeground(original, mac, root)
                    }
                    server.enqueue(MockResponse().setBody("""{"team":{"id":"created","name":"Created"}}"""))
                    server.teamUsers{MockResponse().setBody("""{"id":"user"}""")}
                    server.enqueue(MockResponse().setBody("""{"items":[{"id":"original","display_name":"Original"},{"id":"created","display_name":"Created"}]}"""))
                    server.teamUsers{MockResponse().setBody("""{"id":"user"}""")}
                    server.enqueue(MockResponse().setBody("""{"id":"user","selected_team":{"id":"created"}}"""))
                    assertTrue(feed.createTeam(teams, "Created"))
                    assertEquals("created", teams.state.value.scope?.teamId)
                    assertSame(root, feed.connectionState(mac.code).client); assertFalse(root.isClosed)
                    assertEquals(original, feed.foregroundDetailsPresentation(mac, null)?.team)
                    val requests = (0 until server.requestCount).map { checkNotNull(server.takeRequest()) }
                    assertEquals(1, requests.count { it.method == "POST" && it.path == "/api/subrouter/teams" })
                    assertTrue(feed.selectedTeamChanged())
                    compose.runOnUiThread { feed.clear() }
                }
            }
        } } finally { store.clear(); context.deleteSharedPreferences(preference) }
    }
}
