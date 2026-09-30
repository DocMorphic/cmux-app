package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Production Activity + bound service + proxy. Generated host only; never touches credentials. */
class RoutedBrowserPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val paths = CopyOnWriteArrayList<String>()
    private val targets = CopyOnWriteArrayList<String>()
    private val holds = AtomicInteger()
    private val releases = AtomicInteger()
    private val probes = CopyOnWriteArrayList<Boolean>()
    private val key = LocalBrowserKey("generated-account", "generated-team", "generated-mac", "workspace")
    private val workspace = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"workspace","title":"Fixture workspace","terminals":[{"id":"terminal","title":"Fixture shell"}]}]}""")).single()
    private lateinit var network: NativeMacBrowserNetwork
    private lateinit var navigation: LocalBrowserNavigation
    private lateinit var server: MockWebServer
    private lateinit var surface: LocalBrowserSurface
    private var route: NativeWorkspaceRoute? = null
    private fun <T> main(block: () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun text(value: String) = checkNotNull(device.wait(Until.findObject(By.text(value)), 30_000)) { "Missing: $value" }
    private fun browser(value: String): UiObject2 {
        // The parent uses Compose's virtual clock; the other process uses real frames.
        // Pump the parent until its asynchronous registration actually launches the child.
        compose.waitUntil(15_000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        return text(value)
    }
    private fun desc(value: String) = checkNotNull(device.wait(Until.findObject(By.desc(value)), 15_000)) { "Missing: $value" }
    private fun until(predicate: () -> Boolean) = runBlocking { withTimeout(15_000) { while (!predicate()) delay(100) } }
    @Before fun setup() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Disposable emulator required" }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths += request.path!!
                    val next = request.path!!.startsWith("/next")
                    return MockResponse().setHeader("Content-Type", "text/html").setHeader("Cache-Control", "no-store")
                        .setBody("""<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>${if(next) "Next" else "Routed fixture"}</title><body style="background:#164f3b;color:white;font:24px sans-serif"><h1>Mac route fixture</h1><a style="color:white" href="/next">Open next page</a><p><button onclick="window.draft=true;renderDraft()">Keep draft</button></p><script>function renderDraft(){if(window.draft)document.title='Draft '+(innerWidth>innerHeight?'landscape':'portrait')}addEventListener('resize',renderDraft);document.body.dataset.cookie=document.cookie;document.cookie='presentation=kept;path=/'</script>""")
                }
            }; start()
        }
        main {
            network = NativeMacBrowserNetwork(owner, object : MacBrowserAccess {
                override suspend fun availability() = MacBrowserAvailability.AVAILABLE
                override suspend fun listeningPorts() = BrowserTunnelProtocol.ListeningPorts(emptyList(), false)
                override suspend fun use(host: String, port: Int, connected: suspend (BrowserTunnelLane) -> Unit) {
                    targets += "$host:$port"
                    NioBrowserSocket.direct.use("127.0.0.1", server.port, connected)
                }
            }, { true })
            navigation = LocalBrowserNavigation(owner, LocalBrowserStore(defaultUrl = "http://localhost:34876/start"))
            navigation.restoreRemembered(key, workspace)
            surface = navigation.state.value.local!!.surface
        }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            val state by navigation.state.collectAsState()
            val destination = state.local
            if (destination == null) Button(onClick = { navigation.restoreRemembered(key, workspace) }) { Text("Reopen fixture") }
            else RoutedLocalBrowserWorkspaceView(destination, navigation, workspace, { network }, {
                holds.incrementAndGet()
                RoutedBrowserHostLease({ holds.decrementAndGet(); releases.incrementAndGet() }, { probes += it })
            }, {}, { route = it })
        } } }
    }
    @After fun cleanup() {
        if (::network.isInitialized) main { network.close(); navigation.clear() }
        if (::network.isInitialized) runBlocking { delay(300) }
        owner.cancel()
        if (::server.isInitialized) server.shutdown()
    }
    @Test fun productionBrowserKeepsHostAndReturnsCommittedPageThenSelectsPane() {
        browser("Routed fixture ▾")
        assertEquals(1, holds.get())
        assertTrue(targets.contains("localhost:34876"))
        text("Open next page").click(); text("Next ▾")
        until { main { surface.state.value.url?.endsWith("/next") == true } }
        assertFalse(compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        val shots = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(shots, "routed-browser-production.png")))
        desc("Back to workspaces").click(); compose.waitForIdle(); text("Reopen fixture")
        until { holds.get() == 0 }
        assertEquals(1, releases.get()); assertTrue(probes.contains(true)); assertEquals(false, probes.last())
        assertFalse(main { surface.state.value.closed })
        compose.onNodeWithText("Reopen fixture").performClick(); browser("Next ▾")
        assertEquals(1, holds.get())
        text("Next ▾").click(); text("Fixture shell").click(); compose.waitForIdle(); text("Reopen fixture")
        until { holds.get() == 0 }
        assertEquals("terminal", main { route?.terminalId })
        assertTrue(main { surface.state.value.closed })
        assertEquals(2, releases.get())
    }
    @Test fun retiredOwnerClosesPresentationReleasesHostAndRemovesOnlyItsStorage() {
        browser("Routed fixture ▾")
        val storage = File(context.applicationInfo.dataDir, "app_webview_cmux_browser_${network.storageId}")
        until { storage.isDirectory }
        main { network.close() }
        compose.waitForIdle()
        text("Reopen fixture")
        until { holds.get() == 0 && !storage.exists() }
        assertEquals(1, releases.get())
        assertEquals(false, probes.last())
        assertTrue(network.retired.isCompleted)
    }
    @Test fun rotationKeepsUnsubmittedPageStateHistoryAndHostLease() {
        device.setOrientationNatural()
        try {
            browser("Routed fixture ▾")
            text("Open next page").click(); text("Next ▾")
            text("Keep draft").click(); text("Draft portrait ▾")
            val loaded = paths.count { it == "/next" }
            assertEquals(1, loaded)
            device.setOrientationLeft()
            until { device.displayRotation != 0 }
            text("Draft landscape ▾")
            val shots = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            assertTrue(device.takeScreenshot(File(shots, "routed-browser-landscape.png")))
            assertEquals(loaded, paths.count { it == "/next" })
            assertEquals(1, holds.get()); assertEquals(0, releases.get())
            device.setOrientationNatural()
            until { device.displayRotation == 0 }
            text("Draft portrait ▾")
            assertEquals(loaded, paths.count { it == "/next" })
            desc("Browser Back").click(); text("Routed fixture ▾")
            desc("Browser Forward").click(); text("Next ▾")
            desc("Back to workspaces").click(); compose.waitForIdle(); text("Reopen fixture")
            until { holds.get() == 0 }
            assertEquals(1, releases.get())
        } finally { device.setOrientationNatural(); device.unfreezeRotation() }
    }
}
