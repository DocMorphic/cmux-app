package io.github.docmorphic.cmuxapp

import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import io.github.docmorphic.cmuxapp.iroh.IrxWire
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
    @Volatile private var reconnectFailure: Throwable? = null
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
    private fun fixture(kind: String = "markdown", body: (NativeFixturePeer, ActivityScenario<NativeLifecycleTestActivity>) -> Unit) {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val credentials = NativeCredentialStore(context)
        NativeFixturePeer().use { peer -> try {
            credentials.clear(); credentials.update { it.put("refresh_token", "panel-retention-fixture")
                .put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465") }
            peer.panelArtifactsSupported = true
            peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"panels","title":"Panel workspace","terminals":[],"surfaces":[
                {"surface_id":"markdown","kind":"markdown","title":"Markdown panel","file_path":"/fixture/extensionless","is_focused":true}
            ]}]}""")
            peer.customWorkspaceListing!!.getJSONArray("workspaces").getJSONObject(0)
                .getJSONArray("surfaces").getJSONObject(0).put("kind", kind)
            NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
                reconnectGate?.await()
                reconnectFailure?.let { throw it }
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture" }).also { it.connect() }
            }
            ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use { body(peer, it) }
        } finally { reconnectGate?.complete(Unit); reconnectGate = null; reconnectFailure = null; NativeLifecycleTestActivity.connector = null; credentials.clear() } }
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

    private fun refreshPanel(peer: NativeFixturePeer, scenario: ActivityScenario<NativeLifecycleTestActivity>, title: String) {
        peer.customWorkspaceListing = JSONObject(peer.customWorkspaceListing!!.toString()).also {
            it.getJSONArray("workspaces").getJSONObject(0).getJSONArray("surfaces").getJSONObject(0).put("title", title)
        }
        val coordinator = scenario.session().coordinator
        val mac = scenario.read { coordinator.sources.value.values.single().mac }
        runBlocking { withTimeout(15_000) { withContext(Dispatchers.Main) { coordinator.refreshWorkspaceLists(listOf(mac)) } } }
        peer.pushTerminalEvent("workspace.list.changed", JSONObject())
        await("Refreshed panel was not selected") { scenario.panel()?.target?.title == title }
    }

    private fun changePanel(peer: NativeFixturePeer, scenario: ActivityScenario<NativeLifecycleTestActivity>, kind: String, path: String) {
        peer.customWorkspaceListing = JSONObject(peer.customWorkspaceListing!!.toString()).also {
            it.getJSONArray("workspaces").getJSONObject(0).getJSONArray("surfaces").getJSONObject(0)
                .put("kind", kind).put("file_path", path)
        }
        val coordinator = scenario.session().coordinator
        val mac = scenario.read { coordinator.sources.value.values.single().mac }
        runBlocking { withTimeout(15_000) { withContext(Dispatchers.Main) { coordinator.refreshWorkspaceLists(listOf(mac)) } } }
        peer.pushTerminalEvent("workspace.list.changed", JSONObject())
        await("Current panel kind/path not selected") { scenario.panel()?.target?.let { it.kind == kind && it.path == path } == true }
    }

    @Test fun livePanelKindAndPathChangesReplaceTheRendererAndReleaseOldBytesWithoutATitleChange() = fixture { peer, scenario ->
        val original = "# Original panel\n\nA Markdown panel changed into a plain file."
        response(peer, original); find(By.text("Panel workspace")).click()
        await("Original Markdown missing") { js(scenario, "document.querySelector('#content h1')?.textContent === 'Original panel'") == "true" }
        painted(scenario, "live-kind-markdown")
        val markdown = checkNotNull(scenario.panel()); val firstFile = checkNotNull(markdown.preview.state.value.artifact).file
        changePanel(peer, scenario, "filePreview", "/fixture/extensionless")
        await("File kind did not replace the Markdown renderer") { scenario.read {
            it.window.decorView.webPreview() == null && it.window.decorView.textPreview()?.textView?.text?.toString() == original
        } }
        val plain = checkNotNull(scenario.panel()); assertNotSame(markdown, plain)
        assertEquals(markdown.target.title, plain.target.title); assertFalse(markdown.current())
        await("Kind change retained old bytes") { !firstFile.exists() }; paintedText(scenario, "live-kind-plain-file")
        assertEquals(2, fetches(peer))
        val plainFile = checkNotNull(plain.preview.state.value.artifact).file
        val pixels = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val encoded = ByteArrayOutputStream().also { assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        pixels.recycle()
        peer.artifactResponse = { method, params ->
            check(params.getString("path") == "/fixture/changing.png")
            if (method.endsWith("stat")) JSONObject().put("exists", true).put("is_directory", false)
                .put("kind", "image").put("mime_type", "image/png").put("size", encoded.size)
            else JSONObject().put("offset", 0).put("total_size", encoded.size).put("eof", true)
                .put("data_b64", Base64.getEncoder().encodeToString(encoded))
        }
        changePanel(peer, scenario, "filePreview", "/fixture/changing.png")
        find(By.desc("Image preview changing.png")); device.waitForIdle()
        await("Path change retained old bytes") { !plainFile.exists() }
        assertFalse(plain.current()); assertEquals(3, fetches(peer))
        scenario.recreate(); find(By.desc("Image preview changing.png")); assertEquals(3, fetches(peer))
        screenshot("live-path-image-recreated")
        val captured = BitmapFactory.decodeFile(File(context.getExternalFilesDir(null), "panel-retention/live-path-image-recreated.png").absolutePath)
        try {
            val bounds = find(By.desc("Image preview changing.png")).visibleBounds
            val color = captured.getPixel(bounds.centerX(), bounds.centerY())
            assertTrue("Image was not visibly green", Color.green(color) > 220 && Color.red(color) < 40 && Color.blue(color) < 40)
        } finally { captured.recycle() }
        val imageOwner = checkNotNull(scenario.panel()); val imageFile = checkNotNull(imageOwner.preview.state.value.artifact).file
        response(peer, "# Returned Markdown\n\nA fresh document on the same surface.")
        changePanel(peer, scenario, "markdown", "/fixture/extensionless")
        await("Markdown renderer did not return") { js(scenario, "document.querySelector('#content h1')?.textContent === 'Returned Markdown'") == "true" }
        await("Image bytes retained after returning to Markdown") { !imageFile.exists() }
        assertFalse(imageOwner.current()); assertEquals(4, fetches(peer))
        assertEquals(markdown.target.title, scenario.panel()?.target?.title)
        painted(scenario, "live-kind-returned-markdown")
    }

    @Test fun nativeRevocationDuringReconnectDiscardsRenderedPanelAndItsPrivateFile() = fixture { peer, scenario ->
        response(peer, "# Native rejection\n\nDo not retain this document after revoked admission.")
        find(By.text("Panel workspace")).click()
        await("Initial panel missing") { js(scenario, "document.querySelector('#content h1')?.textContent === 'Native rejection'") == "true" }
        val owner = checkNotNull(scenario.panel()); val file = checkNotNull(owner.preview.state.value.artifact).file
        reconnectGate = CompletableDeferred(); peer.disconnectClients()
        await("Feed did not disconnect") { scenario.session().coordinator.sources.value[owner.mac.origin]?.availability == NativeFeedAvailability.OFFLINE }
        assertTrue(file.exists()); assertTrue(owner.access.cachedCurrent())
        reconnectFailure = IOException("native rejection wrapper", IrxWire.AdmissionRejected(IrxWire.CloseCode.REVOKED))
        // Restart the feed's monitor with its cached snapshot retained. A refresh
        // caller would wait for verified admission while this dial is rejected.
        scenario.onActivity { activity ->
            val coordinator = ViewModelProvider(activity)[NativeFeedSession::class.java].coordinator
            coordinator.pause(); coordinator.updateMacs(listOf(owner.mac))
        }
        reconnectGate!!.complete(Unit); reconnectGate = null
        await("Native rejection retained cached admission") { scenario.session().coordinator.sources.value[owner.mac.origin]?.panelCacheToken == null }
        find(By.text("Preview unavailable"))
        await("Native rejection retained private bytes") { !file.exists() && scenario.panel() == null }
        assertFalse(owner.current()); assertFalse(owner.access.cachedCurrent())
        assertNull(scenario.read { it.window.decorView.webPreview() }); assertEquals(1, fetches(peer))
        screenshot("native-revocation-panel-discarded")
    }

    @Test fun markdownPanelFailuresKeepTheirMeaningAcrossRecreationAndRetry() = fixture { peer, scenario ->
        response(peer, "# Retry recovered\n\nFresh bytes after an explicit retry.")
        peer.rejectedMethods = setOf("mobile.panel.artifact.stat"); peer.rejectedMethodCode = "file_not_found"
        find(By.text("Panel workspace")).click(); find(By.text("File not found"))
        assertFalse(device.hasObject(By.text("Retry"))); val owner = checkNotNull(scenario.panel())
        val requests = peer.requests.count { it.optString("method") == "mobile.panel.artifact.stat" }
        scenario.recreate(); find(By.text("File not found")); assertSame(owner, scenario.panel())
        assertEquals(requests, peer.requests.count { it.optString("method") == "mobile.panel.artifact.stat" })
        screenshot("failure-missing-recreated")
        for ((code, title) in listOf("forbidden" to "Preview unavailable", "workspace_not_found" to "Panel closed",
            "method_not_found" to "Update cmux on your Mac")) {
            peer.rejectedMethodCode = code; refreshPanel(peer, scenario, code)
            find(By.text(title)); assertFalse(device.hasObject(By.text("Retry")))
        }
        screenshot("failure-update-mac")
        peer.rejectedMethodCode = "unavailable"; refreshPanel(peer, scenario, "Retry this panel")
        find(By.text("Transfer unavailable")); find(By.text("Retry")); screenshot("failure-transfer-retry")
        val retryOwner = checkNotNull(scenario.panel()); peer.rejectedMethods = emptySet()
        find(By.text("Retry")).click()
        await("Manual retry did not render the file") { js(scenario, "document.querySelector('#content h1')?.textContent === 'Retry recovered'") == "true" }
        assertSame(retryOwner, scenario.panel()); assertEquals(1, fetches(peer)); painted(scenario, "failure-retry-recovered")
    }

    @Test fun filePanelFailuresKeepFileSpecificRemediation() = fixture(kind = "filePreview") { peer, scenario ->
        response(peer, "File panel recovered.")
        peer.rejectedMethods = setOf("mobile.panel.artifact.stat"); peer.rejectedMethodCode = "permission_denied"
        find(By.text("Panel workspace")).click(); find(By.text("Permission denied")); assertFalse(device.hasObject(By.text("Retry")))
        screenshot("failure-file-permission")
        peer.rejectedMethodCode = "invalid_params"; refreshPanel(peer, scenario, "Invalid request")
        find(By.text("Invalid file request")); assertFalse(device.hasObject(By.text("Retry")))
        peer.rejectedMethodCode = "file_changed"; refreshPanel(peer, scenario, "File changed")
        find(By.text("File changed")); find(By.text("Retry")); screenshot("failure-file-changed")
        peer.rejectedMethods = emptySet(); find(By.text("Retry")).click()
        await("File retry did not show the recovered text") {
            scenario.read { it.window.decorView.textPreview()?.textView?.text?.toString() == "File panel recovered." }
        }
        assertEquals(1, fetches(peer)); screenshot("failure-file-recovered")
    }

    @Test fun failedPanelRetainsItsErrorAndRetriesOnlyOnTheNewVerifiedConnection() = fixture { peer, scenario ->
        response(peer, "# Explicit reconnect retry\n\nFresh bytes on the replacement connection.")
        peer.rejectedMethods = setOf("mobile.panel.artifact.stat"); peer.rejectedMethodCode = "unavailable"
        find(By.text("Panel workspace")).click(); find(By.text("Transfer unavailable"))
        val owner = checkNotNull(scenario.panel()); val session = scenario.session(); val coordinator = session.coordinator
        fun stats() = peer.requests.count { it.optString("method") == "mobile.panel.artifact.stat" }
        val count = stats()
        reconnectGate = CompletableDeferred(); peer.disconnectClients()
        await("Failed panel feed did not disconnect") { coordinator.sources.value[owner.mac.origin]?.availability == NativeFeedAvailability.OFFLINE }
        find(By.text("Transfer unavailable")); assertSame(owner, scenario.panel()); assertTrue(owner.current())
        assertFalse(owner.access.current()); assertTrue(owner.access.cachedCurrent())
        find(By.text("Retry")).click(); find(By.text("Not connected"))
        assertEquals(count, stats()); assertEquals(0, fetches(peer)); screenshot("failed-panel-offline-retry")
        scenario.recreate(); find(By.text("Not connected")); assertSame(owner, scenario.panel())
        assertEquals(count, stats()); screenshot("failed-panel-offline-recreated")
        peer.rejectedMethods = emptySet()
        reconnectGate!!.complete(Unit); reconnectGate = null
        runBlocking { withTimeout(15_000) { withContext(Dispatchers.Main) { coordinator.refreshWorkspaceLists(listOf(owner.mac)) } } }
        find(By.text("Mac unreachable")); assertSame(owner, scenario.panel())
        assertFalse(owner.access.current()); assertEquals(count, stats()); assertEquals(0, fetches(peer))
        screenshot("failed-panel-ready-for-retry")
        find(By.text("Retry")).click()
        await("Explicit retry did not use the replacement feed") {
            js(scenario, "document.querySelector('#content h1')?.textContent === 'Explicit reconnect retry'") == "true"
        }
        val replacement = checkNotNull(scenario.panel()); assertNotSame(owner, replacement)
        assertEquals(count + 1, stats()); assertEquals(1, fetches(peer)); assertNull(owner.preview.state.value.artifact)
        assertFalse(owner.current())
        runBlocking { withContext(Dispatchers.Main) {
            assertTrue(runCatching { owner.access.rpc.stat(owner.target.authorization, owner.target.path) }.isFailure)
            session.retryPanel(owner)
        } }
        assertSame(replacement, scenario.panel()); assertEquals(count + 1, stats())
        painted(scenario, "failed-panel-reconnected-retry-success")
    }

    @Test fun revocationDiscardsRetainedFailureAndRejectsItsRetryCallback() = fixture { peer, scenario ->
        response(peer, "# Must not load after revocation")
        peer.rejectedMethods = setOf("mobile.panel.artifact.stat"); peer.rejectedMethodCode = "unavailable"
        find(By.text("Panel workspace")).click(); find(By.text("Transfer unavailable"))
        val owner = checkNotNull(scenario.panel()); val session = scenario.session(); val coordinator = session.coordinator
        val count = peer.requests.count { it.optString("method").startsWith("mobile.panel.artifact.") }
        peer.rejectedMethods = setOf("mobile.workspace.list"); peer.rejectedMethodCode = "team_access_revoked"
        runBlocking { withContext(Dispatchers.Main) {
            assertTrue(runCatching { coordinator.refreshWorkspaceLists(listOf(owner.mac)) }.isFailure)
        } }
        await("Revocation retained the failed panel") { scenario.panel() == null }
        assertFalse(owner.current()); assertFalse(owner.access.cachedCurrent()); find(By.text("Preview unavailable"))
        assertFalse(device.hasObject(By.text("Retry")))
        scenario.onActivity { session.retryPanel(owner) }
        assertNull(scenario.panel()); assertEquals(count, peer.requests.count { it.optString("method").startsWith("mobile.panel.artifact.") })
        assertEquals(0, fetches(peer)); screenshot("failed-panel-revoked")
    }

    @Test fun oversizedPanelShowsSizeLimitWithoutFetchingOrRetry() = fixture { peer, scenario ->
        peer.artifactResponse = { method, _ ->
            check(method.endsWith("stat")) { "An oversized file must not be downloaded" }
            JSONObject().put("exists", true).put("is_directory", false).put("kind", "text")
                .put("size", ChangesContentTransfer.PREVIEW_BYTES + 1)
        }
        find(By.text("Panel workspace")).click(); find(By.text("File too large to preview"))
        find(By.textContains("exceeds the")); assertFalse(device.hasObject(By.text("Retry")))
        assertEquals(0, fetches(peer)); assertNull(scenario.panel()?.preview?.state?.value?.artifact)
        screenshot("failure-size-limit")
    }

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

    private fun paintedText(scenario: ActivityScenario<NativeLifecycleTestActivity>, name: String) {
        await("Plain text did not finish layout") { scenario.read { activity ->
            activity.window.decorView.textPreview()?.textView?.let {
                it.isShown && it.width > 0 && it.height > 0 && it.layout != null
            } == true
        } }
        val done = CountDownLatch(1)
        scenario.onActivity { activity ->
            val view = checkNotNull(activity.window.decorView.textPreview()).textView
            view.postOnAnimation { view.postOnAnimation { done.countDown() } }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS)); device.waitForIdle(); screenshot(name)
        val bounds = scenario.read { activity -> android.graphics.Rect().also {
            assertTrue(checkNotNull(activity.window.decorView.textPreview()).textView.getGlobalVisibleRect(it))
        } }
        val captured = BitmapFactory.decodeFile(File(context.getExternalFilesDir(null), "panel-retention/$name.png").absolutePath)
        try {
            var ink = 0
            for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(captured.height))
                for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(captured.width)) {
                    val color = captured.getPixel(x, y)
                    if (Color.red(color) > 100 && Color.green(color) > 100 && Color.blue(color) > 100) ink++
                }
            assertTrue("Plain text content was not visibly painted ($ink pixels)", ink > 100)
            File(context.getExternalFilesDir(null), "panel-retention/$name-paint.txt").writeText("bounds=$bounds ink=$ink")
        } finally { captured.recycle() }
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

    @Test fun interruptedPanelTransferRequiresExplicitRetryOnVerifiedReconnect() = fixture { peer, scenario ->
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        response(peer, "# Recovered transfer\n\nComplete bytes from the new connection.") {
            started.countDown(); check(release.await(45, TimeUnit.SECONDS))
        }
        try {
            find(By.text("Panel workspace")).click(); assertTrue(started.await(20, TimeUnit.SECONDS))
            val owner = checkNotNull(scenario.panel())
            reconnectGate = CompletableDeferred(); peer.disconnectClients()
            find(By.text("Not connected")); assertSame(owner, scenario.panel())
            assertTrue(owner.current()); assertFalse(owner.access.current()); assertNull(owner.preview.state.value.artifact)
            scenario.recreate(); find(By.text("Not connected")); assertSame(owner, scenario.panel())
            screenshot("pending-wire-failed")
            release.countDown(); assertEquals(1, fetches(peer))
            val coordinator = scenario.session().coordinator
            reconnectGate!!.complete(Unit); reconnectGate = null
            runBlocking { withTimeout(15_000) { withContext(Dispatchers.Main) { coordinator.refreshWorkspaceLists(listOf(owner.mac)) } } }
            find(By.text("Mac unreachable")); assertSame(owner, scenario.panel()); assertEquals(1, fetches(peer))
            find(By.text("Retry")).click()
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
