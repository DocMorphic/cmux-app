package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.os.*
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Generated pages and certificates only. No account fixtures, real Macs, or physical phones. */
class RoutedBrowserEnvironmentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var networkDiagnostics: () -> String = { "" }
    private fun id() = UUID.randomUUID().toString().replace("-", "")
    private fun guard() { check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Disposable emulator required" } }
    private suspend fun until(label: String = "browser condition", predicate: suspend () -> Boolean) {
        try { withTimeout(20_000) { while (!predicate()) delay(100) } }
        catch (failure: TimeoutCancellationException) { throw AssertionError("Timed out: $label", failure) }
    }

    private inner class Browser : AutoCloseable {
        private val replies = ConcurrentHashMap<Int, CompletableDeferred<String>>()
        private val serial = AtomicInteger()
        private val started = CompletableDeferred<Messenger>()
        private val dead = CompletableDeferred<Unit>()
        private lateinit var remote: Messenger
        private val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == 0) {
                assertNotEquals(Process.myPid(), message.arg1)
                remote = message.replyTo
                remote.binder.linkToDeath({ dead.complete(Unit) }, 0)
                started.complete(remote)
            } else replies.remove(message.arg1)?.complete(message.data.getString("value")!!)
            true
        })
        suspend fun start(binding: RoutedBrowserBinding) {
            context.startActivity(Intent(context, RoutedBrowserTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("reply", receiver))
            withTimeout(15_000) { started.await() }
            assertEquals("ready", configure(binding))
        }
        suspend fun configure(binding: RoutedBrowserBinding) = request(1, Bundle().apply {
            putString("storage", binding.storageId); putInt("port", binding.proxyPort)
        })
        suspend fun load(url: String, certificate: String? = null) = request(2, Bundle().apply {
            putString("url", url); putString("certificate", certificate)
        })
        suspend fun js(script: String) = request(3, Bundle().apply { putString("script", script) })
        suspend fun flush() = request(4)
        suspend fun error() = request(6)
        suspend fun stop() {
            if (!dead.isCompleted && ::remote.isInitialized) {
                runCatching { request(5) }
                withTimeout(10_000) { dead.await() }
            }
        }
        private suspend fun request(kind: Int, args: Bundle = Bundle()): String {
            val ticket = serial.incrementAndGet()
            val answer = CompletableDeferred<String>(); replies[ticket] = answer
            try {
                remote.send(Message.obtain(null, kind).apply { arg1 = ticket; data = args; replyTo = receiver })
                return withTimeout(15_000) { answer.await() }
            } finally { replies.remove(ticket) }
        }
        override fun close() { runBlocking { stop() } }
        suspend fun report(): JSONObject {
            var result: JSONObject? = null
            try { until("page network/storage report") {
                val value = js("JSON.stringify(window.fixtureReport || null)")
                val decoded = JSONArray("[$value]").opt(0)
                result = (decoded as? String)?.takeIf { it.startsWith('{') }?.let(::JSONObject)
                result != null
            } } catch (failure: AssertionError) {
                throw AssertionError("Browser report: " + js("JSON.stringify({url:location.href,title:document.title,stage:window.fixtureStage,body:document.body.innerText})") + "; error=" + error() + "; " + networkDiagnostics(), failure)
            }
            return result!!
        }
        suspend fun title(expected: String) { until("title $expected") { js("document.title") == JSONObject.quote(expected) } }
    }

    private class Site(private val owner: String) : AutoCloseable {
        val server = MockWebServer()
        val paths = CopyOnWriteArrayList<String>()
        init { server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.substringBefore('?'); paths += path
                val response = MockResponse().setHeader("Cache-Control", "no-store")
                return when (path) {
                    "/redirect" -> response.setResponseCode(302).setHeader("Location", "/page")
                    "/page" -> response.setHeader("Content-Type", "text/html").setBody("""<!doctype html>
                        <meta name="viewport" content="width=device-width,initial-scale=1"><title>$owner</title>
                        <style>body{background:#13354a;color:white;font:24px sans-serif}</style><h1>Computer $owner</h1><pre id="result">Checking network…</pre>
                        <script>(async()=>{try {
                          window.fixtureStage='before';
                          const before={cookie:document.cookie,storage:localStorage.getItem('owner'),
                            registrations:(await navigator.serviceWorker.getRegistrations()).length,caches:await caches.keys()};
                          localStorage.setItem('owner','$owner');document.cookie='owner=$owner; Path=/; SameSite=Lax';
                          await caches.open('owner-$owner');
                          window.fixtureStage='api';const api=await (await fetch('/api')).text();
                          window.fixtureStage='socket';const ws=await new Promise((resolve,reject)=>{const s=new WebSocket('ws://'+location.host+'/socket');
                            s.onmessage=e=>{resolve(e.data);s.close()};s.onerror=()=>reject(Error('websocket'))});
                          window.fixtureStage='register';await navigator.serviceWorker.register('/sw.js');
                          window.fixtureStage='ready';await navigator.serviceWorker.ready;
                          window.fixtureStage='control';
                          if(!navigator.serviceWorker.controller)await new Promise(resolve=>navigator.serviceWorker.addEventListener('controllerchange',resolve,{once:true}));
                          window.fixtureStage='worker';const worker=await (await fetch('/worker-probe')).text();
                          window.fixtureReport={owner:'$owner',before,api,ws,worker};
                        }catch(error){window.fixtureReport={error:String(error)}}
                        document.querySelector('#result').textContent=JSON.stringify(window.fixtureReport,null,2)})();</script>""")
                    "/api", "/worker-data" -> response.setBody(owner)
                    "/sw.js" -> response.setHeader("Content-Type", "application/javascript").setBody("""
                        self.addEventListener('install',e=>e.waitUntil(self.skipWaiting()));
                        self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));
                        self.addEventListener('fetch',e=>{if(new URL(e.request.url).pathname==='/worker-probe')e.respondWith(fetch('/worker-data'))});
                    """)
                    "/socket" -> response.withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(owner) }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                    })
                    else -> response.setHeader("Content-Type", "text/html").setBody("<!doctype html><title>$owner simple</title><h1>$owner</h1>")
                }
            }
        }; server.start() }
        override fun close() { server.shutdown() }
    }

    @Test fun processProxyRoutesAllBrowserTrafficAndIsolatesCookiesWorkersAndAppSessions() = runBlocking<Unit> {
        guard()
        Site("PHONE").use { phone -> Site("A").use { a -> Site("B").use { b ->
            val seenA = CopyOnWriteArrayList<String>(); val seenB = CopyOnWriteArrayList<String>()
            fun backend(site: Site, seen: MutableList<String>) = BrowserTunnelBackend { host, port, use ->
                seen += "$host:$port"
                NioBrowserSocket.direct.use("127.0.0.1", site.server.port, use)
            }
            BrowserSocksProxy.start(backend(a, seenA)).use { proxyA -> BrowserSocksProxy.start(backend(b, seenB)).use { proxyB ->
                networkDiagnostics = { "A.routes=$seenA A.paths=${a.paths} A.active=${proxyA.activeConnectionCount} A.listening=${proxyA.isListening}; B.routes=$seenB B.paths=${b.paths} B.active=${proxyB.activeConnectionCount} B.listening=${proxyB.isListening}; phone=${phone.paths}" }
                val ownerA = RoutedBrowserBinding(id(), proxyA.port)
                val ownerB = RoutedBrowserBinding(id(), proxyB.port)
                val url = "http://localhost:${phone.server.port}/redirect"
                assertTrue(runCatching { RoutedBrowserEnvironment.prepare(context, ownerA) }.isFailure)
                Browser().use { browser ->
                    browser.start(ownerA); browser.load(url)
                    assertReport(browser.report(), "A", previous = null)
                    assertEquals("ready", browser.configure(ownerA))
                    assertTrue(browser.configure(ownerB).startsWith("rejected:"))
                    for (host in listOf("127.0.0.1", "[::1]", "app.localhost", "mac-only.invalid")) {
                        browser.load("http://$host:${phone.server.port}/simple")
                        browser.title("A simple")
                    }
                    assertTrue(a.paths.containsAll(listOf("/redirect", "/page", "/api", "/socket", "/sw.js", "/worker-data")))
                    assertEquals(emptyList<String>(), phone.paths)
                    browser.flush(); browser.stop()
                }
                Browser().use { browser ->
                    browser.start(ownerB); browser.load(url)
                    assertReport(browser.report(), "B", previous = null)
                    browser.flush(); browser.stop()
                }
                Browser().use { browser ->
                    browser.start(ownerA); browser.load(url)
                    assertReport(browser.report(), "A", previous = "A")
                    browser.flush(); browser.stop()
                }
                Browser().use { browser ->
                    browser.start(RoutedBrowserBinding(id(), proxyA.port)); browser.load(url)
                    assertReport(browser.report(), "A", previous = null)
                    // A stopped owner proxy must not turn into phone-local browsing.
                    proxyA.close()
                    browser.load("http://localhost:${phone.server.port}/unavailable")
                    until { browser.error().contains("/unavailable") }
                    assertEquals(emptyList<String>(), phone.paths)
                    browser.stop()
                }
                assertTrue(seenA.any { it.startsWith("::1:") || it.startsWith("0:0:0:0:0:0:0:1:") })
                assertTrue(seenA.any { it.startsWith("mac-only.invalid:") })
                assertTrue(seenB.all { it == "localhost:${phone.server.port}" })
                // Main-process artifact WebViews still use their own network/storage.
                var main: WebView? = null
                val loaded = CompletableDeferred<Unit>()
                instrumentation.runOnMainSync {
                    main = WebView(context).apply {
                        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) { loaded.complete(Unit) }
                        }
                        loadUrl("http://localhost:${phone.server.port}/main")
                    }
                }
                try {
                    withTimeout(10_000) { loaded.await() }
                    val value = CompletableDeferred<String>()
                    instrumentation.runOnMainSync { main!!.evaluateJavascript("JSON.stringify({title:document.title,cookie:document.cookie,storage:localStorage.getItem('owner')})") { value.complete(it) } }
                    val report = JSONObject(JSONArray("[${withTimeout(5_000) { value.await() }}]").getString(0))
                    assertEquals("PHONE simple", report.getString("title"))
                    // Existing phone-only fixture cookies may be present; no routed owner's cookie may appear.
                    assertFalse(report.getString("cookie").split(';').any { it.trim().startsWith("owner=") })
                    assertTrue(report.isNull("storage"))
                    assertTrue("/main" in phone.paths)
                } finally { instrumentation.runOnMainSync { main?.destroy() } }
            } }
        } } }
    }
    private fun assertReport(report: JSONObject, expected: String, previous: String?) {
        assertFalse(report.toString(), report.has("error"))
        for (key in listOf("owner", "api", "ws", "worker")) assertEquals(key, expected, report.getString(key))
        val before = report.getJSONObject("before")
        if (previous == null) {
            assertEquals("", before.getString("cookie")); assertTrue(before.isNull("storage"))
            assertEquals(0, before.getInt("registrations")); assertEquals(0, before.getJSONArray("caches").length())
        } else {
            assertEquals("owner=$previous", before.getString("cookie")); assertEquals(previous, before.getString("storage"))
            assertEquals(1, before.getInt("registrations")); assertEquals("owner-$previous", before.getJSONArray("caches").getString(0))
        }
    }

    @Test fun httpsBytesUseSocksTunnelWithoutReplacingWebViewTlsAndRejectAnUnpinnedCertificate() = runBlocking<Unit> {
        guard()
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val pin = MessageDigest.getInstance("SHA-256").digest(certificate.certificate.encoded).joinToString("") { "%02x".format(it) }
        MockWebServer().use { server ->
            val paths = CopyOnWriteArrayList<String>()
            server.useHttps(tls.sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths += request.path!!
                    return MockResponse().setHeader("Cache-Control", "no-store")
                        .setHeader("Content-Type", "text/html").setBody("<!doctype html><title>Encrypted fixture</title><h1>TLS</h1>")
                }
            }
            server.start()
            val requested = CopyOnWriteArrayList<String>()
            BrowserSocksProxy.start(BrowserTunnelBackend { host, port, use ->
                requested += "$host:$port"; NioBrowserSocket.direct.use("127.0.0.1", server.port, use)
            }).use { proxy ->
                Browser().use { browser ->
                    browser.start(RoutedBrowserBinding(id(), proxy.port))
                    browser.load("https://localhost:34443/fixture", pin)
                    browser.title("Encrypted fixture")
                    assertTrue(requested.contains("localhost:34443"))
                    browser.stop()
                }
                Browser().use { browser ->
                    browser.start(RoutedBrowserBinding(id(), proxy.port))
                    browser.load("https://localhost:34443/rejected")
                    until("untrusted certificate rejected") { browser.error().contains("/rejected") }
                    assertFalse(paths.any { it.startsWith("/rejected") })
                    assertNotEquals(JSONObject.quote("Encrypted fixture"), browser.js("document.title"))
                    browser.stop()
                }
            }
        }
    }
}
