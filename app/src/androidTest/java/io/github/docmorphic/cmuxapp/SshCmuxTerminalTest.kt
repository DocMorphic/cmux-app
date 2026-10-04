package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Base64

/** Emulator component checks: actual control, Ghostty JNI and shared terminal
 * UI, with deterministic wire events. Real server/SSH acceptance is separate. */
class SshCmuxTerminalTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var owner: CoroutineScope
    private lateinit var pipe: Pipe
    private lateinit var control: SshCmuxControl
    private lateinit var terminal: SshCmuxTerminal
    private class Pipe : SshExecPipe {
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        override val output = input.receiveAsFlow()
        val sent = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        var cols = 80; var rows = 24
        var snapshot = "seed λ 中\r\n\u001b[?2004h\u001b[6n"
        fun feed(event: JSONObject) { check(input.trySend((event.toString() + "\n").toByteArray()).isSuccess) }
        fun frame(event: String = "resized") = JSONObject().put("event", event).put("surface", 1).put("cols", cols).put("rows", rows)
            .put(if (event == "resized") "replay" else "data", Base64.getEncoder().encodeToString(snapshot.toByteArray()))
            .put("colors", JSONObject().put("fg", "#123456").put("bg", "#101112").put("cursor", "#abcdef")
                .put("cursor_style", "underline").put("cursor_blink", false))
        override suspend fun write(bytes: ByteArray) {
            val value = JSONObject(bytes.toString(Charsets.UTF_8)); sent += value
            val data = when (value.getString("cmd")) {
                "identify" -> JSONObject().put("app", "cmux-tui").put("version", "fixture").put("protocol", 12)
                    .put("session", "fixture").put("pid", 1).put("generation", "fixture-boot")
                    .put("capabilities", JSONArray(listOf("workspace-registry-v1", "attach-initial-size", "view-attachment-lease-v1", "view-attachment-detach-v1")))
                "attach-surface" -> { feed(frame("vt-state")); JSONObject().put("lease", "view-a") }
                "list-workspaces" -> JSONObject("""{"generation":"fixture-boot","registry_id":"registry","workspaces":[{"id":4,"key":"workspace","resource_id":"ws_a","name":"fixture","screens":[{"id":3,"panes":[{"id":2,"tabs":[{"surface":1,"kind":"pty","tab_resource_id":"tab_a","terminal_resource_id":"term_a","terminal_id":"host-a","title":"fixture"}]}]}]}]}""")
                "resize-attached-view" -> {
                    val c = value.getInt("cols"); val r = value.getInt("rows")
                    if (c != cols || r != rows) { cols = c; rows = r; feed(frame()) }
                    JSONObject().put("outcome", "applied")
                }
                "release-attached-view-size", "detach-attached-view" -> JSONObject().put("outcome", "applied")
                "send" -> {
                    val output = "echo accepted\r\n"
                    // A real server's resize replay includes already emitted output.
                    // Keep the fixture consistent when keyboard resizing races input.
                    snapshot += output
                    feed(JSONObject().put("event", "output").put("surface", 1).put("data", Base64.getEncoder().encodeToString(output.toByteArray())))
                    JSONObject()
                }
                else -> JSONObject()
            }
            feed(JSONObject().put("id", value.getString("id")).put("ok", true).put("data", data))
        }
        override fun close() { input.close() }
    }
    @Before fun setup() {
        assumeTrue(android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
        owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        pipe = Pipe()
        runBlocking { withContext(Dispatchers.Main) {
            control = SshCmuxControl(pipe, owner); control.handshake("fixture")
            val tab = SshCmuxTab(1, 2, 3, "tab_a", "pty", "term_a", "term_a", "host-a", null, "cmux-tui fixture",
                80, 24, false, null, null, null, false)
            val selection = SshCmuxSelection("fixture", "registry", "fixture-boot", 4, "workspace", "ws_a", 1, "tab_a", "term_a", "host-a")
            terminal = SshCmuxTerminal.open("cmux-fixture", selection, tab, control, owner) { owner.isActive }
        } }
    }
    @After fun cleanup() {
        if (::owner.isInitialized) compose.runOnIdle { if (::terminal.isInitialized) terminal.close(); if (::control.isInitialized) control.close(); owner.cancel() }
    }
    private fun text() = compose.runOnIdle { TerminalTextSnapshot.capture(terminal.display).text }
    @Test fun visibleCmuxOutputRingsButReplayedAndDisabledBellsStaySilent() {
        val feedback = java.util.concurrent.CopyOnWriteArrayList<NativeHaptic>()
        var enabled = true
        val haptics = NativeHaptics({ enabled }, feedback::add)
        compose.setContent { CompositionLocalProvider(LocalNativeHaptics provides haptics) {
            CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) { SshShellScreen(terminal) {} } }
        } }
        compose.runOnIdle { pipe.snapshot = "history\u0007"; pipe.feed(pipe.frame()) }
        compose.waitUntil(5000) { text().contains("history") }
        compose.runOnIdle { assertTrue(feedback.isEmpty()) }
        fun output(value: String) = pipe.feed(JSONObject().put("event", "output").put("surface", 1)
            .put("data", Base64.getEncoder().encodeToString(value.toByteArray())))
        compose.runOnIdle { output("\u0007live") }
        compose.waitUntil(5000) { feedback.size == 1 }
        compose.runOnIdle { assertEquals(listOf(NativeHaptic.WARNING), feedback.toList()); enabled = false; output("\u0007silent") }
        compose.waitUntil(5000) { text().contains("silent") }
        compose.runOnIdle { assertEquals(1, feedback.size) }
    }

    @Test fun arrowPadUsesSharedSshEncodingAndSingleUseModifiers() {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SshShellScreen(terminal) {}
        } } }
        compose.onNodeWithTag("terminal-shortcut-builtin.ALT").performClick()
        val actions = compose.onNodeWithTag("terminal-arrow-nub").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single { it.label == "Right Arrow" }.action()) }
        compose.waitUntil(5000) { pipe.sent.count { it.optString("cmd") == "send" } >= 1 }
        compose.runOnIdle { assertTrue(actions.single { it.label == "Right Arrow" }.action()) }
        compose.waitUntil(5000) { pipe.sent.count { it.optString("cmd") == "send" } >= 2 }
        compose.runOnIdle {
            val payloads = pipe.sent.filter { it.optString("cmd") == "send" }
            assertEquals(listOf("\u001bf", "\u001b[C"), payloads.map { Base64.getDecoder().decode(it.getString("bytes")).toString(Charsets.UTF_8) })
        }
    }

    @Test fun rawMouseBytesAreCopiedAndDeliveredWithoutUtf8Conversion() {
        val expected = byteArrayOf(27, 91, 77, 32, 183.toByte(), 35)
        compose.runOnIdle {
            val caller = expected.copyOf()
            assertTrue(terminal.sendBytes(caller))
            caller.fill(0)
        }
        compose.waitUntil(5000) { pipe.sent.any { it.optString("cmd") == "send" } }
        compose.runOnIdle {
            val write = pipe.sent.single { it.optString("cmd") == "send" }
            assertArrayEquals(expected, Base64.getDecoder().decode(write.getString("bytes")))
        }
    }
    @Test fun nativeRendererAndComposerApplyReplayColorsModesAndSendOnlyUserInput() {
        assertTrue(text().contains("seed λ 中"))
        compose.runOnIdle {
            assertTrue(terminal.display.bracketedPaste)
            assertEquals("#123456", terminal.display.foreground)
            assertEquals("#101112", terminal.display.background)
            assertEquals("#abcdef", terminal.display.cursorColor)
            assertEquals("underline", terminal.display.cursor?.style)
            assertEquals(false, terminal.display.cursor?.blinking)
            assertTrue(pipe.sent.none { it.optString("cmd") == "send" }) // DSR query never becomes keyboard input.
        }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) { SshShellScreen(terminal) {} } } }
        compose.onNodeWithTag("ssh.shell.composer").performTextReplacement("Phone λ 中")
        compose.onNodeWithTag("ssh.shell.send").performClick()
        compose.waitUntil(5000) { pipe.sent.any { it.optString("cmd") == "send" } }
        compose.runOnIdle {
            val writes = pipe.sent.filter { it.optString("cmd") == "send" }
            assertEquals(1, writes.size)
            assertEquals("\u001b[200~Phone λ 中\u001b[201~\r", String(Base64.getDecoder().decode(writes.single().getString("bytes"))))
        }
        compose.waitUntil(5000) { text().contains("echo accepted") }
        val ui = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val bitmap = ui.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "cmux-tui-terminal.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun hiddenViewsReleaseGeometryAndResizeReplacesRatherThanDuplicatesHistory() {
        runBlocking { withContext(Dispatchers.Main) { terminal.visible(true) } }
        compose.waitUntil(5000) { pipe.sent.count { it.optString("cmd") == "set-client-sizing" } >= 2 }
        val prior = compose.runOnIdle { pipe.sent.count { it.optString("cmd") == "release-attached-view-size" } }
        compose.runOnIdle { terminal.visible(false) }
        compose.waitUntil(5000) { pipe.sent.count { it.optString("cmd") == "release-attached-view-size" } > prior }
        compose.runOnIdle { pipe.cols = 1; pipe.rows = 1; pipe.snapshot = "Z"; pipe.feed(pipe.frame()) }
        compose.waitUntil(5000) { compose.runOnIdle { terminal.display.columns == 1 && terminal.display.rows == 1 } }
        assertEquals("Z", text().trim())
        runBlocking { withContext(Dispatchers.Main) { terminal.retire() } }
        compose.runOnIdle {
            assertEquals(SshShellPhase.ENDED, terminal.state.value.phase)
            assertFalse(terminal.send("late", false))
            assertEquals(1, pipe.sent.count { it.optString("cmd") == "detach-attached-view" })
            assertFalse(control.closed)
        }
    }
    @Test fun priorViewReleaseCannotRetireTheReplacementAttachment() {
        lateinit var provider: SshCmuxProvider
        lateinit var first: SshCmuxTerminal
        lateinit var second: SshCmuxTerminal
        runBlocking { withContext(Dispatchers.Main) {
            terminal.retire()
            provider = SshCmuxProvider.open(control, owner) { owner.isActive }
            first = provider.open(terminal.selection, "shared-view")
            second = provider.open(terminal.selection, "shared-view")
            assertNotSame(first, second); assertEquals(SshShellPhase.ENDED, first.state.value.phase)
            provider.release(first)
        } }
        compose.runOnIdle {
            assertEquals(SshShellPhase.RUNNING, second.state.value.phase)
            second.visible(true); assertTrue(second.send("current view", false))
            provider.release(second)
        }
        compose.waitUntil(5000) { second.state.value.phase == SshShellPhase.ENDED }
        compose.runOnIdle { assertFalse(control.closed); provider.close() }
    }
}
