package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in real tmux, always a unique private socket and empty HOME/config.
 * CMUX_TMUX_TEST_BINARY must name an absolute binary; no personal server is used. */
class SshTmuxProcessTest {
    @Test fun realGroupedSessionSeedsStreamsResizesAndLeavesOriginalWindowSelected() = runBlocking {
        val binary = System.getenv("CMUX_TMUX_TEST_BINARY")
        assumeTrue("Opt-in tmux binary not configured", binary?.startsWith('/') == true)
        val root = Files.createTempDirectory("cmux-tmux-test-").toFile()
        val socket = "cmux-android-test-${UUID.randomUUID()}"
        val config = root.resolve("tmux.conf").apply { writeText("set -g default-shell /bin/sh\nset -g default-command /bin/cat\nset -g update-environment ''\n") }
        val prefix = listOf(binary!!, "-L", socket, "-f", config.absolutePath)
        fun process(args: List<String>): Process = ProcessBuilder(prefix + args).apply {
            directory(root)
            environment().clear()
            environment().putAll(mapOf("HOME" to root.absolutePath, "PATH" to "/usr/bin:/bin", "TERM" to "xterm-256color", "LC_ALL" to "en_US.UTF-8"))
            redirectError(ProcessBuilder.Redirect.appendTo(root.resolve("stderr.txt")))
        }.start()
        fun exec(vararg args: String): String {
            val p = process(args.toList())
            check(p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); "tmux command timed out" }
            val result = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            check(p.exitValue() == 0) { "tmux command failed: ${root.resolve("stderr.txt").readText()}" }
            return result
        }
        var client: SshTmuxControl? = null
        val owner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try {
            exec("new-session", "-d", "-s", "original", "-x", "100", "-y", "30")
            exec("new-window", "-d", "-t", "=original:", "-n", "other")
            val original = exec("display-message", "-p", "-t", "=original:", "#{window_id}")
            val pane = SshTmuxParser.id(exec("display-message", "-p", "-t", "=original:", "#{pane_id}"), '%')!!
            // Seed real history before the phone attaches.
            exec("send-keys", "-t", "%$pane", "seed-before-phone", "Enter")
            // cat emits the program's mode change before the phone is present.
            exec("send-keys", "-t", "%$pane", "-H", "1b", "5b", "3f", "32", "30", "30", "34", "68", "0a")
            withTimeout(3000) {
                while (exec("display-message", "-p", "-t", "%$pane", "#{bracket_paste_flag}") != "1") delay(10)
            }
            val group = "original${SshTmuxEncoding.GROUP_MARKER}fixture"
            val running = process(listOf("-C", "new-session", "-t", "=original", "-s", group, ";", "set-option", "-t", "=$group:", "destroy-unattached", "off"))
            val beforeWrite = AtomicReference<((String) -> Unit)?>(null)
            val pipe = object : SshTmuxPipe {
                override val output = flow {
                    val bytes = ByteArray(8192)
                    while (true) { val n = running.inputStream.read(bytes); if (n < 0) break; emit(bytes.copyOf(n)) }
                }.flowOn(Dispatchers.IO)
                override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
                    beforeWrite.getAndSet(null)?.invoke(bytes.toString(Charsets.UTF_8))
                    running.outputStream.write(bytes); running.outputStream.flush()
                }
                override fun close() { running.destroy() }
            }
            val control = SshTmuxControl(group, pipe, owner, commandTimeoutMillis = 5000); client = control
            withTimeout(15000) {
                control.initialize()
                val events = mutableListOf<TmuxPaneEvent>()
                val seeded = CompletableDeferred<Unit>()
                val window = SshTmuxParser.id(original, '@')!!
                control.attach(pane, window) { events += it; if (it is TmuxPaneEvent.Snapshot) seeded.complete(Unit) }
                seeded.await()
                val snapshot = events.filterIsInstance<TmuxPaneEvent.Snapshot>().single().bytes.toString(Charsets.UTF_8)
                assertTrue(snapshot.contains("seed-before-phone"))
                assertTrue(snapshot.contains("\u001b[?2004h"))
                control.command("select-window -t ${SshTmuxEncoding.quote("=$group:1")}")
                assertEquals(original, exec("display-message", "-p", "-t", "=original:", "#{window_id}"))
                assertEquals("destroy-unattached off", exec("show-options", "-t", "=$group:", "destroy-unattached"))
                control.write(pane, "live λ 中\n".toByteArray())
                fun live() = events.filterIsInstance<TmuxPaneEvent.Output>().flatMap { it.bytes.toList() }.toByteArray().toString(Charsets.UTF_8)
                while (!live().contains("live λ 中")) delay(10)
                control.resize(72, 18)
                control.command("display-message -p ready") // barrier: resize reached tmux
                // client_height is empty for non-TTY control clients in tmux
                // 3.7c. Assert the actual window/pane geometry instead.
                assertEquals("72x18", control.command("display-message -p -t %$pane '#{window_width}x#{window_height}'").single().toString(Charsets.UTF_8))
                var changes = 0; control.onTopologyChange = { changes++ }
                exec("split-window", "-d", "-h", "-t", "%$pane")
                while (changes == 0) delay(10)
                assertTrue(events.filterIsInstance<TmuxPaneEvent.Grid>().size >= 2)
                // A pane moved into another session must not receive an action
                // from the old workspace's still-visible row.
                val metadata = exec("display-message", "-p", "-t", "=original:", "#{pid}:#{session_id}:#{session_created}").split(':')
                val workspace = SshTmuxWorkspace(metadata[0].toInt(), SshTmuxParser.id(metadata[1], '$')!!, metadata[2].toLong(), "original", emptyList())
                val row = SshTmuxPaneRow(pane, SshTmuxParser.id(original, '@')!!, 0, "original", 0, 39, 18, 2)
                val oldTarget = SshTmuxInventory.paneTarget(workspace, row)
                exec("new-session", "-d", "-s", "destination")
                // Move after the UI accepted input and queued its command, but
                // before the command reaches tmux. Local inventory is too late.
                beforeWrite.set { command ->
                    assertTrue(command.startsWith("send-keys"))
                    exec("join-pane", "-d", "-s", "%$pane", "-t", "=destination:")
                }
                assertTrue(runCatching { control.write(pane, "must-not-follow-pane\n".toByteArray()) }.isFailure)
                assertNull(beforeWrite.get())
                assertFalse(exec("capture-pane", "-p", "-t", "%$pane").contains("must-not-follow-pane"))
                val before = exec("list-panes", "-s", "-t", "=destination", "-F", "#{pane_id}")
                val stale = process(SshTmuxInventory.guardedArguments(workspace, "split-window -d -h -t ${SshTmuxEncoding.quote(oldTarget)}", oldTarget))
                assertTrue(stale.waitFor(5, TimeUnit.SECONDS)); assertNotEquals(0, stale.exitValue())
                assertEquals(before, exec("list-panes", "-s", "-t", "=destination", "-F", "#{pane_id}"))
                // A stale attach must not capture the moved pane's new content.
                control.detach(pane)
                val staleEvents = mutableListOf<TmuxPaneEvent>()
                control.attach(pane, window, staleEvents::add)
                while (TmuxPaneEvent.Ended !in staleEvents) delay(10)
                assertTrue(staleEvents.none { it is TmuxPaneEvent.Snapshot || it is TmuxPaneEvent.Output })
                exec("kill-session", "-t", "=destination")
                runCatching { control.detachSession() }
                assertTrue(control.isClosed)
                val sessions = exec("list-sessions", "-F", "#{session_name}").lines()
                assertEquals(listOf("original"), sessions)
                assertEquals(original, exec("display-message", "-p", "-t", "=original:", "#{window_id}"))
            }
        } finally {
            client?.close(); owner.cancel()
            runCatching { exec("kill-server") }
            root.deleteRecursively()
        }
    }
}
