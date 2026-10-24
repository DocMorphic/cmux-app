package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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

class NativeComputerDetailsLifetimeRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun originalDetailAndConfirmedRemovalSurviveActivityRecreationAfterTeamSwitch() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "details-lifetime-" + UUID.randomUUID()
        val store = NativeCredentialStore(context, preferenceName)
        val gate = CompletableDeferred<Unit>(); val revokes = AtomicInteger()
        val finished = CopyOnWriteArrayList<Int>(); val renders = AtomicInteger()
        val connector = object : NativeConnector {
            override suspend fun connect(pairing: PairingCode.Tailscale, account: NativeAccount): MobileRpcClient = error("Must not dial")
        }
        val wire = object : MobileRpcTransport {
            val input = Channel<ByteArray>(Channel.UNLIMITED)
            override suspend fun connect() {}
            override suspend fun read() = input.receiveCatching().getOrNull()
            override suspend fun write(bytes: ByteArray) {
                val request = JSONObject(MobileFrameDecoder().feed(bytes).single().decodeToString())
                val result = if (request.getString("method") == "mobile.host.status") JSONObject()
                    .put("mac_device_id", "device").put("mac_instance_tag", "default").put("mac_app_version", "0.65.0")
                    .put("capabilities", JSONArray()) else JSONObject()
                input.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id"))
                    .put("ok", true).put("result", result).toString().toByteArray()))
            }
            override fun close() { input.close() }
        }
        var lifecycle: Application.ActivityLifecycleCallbacks? = null
        val application = context.applicationContext as Application
        try { MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"user","selected_team":{"id":"original"}}"""))
            server.enqueue(MockResponse().setBody("""{"items":[{"id":"original","display_name":"Original"},{"id":"picker","display_name":"Picker"}]}"""))
            store.update { it.put("task_session", "fixture-login").put("refresh_token", "fixture-refresh") }
            NativeAccountTeams({ "fixture" }, store::taskSession, server.url("/api/v1/"), lock = store.accountStateLock).use { teams ->
                teams.refresh(); val original = checkNotNull(teams.state.value.scope)
                val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
                    "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user", "device", "Original Mac", "default"), original)
                store.update { it.put("pairings", JSONArray().put(NativePairingRecords.encode(mac))).put("pairing_code", mac.code) }
                lateinit var feed: NativeFeedSession
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        NativeFeedSession(connector, NativeAccount(store), store, teams) as T
                }
                compose.activityRule.scenario.onActivity { feed = ViewModelProvider(it, factory)[NativeFeedSession::class.java] }
                MobileRpcClient(wire, { "fixture-root" }).use { root ->
                    root.connect()
                    compose.runOnUiThread {
                        val state = feed.connectionState(mac.code)
                        state.client = root; state.admittedMac = mac; state.connectionReady = true; state.connectedCode = mac.code
                        feed.admitForeground(original, mac, root)
                        val access = checkNotNull(feed.foregroundComputerDetails(mac))
                        feed.computerDetailsState.value = NativeComputerDetailsPresentation(original, access.target, foreground = access)
                    }
                    val page = checkNotNull(feed.computerDetailsState.value)
                    repeat(2){server.enqueue(MockResponse().setBody("""{"id":"user","selected_team":{"id":"picker"}}"""))}
                    teams.select("picker"); val selected = checkNotNull(teams.state.value.scope)
                    val picker = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(mac.code, mac.deviceId, "Picker Mac", "default"), selected)
                    store.update { it.put("pairings", JSONArray().put(NativePairingRecords.encode(mac)).put(NativePairingRecords.encode(picker)))
                        .put("computer_selection", picker.origin) }
                    NativeIrohRuntime(teams.state, teams::isCurrent, { "fixture" }, { owner, current ->
                        object : IrohAccountBackend {
                            override val state = MutableStateFlow(IrohV2ControlState(ready = true, permissionExpiresAt = 2000))
                            override val privatePaths = NativePrivatePathStore({ null }, {})
                            override suspend fun start() {}
                            override suspend fun startManagement() { assertEquals(original, owner); check(current()) }
                            override suspend fun revokeComputer(target: NativeComputerTarget) {
                                assertEquals(original, owner); assertTrue(root.isClosed); revokes.incrementAndGet(); gate.await(); check(current())
                            }
                            override suspend fun refresh() {}
                            override fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport = error("Must not dial")
                            override fun close() {}
                        }
                    }, now = { 1000 }).use { runtime ->
                        fun render(activity: ComponentActivity) {
                            val retained = ViewModelProvider(activity, factory)[NativeFeedSession::class.java]
                            assertSame(feed, retained)
                            val generation = renders.incrementAndGet()
                            activity.setContent { CmuxTheme {
                                val computerState by runtime.state.collectAsState()
                                val presented = retained.computerDetailsState.value
                                NativeComputerDetailsPresentationHost(runtime, computerState, presented,
                                    NativeComputerConnection(NativeFeedAvailability.CONNECTED, true, 3),
                                    NativeComputerForgetCallbacks(started = retained::prepareComputerForget,
                                        finished = { finished += generation }), credentialStore = store,
                                    confirm = retained::confirmComputerForget, prepareDetached = retained::prepareComputerForget) {
                                    if (retained.computerDetailsState.value === presented) retained.computerDetailsState.value = null
                                }
                            } }
                        }
                        lifecycle = object : Application.ActivityLifecycleCallbacks {
                            override fun onActivityCreated(activity: Activity, state: Bundle?) { if (activity is ComponentActivity) render(activity) }
                            override fun onActivityStarted(activity: Activity) {}
                            override fun onActivityResumed(activity: Activity) {}
                            override fun onActivityPaused(activity: Activity) {}
                            override fun onActivityStopped(activity: Activity) {}
                            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
                            override fun onActivityDestroyed(activity: Activity) {}
                        }
                        application.registerActivityLifecycleCallbacks(lifecycle)
                        compose.activityRule.scenario.onActivity(::render)
                        compose.onAllNodesWithText("Original Mac").onFirst().assertIsDisplayed()
                        compose.onNodeWithText("Check Connection").performScrollTo().assertIsEnabled().performClick()
                        compose.waitUntil(5000) { compose.onAllNodesWithText("Verified").fetchSemanticsNodes().size == 2 }
                        compose.onAllNodesWithText("Verified").assertCountEquals(2)
                        compose.onNodeWithText("Forget This Computer").performScrollTo().performClick()
                        compose.onNodeWithText("Forget Computer").performClick()
                        compose.waitUntil(5000) { revokes.get() == 1 }
                        assertSame(page, feed.computerDetailsState.value)
                        compose.activityRule.scenario.recreate()
                        compose.onNodeWithText("Forgetting…").assertIsDisplayed().assertIsNotEnabled()
                        assertSame(page, feed.computerDetailsState.value); assertEquals(1, revokes.get())
                        gate.complete(Unit)
                        compose.waitUntil(5000) { feed.computerDetailsState.value == null }
                        assertEquals(listOf(2), finished); assertEquals(1, revokes.get())
                        assertEquals(listOf(picker), store.pairedMacs()); assertEquals(picker.origin, store.load()!!.getString("computer_selection"))
                        val reopened = NativeCredentialStore(context, preferenceName)
                        assertEquals(picker.code, reopened.load()!!.getString("pairing_code"))
                        assertEquals(picker.origin, reopened.load()!!.getString("computer_selection"))
                        assertEquals(selected, teams.state.value.scope)
                        application.unregisterActivityLifecycleCallbacks(lifecycle); lifecycle = null
                    }
                }
            }
        } } finally {
            lifecycle?.let(application::unregisterActivityLifecycleCallbacks)
            gate.complete(Unit); store.clear(); context.deleteSharedPreferences(preferenceName)
        }
    }
}
