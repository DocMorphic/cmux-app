package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Self-contained pages only; never run account fixtures or touch a Mac terminal. */
class LocalBrowserViewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var retryFails = true
    private fun page(title: String, color: String, body: String = "") = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody("""<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1"><title>$title</title>
            <style>html,body{margin:0;min-height:100%;background:$color}a,button{display:block;width:100%;height:70px;font-size:22px}</style>$body""")
    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path?.substringBefore('?')) {
                    "/first" -> page("First", "#ff0000", """<a id="next" href="/second">Next page</a>""")
                    "/second" -> page("Second", "#00ff00")
                    "/popup" -> page("Popup", "#ff0000", """<a id="blank" target="_blank" href="/second">New window link</a>
                        <button id="script" onclick="window.open('/second')">Open window</button>
                        <form method="post" action="/posted" target="_blank"><input type="hidden" name="message" value="hello"><button id="post">Submit form</button></form>""")
                    "/posted" -> page("Posted", "#0000ff")
                    "/storage" -> page("Stored", "#00ff00", """<script>localStorage.setItem('cmux-browser-fixture','present')</script>""")
                        .setHeader("Set-Cookie", "cmux_fixture=present; Path=/; SameSite=Lax")
                    "/redirect" -> MockResponse().setResponseCode(302).setHeader("Location", "/second").setHeadersDelay(600, TimeUnit.MILLISECONDS)
                    "/slow" -> page("Slow", "#0000ff").setBodyDelay(3, TimeUnit.SECONDS)
                    "/retry" -> if (retryFails) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else page("Recovered", "#00ff00")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }
    @After fun stop() { server.shutdown() }
    private fun url(path: String) = server.url(path).toString()
    private fun show(surface: LocalBrowserSurface, visible: () -> Boolean = { true }, close: () -> Unit = {}) {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            if (visible()) LocalBrowserPane(surface, close)
        } } }
    }
    private fun web(): WebView = compose.activity.window.decorView.findViewWithTag("LocalBrowserWebView")
    private fun js(expression: String): String {
        val ready = CountDownLatch(1); var value = ""
        compose.runOnIdle { web().evaluateJavascript(expression) { value = it; ready.countDown() } }
        assertTrue("JavaScript result", ready.await(3, TimeUnit.SECONDS)); return value
    }
    private fun loaded(surface: LocalBrowserSurface, title: String) {
        compose.waitUntil(10000) { surface.state.value.title == title && !surface.state.value.loading && surface.state.value.progress == 1f }
    }
    private fun touch(id: String) {
        val rect = JSONArray(js("JSON.stringify((()=>{let r=document.getElementById('$id').getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2,window.innerWidth]})())").let { JSONArray("[$it]").getString(0) })
        var width = 0
        compose.runOnIdle { width = web().width }
        val scale = width / rect.getDouble(2)
        compose.onNodeWithTag("LocalBrowserPage").performTouchInput { click(Offset((rect.getDouble(0)*scale).toFloat(), (rect.getDouble(1)*scale).toFloat())) }
    }
    private fun painted(name: String, expected: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bounds = compose.onNodeWithTag("LocalBrowserPage").fetchSemanticsNode().boundsInWindow
        var accepted: Bitmap? = null
        compose.waitUntil(5000) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            var matched = 0
            for (dx in -2..2) for (dy in -2..2) {
                val actual = bitmap.getPixel(bounds.center.x.toInt()+dx*12, bounds.center.y.toInt()+dy*12)
                if (kotlin.math.abs(Color.red(actual)-Color.red(expected)) < 20 &&
                    kotlin.math.abs(Color.green(actual)-Color.green(expected)) < 20 &&
                    kotlin.math.abs(Color.blue(actual)-Color.blue(expected)) < 20) matched++
            }
            if (matched >= 24) { accepted = bitmap; true } else { bitmap.recycle(); false }
        }
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { accepted!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        accepted!!.recycle()
    }

    @Test fun renderedNavigationHistoryReloadAndClose() {
        val surface = LocalBrowserSurface("navigation", url("/first")); var closed = false
        show(surface, close = { closed = true })
        loaded(surface, "First"); painted("local-browser-first", Color.RED)
        compose.onNodeWithContentDescription("Browser Back").assertIsNotEnabled()
        touch("next"); loaded(surface, "Second"); painted("local-browser-second", Color.GREEN)
        compose.onNodeWithContentDescription("Browser Back").performClick(); loaded(surface, "First")
        compose.onNodeWithContentDescription("Browser Forward").performClick(); loaded(surface, "Second")
        val count = requests.count { it.path == "/second" }
        compose.onNodeWithContentDescription("Reload page").performClick()
        compose.waitUntil(5000) { requests.count { it.path == "/second" } > count }; loaded(surface, "Second")
        compose.onNodeWithContentDescription("Close Browser").performClick(); compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun newWindowLinkScriptAndPostStayInThePane() {
        val surface = LocalBrowserSurface("popups", url("/popup")); show(surface)
        for ((id, title, path) in listOf(Triple("blank", "Second", "/second"), Triple("script", "Second", "/second"), Triple("post", "Posted", "/posted"))) {
            compose.runOnIdle { surface.load(url("/popup")) }; loaded(surface, "Popup")
            touch(id); loaded(surface, title)
            assertEquals(url(path), surface.state.value.url)
            painted("local-browser-$id", if (id == "post") Color.BLUE else Color.GREEN)
        }
        val post = requests.single { it.path == "/posted" }
        assertEquals("POST", post.method); assertEquals("message=hello", post.body.readUtf8())
    }

    @Test fun remountRestoresUrlCookiesAndStorageWithFreshHistory() {
        val surface = LocalBrowserSurface("storage", url("/first")); var visible by mutableStateOf(true)
        show(surface, { visible }); loaded(surface, "First")
        compose.runOnIdle { surface.load(url("/storage")) }; loaded(surface, "Stored")
        assertTrue(surface.state.value.canGoBack)
        lateinit var previous: WebView
        compose.runOnIdle { previous = web(); visible = false }; compose.waitForIdle()
        compose.runOnIdle { visible = true }; loaded(surface, "Stored")
        compose.runOnIdle { assertNotSame(previous, web()) }
        assertFalse(surface.state.value.canGoBack)
        assertEquals("\"present\"", js("localStorage.getItem('cmux-browser-fixture')"))
        assertTrue(js("document.cookie").contains("cmux_fixture=present"))
        painted("local-browser-restored", Color.GREEN)
    }

    @Test fun redirectKeepsTypedAddressAndGoDismissesTheKeyboard() {
        val surface = LocalBrowserSurface("editing", url("/first")); show(surface); loaded(surface, "First")
        compose.runOnIdle { surface.load(url("/redirect")) }
        compose.onNodeWithTag("LocalBrowserAddress").performTextReplacement(url("/popup"))
        loaded(surface, "Second")
        compose.onNodeWithTag("LocalBrowserAddress").assertTextEquals(url("/popup"))
        compose.onNodeWithTag("LocalBrowserAddress").performImeAction(); loaded(surface, "Popup")
        compose.onNodeWithTag("LocalBrowserAddress").assertIsNotFocused()
        assertEquals(1, requests.count { it.path == "/popup" })
    }

    @Test fun stopAndNetworkErrorRecoverWithoutPrivilegedSettings() {
        val surface = LocalBrowserSurface("errors", url("/first")); show(surface); loaded(surface, "First")
        compose.runOnIdle { surface.load(url("/slow")) }
        compose.waitUntil(5000) { surface.state.value.loading }
        compose.onNodeWithContentDescription("Stop loading").performClick()
        compose.waitUntil(5000) { !surface.state.value.loading }
        compose.runOnIdle { surface.load(url("/retry")) }
        compose.waitUntil(10000) { surface.state.value.error != null }
        retryFails = false
        compose.onNodeWithText("Retry").performClick(); loaded(surface, "Recovered")
        assertNull(surface.state.value.error); painted("local-browser-recovered", Color.GREEN)
        compose.runOnIdle {
            assertFalse(web().settings.allowFileAccess); assertFalse(web().settings.allowContentAccess)
            assertFalse(web().settings.supportMultipleWindows())
        }
        assertEquals("\"undefined\"", js("typeof Android"))
    }

    @Test fun cancelledFileSelectionCannotLeakToTheNextSurface() {
        val selection = LocalBrowserFileSelection(); val old = Any(); val next = Any()
        val received = mutableListOf<Array<Uri>?>()
        assertTrue(selection.begin(old, false, ValueCallback { received += it }))
        selection.cancel(old); assertEquals(1, received.size); assertNull(received.single())
        assertFalse(selection.begin(next, true, ValueCallback { fail("An old result was delivered") }))
        selection.finish(arrayOf(Uri.parse("content://fixture/old")))
        assertTrue(selection.begin(next, true, ValueCallback { received += it }))
        selection.finish(arrayOf(Uri.parse("file:///private"), Uri.parse("content://fixture/new"), Uri.parse("content://fixture/new")))
        assertEquals(listOf("content://fixture/new"), received.last()!!.map { it.toString() })
        val recreated = LocalBrowserFileSelection(inFlight = true)
        assertFalse(recreated.begin(Any(), false, ValueCallback { fail("Stale Activity result") }))
        recreated.finish(arrayOf(Uri.parse("content://fixture/old-activity")))
        assertTrue(recreated.begin(Any(), false, ValueCallback { received += it }))
        recreated.finish(arrayOf(Uri.parse("content://fixture/one"), Uri.parse("content://fixture/two")))
        assertEquals(1, received.last()!!.size)
    }

    @Test fun rendererTerminationOffersReloadWithANewWebView() {
        val surface = LocalBrowserSurface("renderer", url("/first")); show(surface); loaded(surface, "First")
        lateinit var old: WebView
        compose.runOnIdle { old = web(); assertTrue(checkNotNull(old.webViewRenderProcess).terminate()) }
        compose.waitUntil(5000) { surface.state.value.error?.contains("browser page stopped") == true }
        compose.onNodeWithText("Retry").performClick(); loaded(surface, "First")
        compose.runOnIdle { assertNotSame(old, web()) }
        assertNull(surface.state.value.error); painted("local-browser-renderer-recovered", Color.RED)
    }
}
