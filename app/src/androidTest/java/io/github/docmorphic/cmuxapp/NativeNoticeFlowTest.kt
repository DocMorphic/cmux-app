package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/** Real Gecko pages through the actual launch/archive Compose surfaces, with synthetic local content. */
class NativeNoticeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Store : WhatsNewStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(updates: Map<String, String?>): Boolean {
            updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }; return true
        }
    }
    private fun server() = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "text/html")
                .setHeader("Cache-Control", "no-store").setBody("""<!doctype html><meta name="viewport" content="width=device-width">
                <style>body{margin:20px;font:24px sans-serif;color:#111;background:white}
                #proof{height:140px;background:rgb(22,163,74);color:white;padding:16px}</style>
                <h1>cmux announcement</h1><div id="proof">Ready to read</div><p>Private rendered content</p>""")
        }
        start(InetAddress.getByName("127.0.0.1"), 0)
    }
    private fun captureRendered(name: String) {
        // A page may paint before its load finishes. Capture completed content, not a
        // transient frame with the loading indicator still over the document.
        compose.waitUntil(15_000) {
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .fetchSemanticsNodes().isEmpty()
        }
        val i = InstrumentationRegistry.getInstrumentation()
        val deadline = System.nanoTime() + 10_000_000_000L
        var green = 0
        do {
            compose.waitForIdle() // Drive the test clock so pending AndroidView composition can attach.
            val bitmap = i.uiAutomation.takeScreenshot()
            green = 0
            for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                val color = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(color) in 18..26 && android.graphics.Color.green(color) in 159..167 &&
                    android.graphics.Color.blue(color) in 70..78) green++
            }
            if (green > 1000) {
                val dir = java.io.File(i.targetContext.getExternalFilesDir(null), "notice-flow").apply { mkdirs() }
                java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle(); return
            }
            bitmap.recycle(); Thread.sleep(100)
        } while (System.nanoTime() < deadline)
        fail("Rendered notice color region missing: $green sampled pixels")
    }
    @Test fun launchWaitsForOffscreenLoadDisplaysSamePageAndAcknowledgesOnlyAfterAppearance() {
        val server = server(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val origin = "http://127.0.0.1:${server.port}"
        val page = WhatsNewPage("web", "Web", WhatsNewBody.Web("$origin/page"))
        val store = Store(); val center = NativeWhatsNewCenter(listOf(page), "0.2.0", WhatsNewChannel.DEV, store, origin)
        runBlocking { center.refresh { """{"visibleEntryIds":["a","b","native","web"]}""" } }
        val exchangeGate = CompletableDeferred<Unit>(); val exchanges = AtomicInteger()
        val p = NativeWhatsNewPresentation(center, scope) { content, _, dark ->
            NativeNoticeRenderer(compose.activity, scope, center.webPolicy, (content.body as WhatsNewBody.Web).url,
                dark, NativeWhatsNewWebLoad.LAUNCH_DEADLINE_MS, { true }) {
                exchanges.incrementAndGet(); exchangeGate.await(); emptyList()
            }
        }
        try {
            compose.setContent { CmuxTheme {
                NativeWhatsNewHost(center, p, "fixture", true, false, {}, NativeMacCompatibilityPolicy.baked)
            } }
            compose.waitUntil(10_000) { exchanges.get() == 1 }
            compose.onNodeWithTag("whatsnew.sheet").assertDoesNotExist()
            compose.runOnIdle { assertNull(store.values[NativeWhatsNewCenter.MARKER]); exchangeGate.complete(Unit) }
            compose.waitUntil(15_000) { p.state.value?.appeared == true }
            compose.onNodeWithTag("whatsnew.web").assertIsDisplayed()
            captureRendered("launch")
            compose.runOnIdle {
                assertEquals(WhatsNewWebPhase.LOADED, p.webPage(page)!!.load.phase.value)
                assertEquals("web", store.values[NativeWhatsNewCenter.MARKER]); assertEquals(1, exchanges.get())
            }
            compose.onNodeWithTag("whatsnew.close").performClick()
            compose.onNodeWithTag("whatsnew.sheet").assertDoesNotExist()
            compose.runOnIdle { assertNull(p.webPage(page)) }
        } finally { compose.runOnIdle { p.close(); scope.cancel(); center.close() }; server.shutdown() }
    }
    @Test fun archiveFailureRetryCreatesFreshExchangeAndRendersWithoutAcknowledging() {
        val server = server(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val origin = "http://127.0.0.1:${server.port}"
        val page = WhatsNewPage("web", "Web", WhatsNewBody.Web("$origin/page"))
        val store = Store(); val center = NativeWhatsNewCenter(listOf(page), "0.2.0", WhatsNewChannel.DEV, store, origin)
        runBlocking { center.refresh { """{"visibleEntryIds":["a","b","native","web"]}""" } }
        val p = NativeWhatsNewPresentation(center); val exchanges = AtomicInteger()
        val archive = NativeNoticeArchiveOwner(compose.activity.applicationContext, scope)
        try {
            compose.setContent { CmuxTheme {
                NativeWhatsNewHost(center, p, "fixture", false, true, {}, NativeMacCompatibilityPolicy.baked,
                    webArchive = archive, isOwnerCurrent = { it == "fixture" }, sessionCookies = {
                        if (exchanges.incrementAndGet() == 1) error("Synthetic exchange failure")
                        emptyList()
                    })
            } }
            compose.onNodeWithTag("whatsnew.archive.entry:web").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("whatsnew.web.retry").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("whatsnew.web.retry").performClick()
            compose.waitUntil(15_000) { exchanges.get() == 2 }
            captureRendered("archive-retry")
            compose.onNodeWithTag("whatsnew.web.retry").assertDoesNotExist()
            compose.runOnIdle { assertEquals(2, exchanges.get()); assertNull(store.values[NativeWhatsNewCenter.MARKER]) }
            compose.onNodeWithTag("whatsnew.archive.back").performClick()
            compose.onNodeWithTag("whatsnew.archive.entry:web").assertIsDisplayed()
        } finally { compose.runOnIdle { archive.close(); p.close(); scope.cancel(); center.close() }; server.shutdown() }
    }
}
