package io.github.docmorphic.cmuxapp

import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Uses the actual NativeScreen panel route and its real retained connection owner. */
class NativePanelRetentionRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    @Volatile private var reconnectGate: CompletableDeferred<Unit>? = null
    private fun screenshot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "panel-retention").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(dir, "$name.png")))
    }
    private fun find(selector: BySelector): UiObject2 {
        val found = device.wait(Until.findObject(selector), 20_000)
        if (found == null) {
            screenshot("missing-control")
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "panel-retention/missing-control.xml"))
        }
        return checkNotNull(found) { "Missing $selector" }
    }
    private fun await(message: String, test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < end) { if (test()) return; Thread.sleep(75) }
        screenshot("failure"); fail(message)
    }
    private fun <T> ActivityScenario<NativeLifecycleTestActivity>.read(block: (NativeLifecycleTestActivity) -> T): T {
        var value: T? = null; onActivity { value = block(it) }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun View.textPreview(): ArtifactTextScrollView? = when (this) {
        is ArtifactTextScrollView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).textPreview() }
        else -> null
    }
    private fun ActivityScenario<NativeLifecycleTestActivity>.session() = read { ViewModelProvider(it)[NativeFeedSession::class.java] }
    private fun ActivityScenario<NativeLifecycleTestActivity>.panel() = read { ViewModelProvider(it)[NativeFeedSession::class.java].panelPreview }
    private fun action(name: String) { find(By.desc("Viewer actions").enabled(true)).click(); find(By.text(name)).click() }
    private fun fixture(body: (NativeFixturePeer, ActivityScenario<NativeLifecycleTestActivity>) -> Unit) {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val credentials = NativeCredentialStore(context)
        NativeFixturePeer().use { peer -> try {
            credentials.clear(); credentials.update { it.put("refresh_token", "panel-retention-fixture")
                .put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465") }
            peer.panelArtifactsSupported = true
            peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"panels","title":"Panel workspace","terminals":[],"surfaces":[
                {"surface_id":"markdown","kind":"markdown","title":"Markdown panel","file_path":"/fixture/extensionless","is_focused":true}
            ]}]}""")
            NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
                reconnectGate?.await()
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture" }).also { it.connect() }
            }
            ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use { body(peer, it) }
        } finally { reconnectGate?.complete(Unit); reconnectGate = null; NativeLifecycleTestActivity.connector = null; credentials.clear() } }
    }
    private fun response(peer: NativeFixturePeer, text: String, beforeFetch: () -> Unit = {}) {
        peer.artifactResponse = { method, params ->
            check(method.startsWith("mobile.panel.artifact."))
            check(params.getString("workspace_id") == "panels" && params.getString("surface_id") == "markdown")
            check(params.getString("path") == "/fixture/extensionless")
            val bytes = text.toByteArray()
            if (method.endsWith("stat")) JSONObject().put("exists", true).put("is_directory", false).put("kind", "text").put("size", bytes.size)
            else { beforeFetch(); JSONObject().put("offset", 0).put("total_size", bytes.size).put("eof", true)
                .put("data_b64", Base64.getEncoder().encodeToString(bytes)) }
        }
    }
    private fun fetches(peer: NativeFixturePeer) = peer.requests.count { it.optString("method") == "mobile.panel.artifact.fetch" }

    private fun View.webPreview(): android.webkit.WebView? = when (this) {
        is android.webkit.WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).webPreview() }
        else -> null
    }
    private fun js(scenario: ActivityScenario<NativeLifecycleTestActivity>, script: String): String? {
        var answer: String? = null; val done = CountDownLatch(1)
        scenario.onActivity { activity ->
            val web = activity.window.decorView.webPreview()
            if (web == null) done.countDown() else web.evaluateJavascript(script) { answer = it; done.countDown() }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return answer
    }
    private fun painted(scenario: ActivityScenario<NativeLifecycleTestActivity>, name: String) {
        val done = CountDownLatch(1)
        scenario.onActivity { it.window.decorView.webPreview()!!.postVisualStateCallback(0,
            object : android.webkit.WebView.VisualStateCallback() { override fun onComplete(id: Long) { done.countDown() } }) }
        assertTrue(done.await(5, TimeUnit.SECONDS)); Thread.sleep(350); screenshot(name)
    }

    @Suppress("DEPRECATION")
    @Test fun renderedPanelSurvivesWireLossAndRecreationThenInvalidatesOnRefreshAndRevocation() = fixture { peer, scenario ->
        val text = (1..100).joinToString("\n\n") { "## Section $it\n\n" + "Reading this panel on the phone. ".repeat(8) }
        response(peer, text); find(By.text("Panel workspace")).click()
        await("Rendered panel missing") { js(scenario, "document.querySelectorAll('#content h2').length === 100") == "true" }
        assertTrue(device.findObject(UiSelector().className(android.webkit.WebView::class.java.name)).pinchOut(35, 30))
        val bounds = find(By.clazz(android.webkit.WebView::class.java.name)).visibleBounds
        device.swipe(bounds.centerX(), bounds.bottom - 150, bounds.centerX(), bounds.top + 150, 45)
        device.waitForIdle(); Thread.sleep(350)
        val position = scenario.read { it.window.decorView.webPreview()!!.let { web -> web.scale to web.scrollY } }
        assertTrue(position.second > 500)
        val anchor = js(scenario, "document.getElementById('section-4').getBoundingClientRect().top - visualViewport.offsetTop")!!.toDouble()
        val owner = checkNotNull(scenario.panel()); val file = checkNotNull(owner.preview.state.value.artifact).file
        fun sameReading() {
            assertSame(owner, scenario.panel()); assertEquals(file, owner.preview.state.value.artifact?.file)
            assertEquals(1, fetches(peer)); assertEquals(text, file.readText())
            await("Rendered reading position lost") { scenario.read { it.window.decorView.webPreview()?.let { web ->
                kotlin.math.abs(web.scale - position.first) < .04f && kotlin.math.abs(web.scrollY - position.second) < 12
            } == true } }
            await("Rendered paragraph shifted") { js(scenario, "document.getElementById('section-4')?.getBoundingClientRect().top - visualViewport.offsetTop")
                ?.toDoubleOrNull()?.let { kotlin.math.abs(it - anchor) < 2 } == true }
        }
        reconnectGate = CompletableDeferred(); peer.disconnectClients()
        await("Feed did not disconnect") { scenario.session().coordinator.sources.value[owner.mac.origin]?.availability == NativeFeedAvailability.OFFLINE }
        assertFalse(owner.access.current()); assertTrue(owner.access.cachedCurrent()); sameReading(); painted(scenario, "rendered-offline")
        scenario.recreate(); sameReading(); painted(scenario, "rendered-offline-recreated")
        reconnectGate!!.complete(Unit); reconnectGate = null
        val coordinator = scenario.session().coordinator
        runBlocking { withTimeout(15_000) { withContext(Dispatchers.Main) { coordinator.refreshWorkspaceLists(listOf(owner.mac)) } } }
        sameReading(); assertFalse(owner.access.current()); painted(scenario, "rendered-reconnected")
        response(peer, "# Updated panel\n\nNew bytes after the Mac changes its title.")
        peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"panels","title":"Panel workspace","terminals":[],"surfaces":[
            {"surface_id":"markdown","kind":"markdown","title":"Updated panel","file_path":"/fixture/extensionless","is_focused":true}
        ]}]}""")
        runBlocking { withContext(Dispatchers.Main) { coordinator.refreshWorkspaceLists(listOf(owner.mac)) } }
        await("Title change did not replace panel") { js(scenario, "document.querySelector('#content h1')?.textContent === 'Updated panel'") == "true" }
        await("Old panel bytes were retained after title change") { !file.exists() }
        assertEquals(2, fetches(peer)); val replacement = checkNotNull(scenario.panel())
        val replacementFile = checkNotNull(replacement.preview.state.value.artifact).file
        painted(scenario, "rendered-title-refresh")
        peer.rejectedMethods = setOf("mobile.workspace.list"); peer.rejectedMethodCode = "team_access_revoked"
        runBlocking { withContext(Dispatchers.Main) { assertTrue(runCatching { coordinator.refreshWorkspaceLists(listOf(owner.mac)) }.isFailure) } }
        await("Revocation retained bytes") { !replacementFile.exists() && scenario.panel() == null }
        assertFalse(replacement.current()); assertFalse(replacement.access.cachedCurrent())
        find(By.text("Preview unavailable"))
        reconnectGate = CompletableDeferred(); peer.disconnectClients()
        await("Revoked feed did not disconnect") { coordinator.sources.value[owner.mac.origin]?.availability == NativeFeedAvailability.OFFLINE }
        find(By.text("Preview unavailable")); screenshot("panel-revoked")
    }

    @Test fun interruptedPanelTransferDiscardsOldAdmissionAndFetchesOnVerifiedReconnect() = fixture { peer, scenario ->
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        response(peer, "# Recovered transfer\n\nComplete bytes from the new connection.") {
            started.countDown(); check(release.await(45, TimeUnit.SECONDS))
        }
        try {
            find(By.text("Panel workspace")).click(); assertTrue(started.await(20, TimeUnit.SECONDS))
            val owner = checkNotNull(scenario.panel())
            reconnectGate = CompletableDeferred(); peer.disconnectClients()
            await("Interrupted panel owner not released") { scenario.panel() == null }
            assertFalse(owner.current()); assertNull(owner.preview.state.value.artifact)
            release.countDown(); assertEquals(1, fetches(peer))
            val coordinator = scenario.session().coordinator
            reconnectGate!!.complete(Unit); reconnectGate = null
            runBlocking { withTimeout(15_000) { withContext(Dispatchers.Main) { coordinator.refreshWorkspaceLists(listOf(owner.mac)) } } }
            await("New connection did not complete the panel") { js(scenario, "document.querySelector('#content h1')?.textContent === 'Recovered transfer'") == "true" }
            val replacement = checkNotNull(scenario.panel()); assertNotSame(owner, replacement)
            assertEquals(2, fetches(peer)); assertFalse(owner.access.current()); assertNull(owner.preview.state.value.artifact)
            assertEquals("# Recovered transfer\n\nComplete bytes from the new connection.", replacement.preview.state.value.artifact!!.file.readText())
            painted(scenario, "pending-wire-reconnected")
        } finally { release.countDown() }
    }

    @Test fun markdownRawSearchAndReadingPositionSurviveActualPanelRecreationWithoutRefetch() = fixture { peer, scenario ->
        val text = "# Panel document\n" + (1..180).joinToString("\n") { "Line $it " + if (it == 10 || it == 130) "needle" else "body text for reading" }
        response(peer, text)
        find(By.text("Panel workspace")).click()
        await("Rendered document did not finish loading") {
            js(scenario, "document.querySelector('#content h1')?.textContent === 'Panel document'") == "true"
        }
        action("Raw")
        await("Raw Markdown did not load") { scenario.read { it.window.decorView.textPreview()?.textView?.text?.toString() == text } }
        action("Search"); find(By.clazz("android.widget.EditText")).text = "needle"
        find(By.text("1/2")); find(By.desc("Next match")).click(); find(By.text("2/2")); device.pressBack()
        await("Search did not scroll") { scenario.read { (it.window.decorView.textPreview()?.scrollY ?: 0) > 1_000 } }
        val y = scenario.read { it.window.decorView.textPreview()!!.scrollY }
        val owner = checkNotNull(scenario.panel())
        val file = checkNotNull(owner.preview.state.value.artifact).file
        assertEquals(text, file.readText()); assertEquals(1, fetches(peer))
        scenario.recreate(); find(By.text("2/2")); device.pressBack()
        await("Panel lost reading position") { scenario.read { it.window.decorView.textPreview()?.let { view -> kotlin.math.abs(view.scrollY - y) < 5 } == true } }
        assertSame(owner, scenario.panel())
        assertEquals(file, owner.preview.state.value.artifact?.file); assertEquals(1, fetches(peer))
        assertEquals(text, scenario.read { it.window.decorView.textPreview()!!.textView.text.toString() })
        screenshot("markdown-recreated")
        find(By.desc("Back to workspaces")).click()
        await("Leaving panel leaked its preview") { !file.exists() }
        assertNull(scenario.panel())
    }

    @Test fun pendingPanelTransferSurvivesRecreationAndPanelRemovalReleasesItsBytes() = fixture { peer, scenario ->
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val text = "# Pending panel\n\nExact content after recreation."
        response(peer, text) { started.countDown(); check(release.await(45, TimeUnit.SECONDS)) }
        try {
            find(By.text("Panel workspace")).click(); assertTrue(started.await(20, TimeUnit.SECONDS))
            val owner = checkNotNull(scenario.panel())
            scenario.recreate(); find(By.text("Loading preview"))
            assertSame(owner, scenario.panel()); assertEquals(1, fetches(peer))
            release.countDown()
            await("Pending document did not finish rendering") {
                js(scenario, "document.querySelector('#content h1')?.textContent === 'Pending panel'") == "true"
            }
            action("Raw")
            await("Pending panel did not finish") { scenario.read { it.window.decorView.textPreview()?.textView?.text?.toString() == text } }
            val file = checkNotNull(owner.preview.state.value.artifact).file
            assertEquals(text, file.readText()); assertEquals(1, fetches(peer)); screenshot("pending-recreated")
            peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"panels","title":"Panel workspace","terminals":[],"surfaces":[]}]}""")
            val coordinator = scenario.session().coordinator
            runBlocking { withContext(Dispatchers.Main) { coordinator.refreshWorkspaceLists(listOf(owner.mac)) } }
            await("Removed panel retained private bytes") { !file.exists() }
            assertFalse(owner.current()); assertNull(scenario.panel())
            assertNull(scenario.read { it.window.decorView.textPreview() })
            // The feed may show Panel closed before the foreground list also removes
            // the selected surface. Exercise that authoritative event and final route.
            peer.pushTerminalEvent("workspace.list.changed", JSONObject())
            find(By.text("Waiting for workspace panes…"))
            assertNull(scenario.panel()); assertFalse(file.exists())
            assertNull(scenario.read { it.window.decorView.webPreview() }); screenshot("panel-removed")
        } finally { release.countDown() }
    }
}
