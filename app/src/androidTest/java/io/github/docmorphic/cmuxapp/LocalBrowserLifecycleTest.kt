package io.github.docmorphic.cmuxapp

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.WebView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses emulator-only credentials and fixture files; never run against the physical Pixel. */
@OptIn(ExperimentalTestApi::class)
class LocalBrowserLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var store: NativeCredentialStore
    private lateinit var peer: NativeFixturePeer
    private lateinit var scenario: ActivityScenario<NativeLifecycleTestActivity>
    private val pages = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var document: Uri? = null
    private val filename = "cmux-browser-${UUID.randomUUID()}.txt"
    private val contents = "cmux Android browser upload fixture — 中 🚀\nSecond line.\n"

    @Before fun setup() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only account fixture" }
        store = NativeCredentialStore(context); store.clear()
        store.update { it.put("refresh_token", "local-browser-lifecycle-fixture") }
        store.rememberMac("cmux-ios://attach?v=2&r=100.64.0.1:58465", "fixture-mac", "Fixture Mac")
        peer = NativeFixturePeer()
        pages.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val title = if (request.path == "/upload") "Uploaded fixture" else "Lifecycle fixture"
                return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("""
                    <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title>
                    <style>body{background:#00ff00;margin:0}input,button{display:block;width:100%;height:70px;font-size:20px}</style>
                    <form method="post" action="/upload" enctype="multipart/form-data">
                    <input id="file" name="attachment" type="file" accept="text/plain"><button id="send">Upload selected file</button></form>
                """.trimIndent())
            }
        }
        pages.start()
        document = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename); put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
        })
        context.contentResolver.openOutputStream(checkNotNull(document))!!.use { it.write(contents.toByteArray()) }
        NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
            MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
        }
        scenario = ActivityScenario.launch(NativeLifecycleTestActivity::class.java)
        waitFor(hasText("Claude Code task"))
        compose.onNodeWithContentDescription("Actions for Claude Code task").performClick()
        compose.onNodeWithText("New browser").performClick(); waitFor(hasTestTag("LocalBrowserAddress"))
        compose.onNodeWithTag("LocalBrowserAddress").performTextReplacement(pages.url("/page").toString())
        compose.onNodeWithTag("LocalBrowserAddress").performImeAction(); waitFor(hasText("Lifecycle fixture ▾"))
        compose.waitUntil(5000) {
            var showing = true
            scenario.onActivity { showing = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            !showing
        }
    }
    @After fun cleanup() {
        if (::scenario.isInitialized) scenario.close()
        NativeLifecycleTestActivity.connector = null
        if (::peer.isInitialized) peer.close()
        if (::store.isInitialized) { document?.let { context.contentResolver.delete(it, null, null) }; pages.shutdown(); store.clear() }
    }
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(15000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    private fun js(expression: String): String {
        val ready = CountDownLatch(1); var value = ""
        scenario.onActivity { activity -> activity.window.decorView.findViewWithTag<WebView>("LocalBrowserWebView")
            .evaluateJavascript(expression) { value = it; ready.countDown() } }
        assertTrue(ready.await(5, TimeUnit.SECONDS)); return value
    }
    private fun touch(id: String) {
        val encoded = js("JSON.stringify((()=>{let r=document.getElementById('$id').getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2,innerWidth]})())")
        val point = JSONArray(JSONArray("[$encoded]").getString(0)); var width = 0
        scenario.onActivity { width = it.window.decorView.findViewWithTag<WebView>("LocalBrowserWebView").width }
        val scale = width / point.getDouble(2)
        compose.onNodeWithTag("LocalBrowserPage").performTouchInput {
            click(Offset((point.getDouble(0)*scale).toFloat(), (point.getDouble(1)*scale).toFloat()))
        }
    }
    private fun awaitPicker(): UiDevice {
        val device = UiDevice.getInstance(instrumentation)
        assertTrue("Android system document picker", device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 5000) ||
            device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 5000))
        return device
    }
    private fun chooseFile(device: UiDevice) {
        var row = device.wait(Until.findObject(By.text(filename)), 1500)
        if (row == null) {
            device.findObject(By.desc("Show roots"))?.click()
            val downloads = device.wait(Until.findObject(By.text("Downloads")), 3000)
            checkNotNull(downloads) { "Downloads root missing" }.click()
            row = device.wait(Until.findObject(By.text(filename)), 5000)
        }
        checkNotNull(row) { "Fixture file missing from system picker" }.click()
        waitFor(hasTestTag("LocalBrowserPage"))
    }
    private fun uploadAndVerify() {
        assertEquals("1", js("document.getElementById('file').files.length"))
        touch("send"); waitFor(hasText("Uploaded fixture ▾"))
        val request = requests.single { it.path == "/upload" }
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val body = request.body.clone().readUtf8()
        assertTrue(body.contains("filename=\"$filename\"")); assertTrue(body.contains(contents))
    }
    private fun screenshot(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }

    @Test fun systemPickerGrantsARealFileAndMultipartUploadSendsItsBytes() {
        touch("file"); val picker = awaitPicker(); screenshot("local-browser-system-picker")
        chooseFile(picker); uploadAndVerify(); screenshot("local-browser-uploaded")
    }

    @Test fun activityRecreationRestoresTheLocalTabWithAFreshWebView() {
        lateinit var old: WebView
        scenario.onActivity { old = it.window.decorView.findViewWithTag("LocalBrowserWebView") }
        val loads = requests.count { it.path == "/page" }
        scenario.recreate(); waitFor(hasTestTag("LocalBrowserPage"))
        compose.waitUntil(10000) { requests.count { it.path == "/page" } > loads }
        waitFor(hasText("Lifecycle fixture ▾"))
        scenario.onActivity { assertNotSame(old, it.window.decorView.findViewWithTag<WebView>("LocalBrowserWebView")) }
        compose.onNodeWithTag("LocalBrowserAddress").assertTextEquals(pages.url("/page").toString())
        screenshot("local-browser-activity-restored")
    }

    @Test fun pickerResultForADestroyedActivityIsDiscardedAndANewPickerStillWorks() {
        lateinit var old: NativeLifecycleTestActivity
        scenario.onActivity { old = it }
        touch("file"); val picker = awaitPicker()
        // Recreate behind the system picker; ActivityScenario.recreate waits for foreground.
        instrumentation.runOnMainSync { old.recreate() }
        chooseFile(picker); waitFor(hasText("Lifecycle fixture ▾"))
        scenario.onActivity { assertNotSame(old, it) }
        assertEquals("0", js("document.getElementById('file').files.length"))
        touch("file"); chooseFile(awaitPicker()); uploadAndVerify()
    }
}
