package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.content.MutableContextWrapper
import android.os.Build
import android.webkit.WebView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RetainedBrowserRuntimeTest {
    @Test fun recreationKeepsLiveDomHistoryAndViewThenReleasesActivityContextOnClose() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val paths = CopyOnWriteArrayList<String>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths += request.path.orEmpty()
                    return MockResponse().setHeader("Content-Type", "text/html").setHeader("Cache-Control", "no-store")
                        .setBody("""<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                            <title>${if (request.path == "/next") "Next fixture" else "First fixture"}</title>
                            <body style="background:#174d3a;color:white;font:24px sans-serif">
                            <a href="/next" style="color:white">Open next fixture</a>
                            <input id="draft"><p id="state">No draft</p></body>""")
                }
            }
            server.start()
            fun text(value: String) = checkNotNull(device.wait(Until.findObject(By.text(value)), 15_000)) { "Missing $value" }
            fun desc(value: String) = checkNotNull(device.wait(Until.findObject(By.desc(value)), 15_000))
            lateinit var model: RetainedBrowserTestModel
            lateinit var originalWeb: WebView
            lateinit var wrapped: MutableContextWrapper
            ActivityScenario.launch<RetainedBrowserTestActivity>(Intent(context, RetainedBrowserTestActivity::class.java)
                .putExtra("url", server.url("/first").toString())).use { scenario ->
                fun js(script: String): String {
                    val done = CountDownLatch(1); var result = ""
                    scenario.onActivity { activity -> activity.window.decorView.findViewWithTag<WebView>("LocalBrowserWebView")
                        .evaluateJavascript(script) { result = it; done.countDown() } }
                    assertTrue(done.await(5, TimeUnit.SECONDS)); return result
                }
                text("First fixture"); text("Open next fixture").click(); text("Next fixture")
                assertEquals("41", js("document.getElementById('draft').value='Unsent fixture draft';window.unsentCounter=41;document.getElementById('state').textContent='Unsent fixture draft';window.unsentCounter"))
                text("Unsent fixture draft")
                scenario.onActivity {
                    model = ViewModelProvider(it)[RetainedBrowserTestModel::class.java]
                    originalWeb = it.window.decorView.findViewWithTag("LocalBrowserWebView")
                    wrapped = originalWeb.context as MutableContextWrapper
                    assertSame(it, wrapped.baseContext)
                }
                val reads = paths.count { it == "/next" }
                repeat(2) {
                    scenario.recreate(); text("Next fixture"); text("Unsent fixture draft")
                    assertEquals("[\"Unsent fixture draft\",41]", js("[document.getElementById('draft').value,window.unsentCounter]"))
                    scenario.onActivity { activity ->
                        assertSame(model, ViewModelProvider(activity)[RetainedBrowserTestModel::class.java])
                        assertSame(originalWeb, activity.window.decorView.findViewWithTag<WebView>("LocalBrowserWebView"))
                        assertSame(activity, wrapped.baseContext)
                        assertTrue(model.surface!!.state.value.canGoBack)
                    }
                    assertEquals(reads, paths.count { it == "/next" })
                }
                val shots = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                assertTrue(device.takeScreenshot(File(shots, "retained-browser-recreated.png")))
                desc("Browser Back").click(); text("First fixture")
                desc("Browser Forward").click(); text("Next fixture")
            }
            instrumentation.waitForIdleSync()
            assertTrue(model.closed); assertTrue(model.surface!!.state.value.closed)
            assertSame(context.applicationContext, wrapped.baseContext)
            assertNull(originalWeb.parent)
        }
    }
}
