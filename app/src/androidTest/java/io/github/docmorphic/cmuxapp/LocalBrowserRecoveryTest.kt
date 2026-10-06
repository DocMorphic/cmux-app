package io.github.docmorphic.cmuxapp

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real WebView network errors, no account data or production computer. */
class LocalBrowserRecoveryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun unusedPort() = ServerSocket(0).use { it.localPort }
    private fun page(title: String) = MockResponse().setHeader("Content-Type", "text/html")
        .setBody("<title>$title</title><body style='background:#167f48;color:white;font:32px sans-serif'>$title</body>")
    private fun await(message: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < end) { if (condition()) return; Thread.sleep(50) }
        fail(message)
    }
    private fun withHost(url: String, prepare: (suspend (String?) -> Unit)?,
        run: (LocalBrowserSurface, LocalBrowserWebHost, (() -> Unit) -> Unit) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val surface = LocalBrowserSurface("recovery-fixture", url)
            lateinit var host: LocalBrowserWebHost
            scenario.onActivity { activity ->
                host = LocalBrowserWebHost(activity, surface, { _, _, _ -> false }, {}, prepare)
                activity.setContentView(host); host.foreground(true); host.applyPendingWork()
            }
            try { run(surface, host) { action -> instrumentation.runOnMainSync(action) } }
            finally { scenario.onActivity { host.release(); surface.close() } }
        }
    }

    @Test fun connectionFailureReadiesTheSameRouteAndLoadsTheOriginalGet() {
        val port = unusedPort(); val url = "http://127.0.0.1:$port/recover"
        val preparations = CopyOnWriteArrayList<String?>()
        MockWebServer().use { server ->
            server.enqueue(page("Recovered computer page"))
            withHost(url, { target ->
                preparations += target
                if (preparations.size == 2) withContext(Dispatchers.IO) { server.start(port) }
            }) { surface, _, _ ->
                await("GET never recovered") { surface.state.value.title == "Recovered computer page" && !surface.state.value.loading }
                assertNull(surface.state.value.error); assertEquals(listOf(url, url), preparations.toList())
                val request = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
                assertEquals("GET", request.method); assertEquals("/recover", request.path)
                val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "browser-recovery").apply { mkdirs() }
                val device = UiDevice.getInstance(instrumentation)
                await("Recovered document is not visibly painted") {
                    val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return@await false
                    try {
                        val color = bitmap.getPixel(bitmap.width / 2, bitmap.height * 3 / 4)
                        val green = android.graphics.Color.green(color)
                        if (green in 110..140 && android.graphics.Color.red(color) in 10..35) {
                            File(dir, "recovered.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            true
                        } else false
                    } finally { bitmap.recycle() }
                }
                device.dumpWindowHierarchy(File(dir, "recovered.xml"))
            }
        }
    }

    @Test fun repeatedConnectionFailureStopsAfterOneRetryAndManualReloadCanRecover() {
        val port = unusedPort(); val url = "http://127.0.0.1:$port/recover"
        val preparations = AtomicInteger()
        withHost(url, { preparations.incrementAndGet() }) { surface, host, main ->
            try {
                await("Persistent failure never settled") { surface.state.value.error != null && !surface.state.value.loading }
            } catch (failure: AssertionError) {
                throw AssertionError("preparations=${preparations.get()}, state=${surface.state.value}", failure)
            }
            assertEquals(2, preparations.get())
            MockWebServer().use { server ->
                server.enqueue(page("Manual recovery")); server.start(port)
                main { surface.request(LocalBrowserCommand.RELOAD); host.applyPendingWork() }
                await("Manual reload did not recover") { surface.state.value.title == "Manual recovery" && !surface.state.value.loading }
                assertNull(surface.state.value.error); assertEquals(3, preparations.get())
            }
        }
    }

    @Test fun aFailedPostIsNotAutomaticallyReplayed() {
        MockWebServer().use { server ->
            server.enqueue(page("Form page")); server.start()
            val postUrl = "http://127.0.0.1:${unusedPort()}/submit"
            val preparations = CopyOnWriteArrayList<String?>()
            withHost(server.url("/form").toString(), { preparations += it }) { surface, host, main ->
                await("Form did not load") { surface.state.value.title == "Form page" && !surface.state.value.loading }
                main { host.findViewWithTag<WebView>("LocalBrowserWebView").postUrl(postUrl, "message=once".toByteArray()) }
                await("POST failure never settled") { surface.state.value.error != null && !surface.state.value.loading }
                // onPageStarted refreshes route policy once; it must not prepare
                // again and convert the failed submission into an automatic GET.
                assertEquals(1, preparations.count { it == postUrl })
            }
        }
    }

    @Test fun stoppedOrSupersededRecoveryCannotReplaceTheNewDocument() {
        for (stop in listOf(true, false)) {
            val url = "http://127.0.0.1:${unusedPort()}/old"
            val recoveryStarted = CompletableDeferred<Unit>(); val releaseRecovery = CompletableDeferred<Unit>()
            val count = AtomicInteger()
            MockWebServer().use { server ->
                server.enqueue(page("New document")); server.start()
                val newUrl = server.url("/new").toString()
                withHost(url, { target ->
                    if (target == url && count.incrementAndGet() == 2) {
                        recoveryStarted.complete(Unit)
                        // Even a slow dependency that returns after cancellation
                        // cannot trigger the old loadUrl action.
                        withContext(NonCancellable) { releaseRecovery.await() }
                    }
                }) { surface, host, main ->
                    try {
                        await("Recovery did not begin") { recoveryStarted.isCompleted }
                        main {
                            if (stop) surface.request(LocalBrowserCommand.STOP)
                            else { surface.editAddress(newUrl); surface.submitAddress() }
                            host.applyPendingWork()
                        }
                        releaseRecovery.complete(Unit)
                        if (stop) {
                            instrumentation.waitForIdleSync()
                            assertFalse(surface.state.value.loading); assertEquals(2, count.get())
                            main { surface.editAddress(newUrl); surface.submitAddress(); host.applyPendingWork() }
                        }
                        await("Superseding document was lost") { surface.state.value.title == "New document" && !surface.state.value.loading }
                        assertNull(surface.state.value.error)
                        assertEquals(newUrl, surface.state.value.url)
                    } finally { releaseRecovery.complete(Unit) }
                }
            }
        }
    }
}
