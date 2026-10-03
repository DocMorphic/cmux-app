package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in published cmux-tui binary. A short private HOME/runtime directory
 * isolates sockets, config AND the macOS Application Support terminal hosts.
 * Never connects to a personal owner or installs a binary in the user's PATH. */
class SshCmuxProcessTest {
    @Test fun realRelaySeedsStreamsResizesDetachesAndReattachesDurableTerminal() = runBlocking {
        val binary = System.getenv("CMUX_TUI_TEST_BINARY")
        assumeTrue("Opt-in cmux-tui binary not configured", binary?.startsWith('/') == true)
        require(File(binary!!).isFile)
        val root = Files.createTempDirectory(File("/tmp").toPath(), "ct-").toFile()
        val session = "android-${UUID.randomUUID().toString().take(8)}"
        val config = root.resolve("config.json").apply { writeText("{}") }
        val environment = mutableMapOf("HOME" to root.absolutePath, "PATH" to "/usr/bin:/bin", "SHELL" to "/bin/sh",
            "TERM" to "xterm-256color", "LANG" to "en_US.UTF-8", "CMUX_TUI_CONFIG" to config.absolutePath)
        for ((name, dir) in mapOf("XDG_RUNTIME_DIR" to "run", "XDG_STATE_HOME" to "state", "XDG_CONFIG_HOME" to "config",
            "XDG_DATA_HOME" to "data", "TMPDIR" to "tmp")) environment[name] = root.resolve(dir).apply { mkdirs() }.absolutePath
        fun spawn(args: List<String>) = ProcessBuilder(args).apply {
            directory(root); environment().clear(); environment().putAll(environment)
            redirectError(ProcessBuilder.Redirect.appendTo(root.resolve("stderr.txt")))
        }.start()
        fun process(vararg args: String) = spawn(listOf(binary) + args)
        fun exec(vararg args: String): JSONObject {
            val running = process(*args)
            check(running.waitFor(10, TimeUnit.SECONDS)) { running.destroyForcibly(); "Fixture command timed out; retained $root" }
            val result = running.inputStream.readBytes().toString(Charsets.UTF_8)
            check(running.exitValue() == 0) { "Fixture command failed: $result; retained $root" }
            return JSONObject(result)
        }
        val owner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val relays = mutableListOf<Process>()
        fun pipe(running: Process): SshExecPipe {
            relays += running
            return object : SshExecPipe {
                override val output = flow {
                    val bytes = ByteArray(8192)
                    while (true) { val n = running.inputStream.read(bytes); if (n < 0) break; emit(bytes.copyOf(n)) }
                }.flowOn(Dispatchers.IO)
                override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
                    running.outputStream.write(bytes); running.outputStream.flush()
                }
                override fun close() { running.destroy() }
            }
        }
        fun relay() = SshCmuxControl(pipe(process("relay", "--session", session)), owner, timeoutMillis = 5000)
        val remote = SshCmuxRemote({ command -> withContext(Dispatchers.IO) {
            val running = spawn(listOf("/bin/sh", "-c", command))
            check(running.waitFor(10, TimeUnit.SECONDS)) { running.destroyForcibly(); "Discovery command timed out" }
            SshExecResult(running.inputStream.readBytes(), byteArrayOf(), running.exitValue())
        } }, { command -> pipe(spawn(listOf("/bin/sh", "-c", command))) }, owner)
        fun objects(array: JSONArray) = (0 until array.length()).map(array::getJSONObject)
        fun tabs(tree: JSONObject) = objects(tree.getJSONArray("workspaces")).flatMap { workspace ->
            objects(workspace.getJSONArray("screens")).flatMap { screen ->
                objects(screen.getJSONArray("panes")).flatMap { pane -> objects(pane.getJSONArray("tabs")) }
            }
        }
        suspend fun closeFixtureTerminals(control: SshCmuxControl) {
            // Resolve the exact private session. Never fall back to the first
            // session when its name is missing or ambiguous.
            val machines = control.requestV2("machine.list", JSONObject()) as JSONArray
            val machine = objects(machines).single().getString("id")
            val sessions = control.requestV2("session.list", JSONObject().put("machine", machine)) as JSONArray
            val resourceSession = objects(sessions).single { it.optString("name") == session }.getString("id")
            val terminals = tabs(control.request("list-workspaces")).mapNotNull { it.opt("terminal_resource_id") as? String }.distinct()
            for (terminal in terminals) control.requestV2("terminal.close", JSONObject().put("machine", machine)
                .put("session", resourceSession).put("terminal", terminal), UUID.randomUUID().toString())
        }
        var client: SshCmuxControl? = null
        try {
            // The exact production locator must prefer this private HOME.
            // Symlinking avoids a second 44 MB fixture binary on disk.
            val localBin = root.resolve(".local/bin").apply { mkdirs() }
            Files.createSymbolicLink(localBin.resolve("cmux-tui").toPath(), File(binary).toPath())
            val located = checkNotNull(remote.locateBinary())
            assertEquals(localBin.resolve("cmux-tui").absolutePath, located)
            assertTrue(remote.listSockets().none { it.serves(session) })
            exec("server", "ensure", "--session", session, "--json")
            withTimeout(20000) {
                val socket = remote.listSockets().single { it.serves(session) }
                assertTrue(socket.path.startsWith(root.absolutePath + "/"))
                val control = remote.connect(located, socket); client = control
                val info = checkNotNull(control.server)
                assertTrue(info.protocol >= 11)
                assertTrue("view-attachment-lease-v1" in info.capabilities)
                val provider = SshCmuxProvider.open(control, owner) { true }
                val key = provider.createWorkspace("Android private fixture", listOf("/bin/cat"))
                val surface = provider.state.value.tree!!.tabs.single().surface
                val before = tabs(control.request("list-workspaces")).single()
                val terminal = before.getString("terminal_resource_id")
                assertTrue(terminal.startsWith("term_"))
                val inventory = control.listWorkspaces()
                assertEquals("Android private fixture", inventory.workspaces.single().name)
                val selection = SshCmuxSelection.capture(session, inventory, inventory.workspaces.single(), inventory.tabs.single())
                val events = mutableListOf<SshCmuxEvent>()
                val attachment = control.attach(surface, 80, 24, events::add)
                assertNotNull(attachment.lease)
                assertTrue(events.first() is SshCmuxEvent.Snapshot)
                control.send(attachment, "live λ 中\n".toByteArray())
                fun live() = events.filterIsInstance<SshCmuxEvent.Output>().flatMap { it.bytes.toList() }.toByteArray().toString(Charsets.UTF_8)
                while (!live().contains("live λ 中")) delay(10)
                assertEquals("applied", control.resize(attachment, 72, 18))
                while (events.filterIsInstance<SshCmuxEvent.Snapshot>().none { it.resized && it.columns == 72 && it.rows == 18 }) delay(10)
                val resized = tabs(control.request("list-workspaces")).single().getJSONObject("size")
                assertEquals(72, resized.getInt("cols")); assertEquals(18, resized.getInt("rows"))
                // Tests the lease acknowledgment only. Published 0.13.4 does
                // not restore another client's dimensions on release.
                assertTrue(control.releaseGeometry(attachment))
                control.claimGeometry(attachment, 76, 22)
                control.detach(attachment)
                assertEquals(1, events.count { it is SshCmuxEvent.Ended })
                assertFalse(control.closed)
                assertTrue(runCatching { control.send(attachment, "must-not-send\n".toByteArray()) }.isFailure)
                control.close()

                // Terminal hosts and topology survive an owner restart, while
                // numeric view IDs and the owner's generation are transient.
                exec("server", "stop", "--session", session, "--json")
                exec("server", "ensure", "--session", session, "--json")
                val newSocket = remote.listSockets().single { it.serves(session) }
                val next = remote.connect(located, newSocket); client = next
                assertNotEquals(info.generation, next.server?.generation)
                val nextProvider = SshCmuxProvider.open(next, owner) { true }
                val preserved = checkNotNull(selection.resolve(session, next.listWorkspaces())).second
                assertEquals(terminal, preserved.terminal)
                val restored = mutableListOf<SshCmuxEvent>()
                val reattached = next.attach(preserved.surface, 80, 24, restored::add)
                assertTrue(restored.filterIsInstance<SshCmuxEvent.Snapshot>().first().bytes.toString(Charsets.UTF_8).contains("live λ 中"))
                assertFalse(restored.filterIsInstance<SshCmuxEvent.Snapshot>().first().bytes.toString(Charsets.UTF_8).contains("must-not-send"))
                val confirmed = nextProvider.state.value.tree!!.workspaces.single()
                nextProvider.newTerminal(confirmed, listOf("/bin/cat"))
                assertEquals(2, nextProvider.state.value.tree!!.tabs.size)
                assertTrue(runCatching { nextProvider.endWorkspace(confirmed) }.isFailure)
                assertEquals(2, next.listWorkspaces().tabs.count { !it.dead })
                nextProvider.endWorkspace(nextProvider.state.value.tree!!.workspaces.single())
                while (restored.none { it is SshCmuxEvent.Ended }) delay(10)
                assertTrue(reattached.ended)
                assertEquals(0, next.request("list-workspaces").getJSONArray("workspaces").length())
                assertTrue(nextProvider.state.value.tree!!.workspaces.isEmpty())
                // A real attach-only browser tab can exist without a CDP provider.
                // Verify its protocol/lease lifecycle; this is not pixel/render proof.
                val browserId = next.request("new-browser-tab", JSONObject().put("url", "http://127.0.0.1:9/")).getInt("surface")
                assertTrue(next.listWorkspaces().tabs.single { it.surface == browserId }.isBrowser)
                val browserEvents = mutableListOf<SshCmuxBrowserEvent>()
                val browser = next.attachBrowser(browserId, 80, 24, browserEvents::add)
                assertTrue(browserEvents.first() is SshCmuxBrowserEvent.State)
                assertNotNull(browser.lease); assertNull(browser.pointer.token)
                val cell = next.browserCellPixels(); assertTrue(cell.first > 0 && cell.second > 0)
                next.resizeBrowser(browser, 72, 20)
                next.detach(browser)
                assertEquals(1, browserEvents.count { it is SshCmuxBrowserEvent.Ended })
                assertFalse(next.closed)
                next.request("close-surface", JSONObject().put("surface", browserId))
                Unit
            }
        } finally {
            client?.close()
            try {
                // An owner stop alone leaves durable terminal hosts running.
                // Close exact fixture terminals first; reset-state refuses to
                // erase live hosts, so a failed cleanup retains its evidence.
                exec("server", "ensure", "--session", session, "--json")
                val cleanup = relay()
                try { withTimeout(10000) { cleanup.handshake(session); closeFixtureTerminals(cleanup) } }
                finally { cleanup.close() }
                exec("server", "stop", "--session", session, "--json")
                val preview = exec("session", session, "reset-state", "--json")
                exec("session", session, "reset-state", "--force", "--confirm-reset", preview.getString("confirm_reset"), "--json")
                check(root.deleteRecursively()) { "Could not remove fixture $root" }
            } finally {
                owner.cancel()
                for (running in relays) { running.destroy(); if (!running.waitFor(2, TimeUnit.SECONDS)) running.destroyForcibly() }
            }
        }
    }
}
