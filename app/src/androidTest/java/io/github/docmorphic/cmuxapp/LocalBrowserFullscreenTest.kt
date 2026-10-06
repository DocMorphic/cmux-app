package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LocalBrowserFullscreenTest {
    @Test fun duplicateDismissalAndRendererExitReleaseOnlyTheirOwnWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val fullscreen = LocalBrowserFullscreen()
            try {
                lateinit var page: FrameLayout
                scenario.onActivity { activity -> page = FrameLayout(activity); activity.setContentView(page) }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val first = FrameLayout(activity)
                    var firstClosed = 0; var rejected = 0; var rendererClosed = 0
                    fullscreen.show(page, first, WebChromeClient.CustomViewCallback { firstClosed++ })
                    assertTrue(fullscreen.active); assertEquals(View.INVISIBLE, page.visibility); assertNotNull(first.parent)
                    fullscreen.show(page, FrameLayout(activity), WebChromeClient.CustomViewCallback { rejected++ })
                    assertEquals(1, rejected); assertTrue(fullscreen.active); assertEquals(0, firstClosed)
                    fullscreen.hide(); fullscreen.hide()
                    assertEquals(1, firstClosed); assertNull(first.parent); assertEquals(View.VISIBLE, page.visibility)
                    val second = FrameLayout(activity)
                    fullscreen.show(page, second, WebChromeClient.CustomViewCallback { rendererClosed++ })
                    fullscreen.hide(notifyPage = false)
                    assertFalse(fullscreen.active); assertNull(second.parent); assertEquals(0, rendererClosed)
                    val detachedPage = FrameLayout(activity)
                    fullscreen.show(detachedPage, FrameLayout(activity), WebChromeClient.CustomViewCallback { rejected++ })
                    assertEquals(2, rejected); assertFalse(fullscreen.active)
                }
            } finally { scenario.onActivity { fullscreen.hide() } }
        }
    }

    @Test fun actualWebVideoExpandsReturnsAndSurvivesActivityRecreationWithoutReload() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val evidence = File(context.getExternalFilesDir(null), "browser-fullscreen").apply { mkdirs() }
        val bytes = instrumentation.context.assets.open("media/tracks.mp4").use { it.readBytes() }
        val pageReads = AtomicInteger()
        fun await(message: String, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < end) { if (condition()) return; Thread.sleep(100) }
            fail(message)
        }
        fun find(selector: BySelector) = checkNotNull(device.wait(Until.findObject(selector), 15_000)) { "Missing $selector" }
        fun pixels(name: String) {
            await("Fullscreen video has no visible frame") {
                val file = File(evidence, "$name.png")
                device.takeScreenshot(file)
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.path) ?: return@await false
                try {
                    var gold = 0; var blue = 0
                    for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                        val pixel = bitmap.getPixel(x, y)
                        val r = android.graphics.Color.red(pixel); val g = android.graphics.Color.green(pixel)
                        val b = android.graphics.Color.blue(pixel)
                        if (r in 195..245 && g in 130..190 && b in 40..110) gold++
                        if (r in 10..60 && g in 50..110 && b in 100..160) blue++
                    }
                    gold > 1000 && blue > 1000
                } finally { bitmap.recycle() }
            }
        }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/video.mp4")
                    MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes))
                else {
                    pageReads.incrementAndGet()
                    MockResponse().setHeader("Content-Type", "text/html").setBody("""<!doctype html>
                        <meta name="viewport" content="width=device-width,initial-scale=1"><title>Video fixture</title>
                        <body style="background:#151515;color:white;font:24px sans-serif">
                        <p id="draft">Unsent video draft</p><video id="video" controls playsinline muted preload="auto"
                        src="/video.mp4" style="width:100%;height:240px"></video>
                        <button style="font:24px sans-serif" onclick="video.play();video.requestFullscreen()">Play fullscreen</button>
                        <script>window.unsentDraft=41;</script></body>""")
                }
            }
            server.start()
            ActivityScenario.launch<RetainedBrowserTestActivity>(Intent(context, RetainedBrowserTestActivity::class.java)
                .putExtra("url", server.url("/page").toString())).use { scenario ->
                lateinit var original: WebView
                fun web(block: (WebView) -> Unit) = scenario.onActivity { activity ->
                    block(activity.window.decorView.findViewWithTag("LocalBrowserWebView"))
                }
                fun js(script: String): String {
                    val done = CountDownLatch(1); var value = ""
                    web { it.evaluateJavascript(script) { result -> value = result; done.countDown() } }
                    assertTrue(done.await(5, TimeUnit.SECONDS)); return value
                }
                try {
                    find(By.text("Video fixture")); find(By.text("Play fullscreen"))
                    await("Inline video never prepared") { js("document.getElementById('video').readyState >= 2") == "true" }
                    web { original = it }
                    val reads = pageReads.get()
                    fun expand() {
                        find(By.text("Play fullscreen")).click()
                        await("Browser did not enter real fullscreen") { js("document.fullscreenElement === document.getElementById('video')") == "true" }
                        web { assertEquals(View.INVISIBLE, it.visibility) }
                    }
                    expand(); pixels("fullscreen")
                    await("Fullscreen video did not play") { js("!video.paused && video.currentTime > 0.25") == "true" }
                    device.pressBack()
                    await("Back did not return to the same page") { js("document.fullscreenElement === null && window.unsentDraft === 41") == "true" }
                    web { assertSame(original, it); assertEquals(View.VISIBLE, it.visibility) }
                    find(By.text("Play fullscreen")); assertEquals(reads, pageReads.get())
                    expand(); scenario.recreate()
                    find(By.text("Play fullscreen"))
                    await("Recreation left fullscreen active or lost the draft") { js("document.fullscreenElement === null && window.unsentDraft === 41") == "true" }
                    web { assertSame(original, it); assertEquals(View.VISIBLE, it.visibility) }
                    assertEquals(reads, pageReads.get())
                    pixels("recreated-inline")
                    device.dumpWindowHierarchy(File(evidence, "recreated-inline.xml"))
                } catch (failure: Throwable) {
                    device.takeScreenshot(File(evidence, "failure.png"))
                    device.dumpWindowHierarchy(File(evidence, "failure.xml"))
                    throw failure
                }
            }
        }
    }
}
