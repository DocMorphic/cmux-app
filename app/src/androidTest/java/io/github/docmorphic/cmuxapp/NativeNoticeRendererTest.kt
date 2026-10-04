package io.github.docmorphic.cmuxapp

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.Cookie
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mozilla.geckoview.*
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Uses the delivered renderer/extension, synthetic cookies and local HTTP only. */
class NativeNoticeRendererTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val reports = LinkedBlockingQueue<JSONObject>()
    private val reportedLabels = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private fun <T> main(block: () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun server() = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/report") == true) {
                    val report = JSONObject(request.body.readUtf8())
                    reportedLabels.add(report.getString("label")); reports.add(report)
                    return MockResponse().setResponseCode(204)
                }
                val label = request.requestUrl?.queryParameter("label") ?: "blank"
                val cookie = request.getHeader("Cookie").orEmpty()
                return MockResponse().setHeader("Content-Type", "text/html").setHeader("Cache-Control", "no-store")
                    .setBody("""<!doctype html><meta name="viewport" content="width=device-width"><style>
                    body{font:24px sans-serif;background:#fff;color:#111;padding:24px}
                    @media(prefers-color-scheme:dark){body{background:#111;color:#fff}}</style>
                    <h1>Private cmux notice</h1><p id="label">$label</p><script>
                    const previous=localStorage.getItem('notice-value');
                    localStorage.setItem('notice-value', ${JSONObject.quote(label)});
                    fetch('/report',{method:'POST',body:JSON.stringify({label:${JSONObject.quote(label)},
                      cookie:${JSONObject.quote(cookie)},scriptCookie:document.cookie,previous:previous,
                      dark:matchMedia('(prefers-color-scheme:dark)').matches})});</script>""")
            }
        }
        start(InetAddress.getByName("127.0.0.1"), 0)
    }
    private fun report(label: String): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            val item = reports.poll(1, TimeUnit.SECONDS) ?: continue
            if (item.getString("label") == label) return item
        }
        throw AssertionError("No page report for $label")
    }
    private fun cookie(value: String) = listOf(Cookie.Builder().name("stack-access").value(value)
        .hostOnlyDomain("127.0.0.1").path("/").httpOnly().build())
    private fun outcome(page: NativeNoticeRenderer) = runBlocking { withTimeout(30_000) { page.load.outcome() } }
    private fun assertLoaded(page: NativeNoticeRenderer) {
        val result = outcome(page)
        assertEquals("Stage: ${page.diagnostic}", WhatsNewWebPhase.LOADED, result)
    }
    private fun retired(page: NativeNoticeRenderer) = runBlocking { withContext(Dispatchers.Main) {
        withTimeout(10_000) { page.awaitRetired() }
    } }
    private fun <T> field(instance: Any, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
    }

    @Test fun privatePagesRenderRetainThemeAndClearOnlyTheirOwnContext() {
        val server = server()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val activity = ActivityScenario.launch(NativeNoticeTestActivity::class.java)
        var a: NativeNoticeRenderer? = null; var b: NativeNoticeRenderer? = null
        var reopened: GeckoSession? = null
        try {
            val origin = "http://127.0.0.1:${server.port}/"
            a = main { NativeNoticeRenderer(instrumentation.targetContext, scope, WhatsNewWebPolicy(origin),
                "http://127.0.0.1:${server.port}/page?label=A", true, 20_000, { true }) { cookie("a") } }
            activity.onActivity { a.attach(it.pageView) }
            assertLoaded(a)
            val first = report("A")
            assertEquals("stack-access=a", first.getString("cookie")); assertEquals("", first.getString("scriptCookie"))
            assertTrue(first.isNull("previous")); assertTrue(first.getBoolean("dark"))
            b = main { NativeNoticeRenderer(instrumentation.targetContext, scope, WhatsNewWebPolicy(origin),
                "http://127.0.0.1:${server.port}/page?label=B", true, 20_000, { true }) { cookie("b") } }
            assertLoaded(b)
            val second = report("B")
            assertEquals("stack-access=b", second.getString("cookie")); assertEquals("", second.getString("scriptCookie"))
            assertTrue(second.isNull("previous"))
            // Reattach the existing session, not a new load, after recreation.
            main { a.detach() }; activity.recreate(); activity.onActivity { a.attach(it.pageView) }
            main { a.theme(false) }
            main { field<GeckoSession>(a, "session").loadUri("http://127.0.0.1:${server.port}/page?label=theme") }
            val themed = report("theme"); assertFalse(themed.getBoolean("dark")); assertEquals("A", themed.getString("previous"))
            val root = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "notice-production").apply { mkdirs() }
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                java.io.File(root, "rendered.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            val oldContext = field<String>(a, "contextId")
            val runtime = field<GeckoRuntime>(field<NativeNoticeEngine>(a, "engine"), "runtime")
            main { a.close() }; assertTrue(retired(a))
            // Test-only reopening of the exact retired context proves scoped clearing, rather than merely using a new UUID.
            reopened = main { GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).contextId(oldContext).build()).also {
                it.open(runtime); it.loadUri("http://127.0.0.1:${server.port}/page?label=empty")
            } }
            val empty = report("empty"); assertEquals("", empty.getString("cookie")); assertTrue(empty.isNull("previous"))
            main { field<GeckoSession>(b, "session").loadUri("http://127.0.0.1:${server.port}/page?label=B-again") }
            val intact = report("B-again"); assertEquals("stack-access=b", intact.getString("cookie")); assertEquals("B", intact.getString("previous"))
            java.io.File(root, "report.json").writeText(JSONObject().put("first", first).put("second", second)
                .put("theme", themed).put("retired", empty).put("other_context", intact).toString(2))
        } finally {
            main { reopened?.close(); a?.close(); b?.close() }
            a?.let { retired(it) }; b?.let { retired(it) }
            scope.cancel(); activity.close(); server.shutdown()
        }
    }

    @Test fun accountReplacementDuringNonCooperativeExchangeNeverNavigatesOrSeeds() {
        val server = server(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var current = true
        val page = main { NativeNoticeRenderer(instrumentation.targetContext, scope,
            WhatsNewWebPolicy("http://127.0.0.1:${server.port}/"), "http://127.0.0.1:${server.port}/page?label=late", false, 20_000,
            { current }) { entered.complete(Unit); withContext(NonCancellable) { finish.await() }; cookie("retired") } }
        try {
            runBlocking { withTimeout(25_000) { entered.await() } }
            main { current = false; page.close() }; assertTrue(retired(page))
            finish.complete(Unit)
            assertEquals(WhatsNewWebPhase.FAILED, outcome(page))
            assertNull(server.takeRequest(1, TimeUnit.SECONDS))
        } finally { finish.complete(Unit); main { page.close() }; scope.cancel(); server.shutdown() }
    }

    @Test fun extensionLossRetiresOldPagesAndRetryClearsReceiptsBeforeFreshSession() {
        val server = server(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val activity = ActivityScenario.launch(NativeNoticeTestActivity::class.java)
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var old: NativeNoticeRenderer? = null; var pending: NativeNoticeRenderer? = null
        var fresh: NativeNoticeRenderer? = null; var reopened: GeckoSession? = null
        try {
            val origin = "http://127.0.0.1:${server.port}/"
            old = main { NativeNoticeRenderer(instrumentation.targetContext, scope, WhatsNewWebPolicy(origin),
                "${origin}page?label=old", false, 20_000, { true }) { cookie("old") } }
            activity.onActivity { old.attach(it.pageView) }; assertLoaded(old)
            val before = report("old"); assertEquals("stack-access=old", before.getString("cookie"))
            val oldContext = field<String>(old, "contextId")
            val engine = field<NativeNoticeEngine>(old, "engine")
            val runtime = field<GeckoRuntime>(engine, "runtime")
            pending = main { NativeNoticeRenderer(instrumentation.targetContext, scope, WhatsNewWebPolicy(origin),
                "${origin}page?label=late", false, 20_000, { true }) {
                entered.complete(Unit); withContext(NonCancellable) { finish.await() }; cookie("late")
            } }
            runBlocking { withTimeout(15_000) { entered.await() } }
            // Real extension shutdown, rather than calling the native invalidation method.
            val disabled = CompletableDeferred<Unit>()
            main {
                val extension = field<WebExtension.Port>(engine, "port").sender.webExtension
                runtime.webExtensionController.setAllowedInPrivateBrowsing(extension, false).accept(
                    { disabled.complete(Unit) }, { disabled.completeExceptionally(it ?: IllegalStateException("Disable failed")) })
            }
            runBlocking { withTimeout(15_000) {
                disabled.await()
                while (!main { old.isClosed.value && pending.isClosed.value }) delay(25)
            } }
            assertFalse(retired(old)) // Cookie cleanup is retained for the next successful connection.
            assertTrue(main { field<Map<*, *>>(engine, "receipts").size >= 2 })
            var exchanges = 0
            fresh = main { NativeNoticeRenderer(instrumentation.targetContext, scope, WhatsNewWebPolicy(origin),
                "${origin}page?label=fresh", false, 20_000, { true }) { exchanges++; cookie("fresh") } }
            activity.onActivity { fresh.attach(it.pageView) }; assertLoaded(fresh)
            val after = report("fresh"); assertEquals("stack-access=fresh", after.getString("cookie"))
            assertTrue(after.isNull("previous")); assertEquals(1, main { exchanges })
            assertSame(engine, field<NativeNoticeEngine>(fresh, "engine"))
            assertNotEquals(oldContext, field<String>(fresh, "contextId"))
            assertTrue(main { field<Set<*>>(engine, "retiring").isEmpty() })
            assertEquals(1, main { field<Map<*, *>>(engine, "receipts").size })
            finish.complete(Unit)
            assertEquals(WhatsNewWebPhase.FAILED, outcome(pending))
            reopened = main { GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).contextId(oldContext).build()).also {
                it.open(runtime); it.loadUri("${origin}page?label=retired-after-restart")
            } }
            val empty = report("retired-after-restart")
            assertEquals("", empty.getString("cookie")); assertTrue(empty.isNull("previous"))
            main { field<GeckoSession>(fresh, "session").loadUri("${origin}page?label=fresh-again") }
            val intact = report("fresh-again")
            assertEquals("stack-access=fresh", intact.getString("cookie")); assertEquals("fresh", intact.getString("previous"))
            assertNull(reports.poll(1, TimeUnit.SECONDS))
            assertFalse(reportedLabels.contains("late")) // No discarded report can hide a stale navigation.
            val root = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "notice-recovery").apply { mkdirs() }
            java.io.File(root, "report.json").writeText(JSONObject().put("old", before).put("fresh", after)
                .put("retired_context", empty).put("fresh_preserved", intact).toString(2))
        } finally {
            finish.complete(Unit)
            main { reopened?.close(); old?.close(); pending?.close(); fresh?.close() }
            fresh?.let { retired(it) }; scope.cancel(); activity.close(); server.shutdown()
        }
    }

}
