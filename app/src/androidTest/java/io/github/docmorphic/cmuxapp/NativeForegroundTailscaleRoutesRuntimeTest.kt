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

class NativeForegroundTailscaleRoutesRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun verifiedOriginalOwnerCanAddEditAndRemoveThroughRealEncryptedStorageAfterSelectionChanges() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "foreground-route-fixture-" + UUID.randomUUID()
        val store = NativeCredentialStore(context, name)
        val probes = CopyOnWriteArrayList<MobileRpcClient>()
        var failAtCommit = false; var screenOwnsRoot = true
        fun wire(host: String = "device"): MobileRpcTransport = object : MobileRpcTransport {
            val input = Channel<ByteArray>(Channel.UNLIMITED)
            override suspend fun connect() {}
            override suspend fun read() = input.receiveCatching().getOrNull()
            override suspend fun write(bytes: ByteArray) {
                val request = JSONObject(MobileFrameDecoder().feed(bytes).single().decodeToString())
                val result = when (request.getString("method")) {
                    "mobile.host.status" -> JSONObject().put("mac_device_id", host).put("mac_instance_tag", "default").put("mac_app_version", "0.65.0")
                    "mobile.attach_ticket.create" -> JSONObject().put("ticket", JSONObject("""{"version":1,"workspaceID":"",
                        "macDeviceID":"device","macUserID":"user","auth_token":"fixture-ticket",
                        "routes":[{"id":"fixture","kind":"tailscale","endpoint":{"type":"host_port","host":"100.64.0.8","port":58465}}]}"""))
                    "mobile.workspace.list" -> JSONObject()
                    else -> error("Unexpected route probe request")
                }
                input.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", true)
                    .put("result", result).toString().toByteArray()))
            }
            override fun close() { input.close() }
        }
        try { MockWebServer().use { server ->
            server.teamUsers(2){MockResponse().setBody("""{"id":"user","selected_team":{"id":"original"}}""")}
            server.enqueue(MockResponse().setBody("""{"items":[{"id":"original","display_name":"Original"},{"id":"picker","display_name":"Picker"}]}"""))
            store.update { it.put("task_session", "fixture-login").put("refresh_token", "fixture-refresh") }
            NativeAccountTeams({ "fixture-access" }, store::taskSession, server.url("/api/v1/"), lock = store.accountStateLock).use { teams ->
                teams.refresh(); val original = checkNotNull(teams.state.value.scope)
                val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
                    "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user", "device", "Original Mac", "default"), original)
                store.update { it.put("pairings", JSONArray().put(NativePairingRecords.encode(mac))).put("pairing_code", mac.code) }
                MobileRpcClient(wire(), { "fixture-root" }).use { root ->
                    root.connect()
                    val authority = NativeForegroundSessionAuthority(teams, store::visiblePairedMacs)
                    authority.admit(original, mac, root)
                    val management = checkNotNull(authority.computerManagement(mac, root))
                    val access = NativeForegroundComputerDetailAccess(original, mac, root, management) {
                        screenOwnsRoot && authority.scopeFor(mac, root) == original
                    }
                    server.teamSelection{MockResponse().setBody("""{"id":"user","selected_team":{"id":"picker"}}""")}
                    teams.select("picker"); val selected = checkNotNull(teams.state.value.scope)
                    assertTrue(access.current())
                    val saved = TailscaleGrantStore(store::load, store::update)
                    val pickerPairing = tailscalePairingInput("100.64.0.8:58465")
                    val pickerGrant = TailscaleSavedGrant(UUID.randomUUID().toString(), selected.userId, selected.teamId,
                        TailscaleGrantStore.source(pickerPairing), mac.deviceId, "default", pickerPairing.routes.single())
                    saved.save(selected, pickerGrant) { true }
                    val gate = NativeMacCompatibilityGate(teams::isCurrent)
                    NativeIrohRuntime(teams.state, teams::isCurrent, { "fixture-access" }, { _, _ ->
                        object : IrohAccountBackend {
                            override val state = MutableStateFlow(IrohV2ControlState())
                            override suspend fun start() {}
                            override suspend fun refresh() {}
                            override fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport = error("Must not dial through selected Iroh")
                            override fun close() {}
                        }
                    }, validateForegroundRoute = gate::verifyComputerRoute).use { runtime ->
                        val routes = NativeForegroundTailscaleRoutes(access, runtime, store::load, { change ->
                            store.update { value -> if (failAtCommit) screenOwnsRoot = false; change(value) }
                        }, resolve = { route, allowed -> check(allowed()); route }, dial = { _, _, bearer ->
                            MobileRpcClient(wire(), bearer).also { probes += it }
                        }, token = { "fixture-access" }, forceToken = { "fixture-access" })
                        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                            val revision by store.revisions.collectAsState()
                            val currentRoutes = remember(revision) { runCatching { routes.routes() }.getOrNull() }
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                NativeTailscaleSection(access.target, NativeMacConnectionMethod.TAILSCALE, currentRoutes,
                                    pair = { input, replacing -> routes.pair(tailscalePairingInput(input), replacing) },
                                    remove = routes::remove, reload = {})
                            }
                        } } }
                        compose.onNodeWithText("Add Tailscale Connection").performScrollTo().performClick()
                        compose.onNodeWithText("Pairing Code or IP Address:Port").performTextInput("100.64.0.8:58465")
                        compose.onNodeWithText("Connect").performClick()
                        compose.waitUntil(5000) { saved.computer(original, access.target).size == 1 }
                        compose.onNodeWithContentDescription("Tailscale actions for 100.64.0.8:58465").performScrollTo().performClick()
                        compose.onNodeWithText("Edit connection").performClick()
                        compose.onNodeWithText("Pairing Code or IP Address:Port").performTextReplacement("100.64.0.9:58465")
                        compose.onNodeWithText("Connect").performClick()
                        compose.waitUntil(5000) { saved.computer(original, access.target).singleOrNull()?.route?.host == "100.64.0.9" }
                        compose.onNodeWithContentDescription("Tailscale actions for 100.64.0.9:58465").performScrollTo().performClick()
                        compose.onNodeWithText("Remove connection").performClick()
                        compose.onNodeWithText("Remove").performClick()
                        compose.waitUntil(5000) { saved.computer(original, access.target).isEmpty() }
                        assertEquals(pickerGrant, saved.find(selected, pickerGrant.source)); assertEquals(selected, teams.state.value.scope)
                        assertEquals(mac.code, store.load()!!.getString("pairing_code")); assertFalse(root.isClosed)
                        compose.onNodeWithText("Add Tailscale Connection").performScrollTo().performClick()
                        compose.onNodeWithText("Pairing Code or IP Address:Port").performTextInput("100.64.0.10:58465")
                        failAtCommit = true
                        compose.onNodeWithText("Connect").performClick()
                        compose.waitUntil(5000) { compose.onAllNodesWithText("Computer route owner changed").fetchSemanticsNodes().isNotEmpty() }
                        assertTrue(saved.computer(original, access.target).isEmpty())
                        assertEquals(pickerGrant, saved.find(selected, pickerGrant.source))
                        assertTrue(probes.all { it.isClosed }); assertFalse(root.isClosed)
                        val encrypted = context.getSharedPreferences(name, 0).getString("state", "")!!
                        assertFalse(encrypted.contains("100.64.0.8")); assertFalse(encrypted.contains("fixture-refresh"))
                    }
                    authority.clear()
                }
            }
        } } finally { store.clear(); context.deleteSharedPreferences(name) }
    }
}
