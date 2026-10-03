package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Opt-in real Chrome + published cmux-tui, with private HOME, profile and generated pages.
 * Defaults to loopback HTTP; CMUX_BROWSER_TEST_HTTP=0 isolates generated file pages.
 * No personal browser, cmux socket, account, SSH service or desktop tab is consulted. */
class SshCmuxBrowserProcessTest {
    private class Inspector(url: String) : AutoCloseable {
        private val http = OkHttpClient()
        private val ready = CompletableDeferred<Unit>()
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
        private var sequence = 0
        val networkEvents = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { ready.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val value = JSONObject(text)
                if (value.optString("method") in listOf("Network.loadingFailed", "Network.responseReceived", "Network.requestWillBeSent"))
                    networkEvents += value.toString()
                pending.remove(value.optInt("id"))?.complete(value)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready.completeExceptionally(t); pending.values.forEach { it.completeExceptionally(t) }; pending.clear()
            }
        })
        suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
            withTimeout(5000) { ready.await() }
            val id = ++sequence; val reply = CompletableDeferred<JSONObject>(); pending[id] = reply
            try {
                check(socket.send(JSONObject().put("id", id).put("method", method).put("params", params).toString()))
                val result = withTimeout(5000) { reply.await() }
                check(!result.has("error")) { result.toString() }
                return result.getJSONObject("result")
            } finally { pending.remove(id) }
        }
        suspend fun evaluate(expression: String): Any? {
            val result = request("Runtime.evaluate", JSONObject().put("expression", expression).put("returnByValue", true))
            check(!result.has("exceptionDetails")) { "Fixture evaluation failed: $result" }
            return result.getJSONObject("result").opt("value")
        }
        override fun close() { socket.cancel(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }

    @Test fun realProviderFramesGuardedPointerKeyboardAndNavigation() = runBlocking {
        val binary = System.getenv("CMUX_TUI_TEST_BINARY")
        val chromeBinary = System.getenv("CMUX_BROWSER_TEST_CHROME")
        assumeTrue("Explicit cmux-tui and Chrome fixture binaries required", binary?.startsWith('/') == true && chromeBinary?.startsWith('/') == true)
        require(File(binary!!).isFile && File(chromeBinary!!).isFile)
        val root = Files.createTempDirectory(File("/tmp").toPath(), "cb-").toFile()
        val session = "android-browser-${UUID.randomUUID().toString().take(8)}"
        val config = root.resolve("config.json").apply { writeText("{}") }
        val env = mutableMapOf("HOME" to root.path, "PATH" to "/usr/bin:/bin", "SHELL" to "/bin/sh",
            "TERM" to "xterm-256color", "LANG" to "en_US.UTF-8", "CMUX_TUI_CONFIG" to config.path)
        for ((key, dir) in mapOf("XDG_RUNTIME_DIR" to "run", "XDG_STATE_HOME" to "state", "XDG_CONFIG_HOME" to "config",
            "XDG_DATA_HOME" to "data", "TMPDIR" to "tmp")) env[key] = root.resolve(dir).apply { mkdirs() }.path
        fun spawn(args: List<String>) = ProcessBuilder(args).apply {
            directory(root); environment().clear(); environment().putAll(env)
            redirectError(ProcessBuilder.Redirect.appendTo(root.resolve("stderr.txt")))
        }.start()
        fun command(vararg args: String): JSONObject {
            val p = spawn(listOf(binary) + args)
            check(p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); "Fixture command timeout: $root" }
            val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
            check(p.exitValue() == 0) { "Fixture command failed: $text; $root" }
            return JSONObject(text)
        }
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        var relay: Process? = null
        var chrome: Process? = null
        var control: SshCmuxControl? = null
        var inspector: Inspector? = null
        var started = false
        var success = false
        fun page(path: String) = """
                            <!doctype html><title>CDP fixture $path</title>
                            <style>body{margin:0;background:rgb(18,95,55)}button,input{position:absolute;left:20px;width:200px;height:80px}button{top:20px}input{top:140px}</style>
                            <button onclick="window.clicks=(window.clicks||0)+1">Fixture click</button><input id="edit">
                            <script>document.addEventListener('keydown',e=>{window.lastKey={key:e.key,code:e.code,ctrl:e.ctrlKey,shift:e.shiftKey};if(e.ctrlKey||e.metaKey||/^F\d+$/.test(e.key))e.preventDefault()})</script>
                        """.trimIndent()
        val pageServer = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val connections = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
        val accepting = Thread {
            try { while (!pageServer.isClosed) {
                val connection = pageServer.accept(); connections += connection
                Thread {
                    try { connection.use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        val line = input.readLine() ?: return@use
                        val path = line.split(' ').getOrElse(1) { "/" }; requests += path
                        while (!input.readLine().isNullOrEmpty()) Unit
                        val body = page(path).toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    } } catch (_: java.io.IOException) { } finally { connections.remove(connection) }
                }.apply { isDaemon = true; start() }
            } } catch (_: java.net.SocketException) { }
        }.apply { isDaemon = true; start() }
        val httpFixture = System.getenv("CMUX_BROWSER_TEST_HTTP") != "0"
        for (name in listOf("start", "next", "health")) root.resolve("$name.html").writeText(page("/$name"))
        fun url(path: String) = if (httpFixture) "http://127.0.0.1:${pageServer.localPort}$path"
            else root.resolve("${path.removePrefix("/")}.html").toURI().toASCIIString()
        suspend fun until(label: String, predicate: suspend () -> Boolean) {
            try { withTimeout(15000) { while (!predicate()) delay(30) } }
            catch (failure: TimeoutCancellationException) { error("Timed out: $label; retained $root") }
        }
        try {
            val response = withContext(Dispatchers.IO) {
                java.net.URL(url("/health")).openConnection().apply { connectTimeout = 3000; readTimeout = 3000 }.getInputStream().use { it.readBytes() }
            }
            check(response.toString(Charsets.UTF_8).contains("CDP fixture /health"))
            val profile = root.resolve("chrome-profile")
            // A disposable test profile must not wait on, or use, the user's
            // macOS Safe Storage keychain. Chromium documents this test-only flag.
            chrome = spawn(listOf(chromeBinary, "--headless=new", "--disable-gpu", "--use-mock-keychain", "--no-first-run", "--no-default-browser-check",
                "--disable-background-networking", "--disable-background-timer-throttling", "--disable-backgrounding-occluded-windows", "--disable-renderer-backgrounding", "--no-proxy-server", "--disable-extensions", "--remote-debugging-address=127.0.0.1",
                "--remote-debugging-port=0", "--user-data-dir=${profile.path}") +
                (if (httpFixture) listOf("--log-net-log=${root.resolve("network.json").path}") else emptyList()) + url("/start"))
            val portFile = profile.resolve("DevToolsActivePort")
            until("Chrome endpoint") { check(chrome.isAlive) { "Private Chrome exited; $root" }; portFile.isFile }
            val port = portFile.readLines().first().toInt()
            val endpoint = "ws://127.0.0.1:$port" + portFile.readLines()[1]
            val targets = JSONArray(withContext(Dispatchers.IO) { java.net.URL("http://127.0.0.1:$port/json/list").readText() })
            val target = (0 until targets.length()).map(targets::getJSONObject).single { it.optString("type") == "page" }
            val observer = Inspector(target.getString("webSocketDebuggerUrl")); inspector = observer
            observer.request("Network.enable")
            println("Initial document: ${observer.request("Runtime.evaluate", JSONObject().put("expression", "({title:document.title,url:location.href,state:document.readyState})").put("returnByValue", true))}")
            command("server", "ensure", "--session", session, "--json"); started = true
            val process = spawn(listOf(binary, "relay", "--session", session)); relay = process
            val pipe = object : SshExecPipe {
                override val output = flow {
                    val bytes = ByteArray(8192)
                    while (true) { val n = process.inputStream.read(bytes); if (n < 0) break; emit(bytes.copyOf(n)) }
                }.flowOn(Dispatchers.IO)
                override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { process.outputStream.write(bytes); process.outputStream.flush() }
                override fun close() { process.destroy() }
            }
            val client = SshCmuxControl(pipe, scope, timeoutMillis = 10000); control = client
            client.handshake(session)
            check("browser-provider-v1" in client.server!!.capabilities)
            val startUrl = url("/start")
            val id = client.request("new-browser-tab", JSONObject().put("url", startUrl)).getInt("surface")
            val tab = client.listWorkspaces().tabs.single { it.surface == id }
            client.request("register-browser-provider", JSONObject().put("provider_id", "android-private-fixture")
                .put("endpoint", endpoint).put("authentication", "none")
                .put("targets", JSONArray().put(JSONObject().put("tab_id", tab.resource).put("target_id", target.getString("id")))))
            val events = mutableListOf<SshCmuxBrowserEvent>()
            val attached = client.attachBrowser(id, 80, 30, events::add)
            fun frames() = events.mapNotNull { when (it) {
                is SshCmuxBrowserEvent.Frame -> it.value
                is SshCmuxBrowserEvent.State -> it.value.frame
                else -> null
            } }
            suspend fun present(): SshCmuxBrowserFrame {
                var shown: SshCmuxBrowserFrame? = null
                until("live frame presentation; states=${events.filterIsInstance<SshCmuxBrowserEvent.State>().map { it.value.error }}") {
                    val frame = frames().lastOrNull()
                    if (frame != null && client.browserFrameDisplayed(attached, frame.sequence)) shown = frame
                    shown != null
                }
                return checkNotNull(shown)
            }
            until("provider live") { events.filterIsInstance<SshCmuxBrowserEvent.State>().lastOrNull()?.value?.status == SshCmuxBrowserStatus.LIVE }
            assertTrue(client.browserInput(attached, BrowserInput.Navigation("navigate", startUrl)))
            println("Fixture URL: $startUrl; target=${target.optString("url")}")
            var observedTitle: Any? = null
            until("fixture document") {
                val title = observer.evaluate("document.title")
                if (title != observedTitle) { observedTitle = title; println("Fixture title: $title; location=${observer.evaluate("location.href")}; requests=$requests") }
                title == "CDP fixture /start"
            }
            val first = present()
            val png = Base64.getDecoder().decode(first.png)
            root.resolve("frame.png").writeBytes(png)
            // Android's compile boot classpath omits java.desktop; the opt-in JVM
            // test runtime provides ImageIO without adding it to the Android app.
            val pixels = Class.forName("javax.imageio.ImageIO").getMethod("read", java.io.InputStream::class.java).invoke(null, png.inputStream())
            val width = pixels.javaClass.getMethod("getWidth").invoke(pixels) as Int
            val height = pixels.javaClass.getMethod("getHeight").invoke(pixels) as Int
            assertEquals(first.imageWidth, width); assertEquals(first.imageHeight, height)
            assertEquals(0x125f37, (pixels.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(pixels, width - 10, height - 10) as Int) and 0xffffff)
            assertTrue(client.browserInput(attached, BrowserInput.Click(80.0, 50.0)))
            until("pointer affects real page") { observer.evaluate("window.clicks") == 1 }
            assertTrue(client.browserInput(attached, BrowserInput.Click(80.0, 170.0)))
            assertTrue(client.browserInput(attached, BrowserInput.Text("abc")))
            until("text insertion") { observer.evaluate("edit.value") == "abc" }
            observer.evaluate("edit.setSelectionRange(0,0)")
            assertTrue("Android forward-delete token must be accepted", client.browserInput(attached, BrowserInput.Key("forward_delete")))
            until("forward delete") { observer.evaluate("edit.value") == "bc" }
            assertTrue("Control-letter must be accepted", client.browserInput(attached, BrowserInput.Key("a", listOf("control"))))
            until("Control-A CDP event") { (observer.evaluate("window.lastKey") as? JSONObject)?.let { it.optString("code") == "KeyA" && it.optBoolean("ctrl") } == true }
            assertTrue("Function key must be accepted", client.browserInput(attached, BrowserInput.Key("f6")))
            until("F6 CDP event") { (observer.evaluate("window.lastKey") as? JSONObject)?.optString("key") == "F6" }
            assertTrue(client.browserInput(attached, BrowserInput.Navigation("navigate", url("/next"))))
            until("navigation") { observer.evaluate("document.title") == "CDP fixture /next" }
            present()
            client.resizeBrowser(attached, 60, 20)
            assertFalse(client.browserInput(attached, BrowserInput.Click(80.0, 50.0)))
            present()
            assertTrue(client.browserInput(attached, BrowserInput.Click(80.0, 50.0)))
            until("resized pointer") { observer.evaluate("window.clicks") == 1 }
            client.detach(attached)
            assertFalse(client.closed)
            client.request("close-surface", JSONObject().put("surface", id))
            success = true
        } finally {
            if (!success) println("Fixture network diagnostics: ${inspector?.networkEvents}")
            inspector?.close(); control?.close(); scope.cancel()
            relay?.let { it.destroy(); if (!it.waitFor(2, TimeUnit.SECONDS)) it.destroyForcibly() }
            chrome?.let { it.destroy(); if (!it.waitFor(5, TimeUnit.SECONDS)) { it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) } }
            root.resolve("chrome-profile").deleteRecursively()
            pageServer.close(); connections.forEach { it.close() }; accepting.join(2000)
            if (started) {
                command("server", "stop", "--session", session, "--json")
                val preview = command("session", session, "reset-state", "--json")
                command("session", session, "reset-state", "--force", "--confirm-reset", preview.getString("confirm_reset"), "--json")
            }
            if (success) check(root.deleteRecursively()) else System.err.println("Retained private browser fixture: $root")
        }
    }
}
