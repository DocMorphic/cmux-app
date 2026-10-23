package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Chromium shell/native bridge, with synthetic bytes behind the production fetch gate. */
class MarkdownConsentRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val approved = "https://example.com/approved.png"
    private val blocked = "https://example.com/blocked.png"
    private val generation = mutableIntStateOf(0)
    private val dark = mutableStateOf(false)
    private lateinit var web: WebView
    private lateinit var controller: MarkdownWebController
    private val loaders = CopyOnWriteArrayList<RecordingLoader>()
    private val errors = CopyOnWriteArrayList<String>()
    private val output get() = File(compose.activity.getExternalFilesDir(null), "markdown-consent").apply { mkdirs() }

    private class RecordingLoader : MarkdownImageLoader {
        val calls = CopyOnWriteArrayList<String>()
        @Volatile var closed = false
        private val png = ByteArrayOutputStream().use { bytes ->
            val image = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            image.eraseColor(Color.GREEN); image.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            image.recycle(); bytes.toByteArray()
        }
        override fun fetch(raw: String): MarkdownRemoteImages.Image {
            check(!closed); calls.add(raw); return MarkdownRemoteImages.Image(png, "image/png")
        }
        override fun close() { closed = true }
    }

    @Before fun emulatorOnly() { check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) }
    @After fun finish() { compose.activity.finish() }

    private fun show(markdown: String) {
        val shell = MarkdownAssets.shell(compose.activity)
        compose.setContent { CmuxTheme(darkTheme = dark.value) { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { key(generation.intValue) {
                val context = LocalContext.current
                val appearance = MarkdownWebTheme.resolve(dark.value, if (dark.value) 0xff0b0c0e.toInt() else Color.WHITE)
                val owner = remember {
                    val loader = RecordingLoader().also { loaders.add(it) }
                    MarkdownWebController(context, shell, markdown, 1f, { errors.add("renderer stopped") },
                        { errors.add(it) }, appearance = appearance, images = loader).also { controller = it }
                }
                DisposableEffect(owner) { onDispose { owner.close() } }
                AndroidView(factory = { owner.create().also { web = it } }, modifier = Modifier.fillMaxSize(),
                    update = { owner.update(markdown, 1f, appearance) })
            } }
        } } }
        waitJs("document.querySelector('h1')?.textContent === 'Native consent'")
    }

    private fun js(script: String): String? {
        val answer = AtomicReference<String?>(); val done = CountDownLatch(1)
        compose.runOnUiThread { web.evaluateJavascript(script) { answer.set(it); done.countDown() } }
        assertTrue("JavaScript timeout", done.await(5, TimeUnit.SECONDS)); return answer.get()
    }
    private fun waitJs(expression: String) {
        compose.waitUntil(15_000) { js(expression) == "true" }
    }
    private fun request(raw: String, main: Boolean = false, gesture: Boolean = false) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(raw)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = gesture
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
    private fun rejectImage(raw: String, target: WebView = web, previousClient: android.webkit.WebViewClient? = null) {
        val url = Uri.Builder().scheme("cmux-remote-image").authority("image").appendQueryParameter("url", raw).build()
        val client = AtomicReference<android.webkit.WebViewClient>()
        compose.runOnUiThread { client.set(previousClient ?: target.webViewClient) }
        val response = runBlocking(Dispatchers.IO) { client.get().shouldInterceptRequest(target, request(url.toString())) }
        assertEquals("Unapproved request: $raw", 403, response!!.statusCode)
    }
    private fun click(selector: String) {
        val quoted = JSONObject.quote(selector)
        js("document.querySelector($quoted).scrollIntoView({behavior:'instant',block:'center'}); true")
        val rect = JSONObject(js("(() => {const r=document.querySelector($quoted).getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,width:innerWidth};})()")!!)
        val origin = IntArray(2); var scale = 1.0
        compose.runOnUiThread { web.getLocationOnScreen(origin); scale = web.width / rect.getDouble("width") }
        assertTrue(device.click(origin[0] + (rect.getDouble("x") * scale).toInt(), origin[1] + (rect.getDouble("y") * scale).toInt()))
    }

    @Test fun nativeApprovalGatesActualImageBytesSurvivesThemeAndRetiresWithShell() {
        show("# Native consent\n\n<img src=\"$approved\" alt=\"Approved\">\n\n<img src=\"$blocked\" alt=\"Blocked\">")
        val first = loaders.single()
        rejectImage(approved); rejectImage(blocked); assertTrue(first.calls.isEmpty())
        // Bypass the trusted shell's consent map deliberately: native code must still refuse it.
        js("window.rogueFinished=false;const rogue=new Image();rogue.onerror=()=>window.rogueFinished=true;rogue.src='cmux-remote-image://image?url='+encodeURIComponent('$approved');window.rogue=rogue;true")
        waitJs("window.rogueFinished"); assertTrue(first.calls.isEmpty())
        click("[data-cmux-remote-action='load']")
        waitJs("document.querySelector('img[alt=Approved]')?.naturalWidth === 32")
        assertEquals(listOf(approved), first.calls.toList())
        rejectImage(blocked); rejectImage("$approved?different=1")
        assertEquals(listOf(approved), first.calls.toList())
        waitJs("document.querySelector('img[alt=Approved]').complete")
        val geometry = JSONObject(js("(() => {const r=document.querySelector('img[alt=Approved]').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,width:innerWidth};})()")!!)
        val origin = IntArray(2); var scale = 1.0
        compose.runOnUiThread { web.getLocationOnScreen(origin); scale = web.width / geometry.getDouble("width") }
        compose.waitUntil(5_000) {
            val frame = instrumentation.uiAutomation.takeScreenshot()
            try {
                val painted = frame.getPixel(origin[0] + (geometry.getDouble("x") * scale).toInt(), origin[1] + (geometry.getDouble("y") * scale).toInt()) == Color.GREEN
                if (painted) File(output, "approved-image.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                painted
            } finally { frame.recycle() }
        }
        val original = web
        val originalClient = AtomicReference<android.webkit.WebViewClient>()
        compose.runOnUiThread { originalClient.set(original.webViewClient) }
        js("window.consentDocument='same';true")
        for (next in listOf(true, false)) {
            compose.runOnIdle { dark.value = next }
            waitJs("matchMedia('(prefers-color-scheme: dark)').matches === $next && window.consentDocument === 'same' && document.querySelector('img[alt=Approved]')?.naturalWidth === 32")
            assertSame(original, web); assertTrue(first.calls.all { it == approved })
            rejectImage(blocked)
        }
        compose.runOnIdle { generation.intValue++ }
        waitJs("document.querySelector('h1')?.textContent === 'Native consent' && typeof window.consentDocument === 'undefined'")
        assertNotSame(original, web); assertTrue(first.closed)
        rejectImage(approved); rejectImage(blocked); rejectImage(approved, original, originalClient.get())
        assertTrue(loaders.last().calls.isEmpty())
        assertEquals("true", js("!document.querySelector('img[alt=Approved]').getAttribute('src')"))
        assertTrue(errors.toString(), errors.isEmpty())
        File(output, "approval.json").writeText(JSONObject().put("firstShellFetches", org.json.JSONArray(first.calls))
            .put("freshShellFetches", loaders.last().calls.size).put("oldLoaderClosed", first.closed)
            .put("unapprovedAndVariantRequestsRejected", true).put("paintedImage", true).toString(2))
    }

    @Test fun navigationAndPlaceholderOpenUseHostOnlyForActivatedLinks() {
        val intents = CopyOnWriteArrayList<String>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                intents.add(intent.dataString.orEmpty())
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            show("# Native consent\n\n[Open user link](https://example.com/user)\n\n<img src=\"$blocked\" alt=\"Blocked\">\n\n## Destination")
            js("window.navigationDocument='retained';location.assign('https://example.com/script');true")
            Thread.sleep(300)
            assertEquals("true", js("window.navigationDocument === 'retained' && document.querySelector('h1').textContent === 'Native consent'"))
            assertTrue(intents.isEmpty())
            js("window.open('https://example.com/script-popup');true"); Thread.sleep(300)
            assertTrue(intents.isEmpty())
            // The sanitizer strips arbitrary target attributes. Add one in the test
            // to exercise the native callback for a user-activated new-window link too.
            js("document.querySelector('a[href=\"https://example.com/user\"]').target='_blank';true")
            click("a[href='https://example.com/user']")
            compose.waitUntil(5_000) { intents.size == 1 }
            assertEquals("https://example.com/user", intents.single())
            // The placeholder opens through its explicit native callback, without fetching it.
            js("Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Open image URL').click();true")
            compose.waitUntil(5_000) { intents.size == 2 }
            assertEquals(blocked, intents.last()); assertTrue(loaders.single().calls.isEmpty())
            js("CmuxMarkdownBridge.postMessage(JSON.stringify({action:'openRemoteImage',url:'https://user:pw@example.com/'}));true")
            js("CmuxMarkdownBridge.postMessage(JSON.stringify({action:'openRemoteImage',url:'javascript:alert(1)'}));true")
            js("location.hash='destination';true")
            waitJs("location.hash === '#destination'")
            assertEquals(2, intents.size)
            compose.runOnUiThread {
                assertTrue(web.webViewClient.shouldOverrideUrlLoading(web, request("about:blank#subframe", main = false)))
                assertTrue(web.webViewClient.shouldOverrideUrlLoading(web, request("data:text/html,other", main = true)))
            }
            assertTrue(errors.toString(), errors.isEmpty())
            File(output, "navigation.json").writeText(JSONObject().put("opened", org.json.JSONArray(intents))
                .put("scriptNavigationRetainedDocument", true).put("scriptPopupOpened", false)
                .put("mainFragmentRetained", true).put("subframeFragmentAndExtraDataNavigationBlocked", true).toString(2))
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
