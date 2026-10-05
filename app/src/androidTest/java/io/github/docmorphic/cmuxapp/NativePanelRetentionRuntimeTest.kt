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
    private fun screenshot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "panel-retention").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(dir, "$name.png")))
    }
    private fun find(selector: BySelector) = checkNotNull(device.wait(Until.findObject(selector), 20_000)) { "Missing $selector" }
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
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture" }).also { it.connect() }
            }
            ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use { body(peer, it) }
        } finally { NativeLifecycleTestActivity.connector = null; credentials.clear() } }
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

    @Test fun markdownRawSearchAndReadingPositionSurviveActualPanelRecreationWithoutRefetch() = fixture { peer, scenario ->
        val text = "# Panel document\n" + (1..180).joinToString("\n") { "Line $it " + if (it == 10 || it == 130) "needle" else "body text for reading" }
        response(peer, text)
        find(By.text("Panel workspace")).click(); action("Raw")
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
            release.countDown(); action("Raw")
            await("Pending panel did not finish") { scenario.read { it.window.decorView.textPreview()?.textView?.text?.toString() == text } }
            val file = checkNotNull(owner.preview.state.value.artifact).file
            assertEquals(text, file.readText()); assertEquals(1, fetches(peer)); screenshot("pending-recreated")
            peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"panels","title":"Panel workspace","terminals":[],"surfaces":[]}]}""")
            val coordinator = scenario.session().coordinator
            runBlocking { withContext(Dispatchers.Main) { coordinator.refreshWorkspaceLists(listOf(owner.mac)) } }
            await("Removed panel retained private bytes") { !file.exists() }
            assertFalse(owner.current()); assertNull(scenario.panel())
            assertNull(scenario.read { it.window.decorView.textPreview() }); find(By.text("Panel closed")); screenshot("panel-removed")
        } finally { release.countDown() }
    }
}
