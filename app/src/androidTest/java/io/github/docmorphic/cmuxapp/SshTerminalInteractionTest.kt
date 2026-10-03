package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Empty local activity and real Ghostty parser, without account or SSH credentials. */
class SshTerminalInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Terminal : SshTerminal {
        override val id = "interaction-test"
        override val title = "Interaction fixture"
        override val state = MutableStateFlow(SshShellState(SshShellPhase.RUNNING))
        override val display = GhosttyVtTerminal(80, 24)
        val sent = mutableListOf<ByteArray>()
        var accept = true
        override fun send(text: String, paste: Boolean) = sendBytes(text.toByteArray())
        override fun sendBytes(bytes: ByteArray): Boolean {
            if (!accept) return false
            sent += bytes.copyOf(); return true
        }
        override fun resize(columns: Int, rows: Int, cells: TerminalCellMetrics) {
            display.resize(columns, rows, cells.widthPx.toInt().coerceAtLeast(1), cells.heightPx.toInt().coerceAtLeast(1))
            state.value = state.value.copy(revision = state.value.revision + 1)
        }
        fun output(text: String) {
            display.append(text.toByteArray()); state.value = state.value.copy(revision = state.value.revision + 1)
        }
        override fun close() { state.value = state.value.copy(phase = SshShellPhase.ENDED); display.close() }
    }

    @Test fun explicitReportsUseMouseModesAndNeverGenerateMirrorQueryReplies() {
        compose.runOnUiThread {
            Terminal().use { terminal ->
                val input = SshTerminalInteraction(terminal)
                val cell = TerminalGeometry.Cell(4, 2)
                terminal.output("\u001b[?1000h\u001b[?1006h\u001b[6n\u001b[c")
                assertTrue(terminal.sent.isEmpty())
                assertTrue(input.click(cell))
                assertEquals("\u001b[<0;5;3M\u001b[<0;5;3m", terminal.sent.last().decodeToString())
                assertEquals(true, input.scroll(2.0, cell))
                assertEquals("\u001b[<64;5;3M".repeat(2), terminal.sent.last().decodeToString())
                assertEquals(true, input.scroll(-1.0, cell))
                assertEquals("\u001b[<65;5;3M", terminal.sent.last().decodeToString())
                terminal.accept = false
                assertFalse(input.click(cell))
                assertEquals(false, input.scroll(1.0, cell))
                assertEquals(3, terminal.sent.size)
            }
        }
    }

    @Test fun alternateArrowsRespectModeAndPrimaryHistoryStaysLocal() {
        compose.runOnUiThread {
            Terminal().use { terminal ->
                val input = SshTerminalInteraction(terminal)
                val cell = TerminalGeometry.Cell(0, 0)
                assertNull(input.scroll(3.0, cell))
                assertFalse(input.click(cell))
                terminal.output("\u001b[?1049h\u001b[?1007h")
                assertEquals(true, input.scroll(2.0, cell))
                assertEquals("\u001b[A\u001b[A", terminal.sent.last().decodeToString())
                terminal.output("\u001b[?1h")
                assertEquals(true, input.scroll(-2.0, cell))
                assertEquals("\u001bOB\u001bOB", terminal.sent.last().decodeToString())
                terminal.output("\u001b[?1007l")
                assertEquals(false, input.scroll(1.0, cell))
                assertEquals(false, input.scroll(Double.NaN, cell))
                assertEquals(false, input.scroll(4097.0, cell))
                terminal.state.value = terminal.state.value.copy(phase = SshShellPhase.ENDED)
                assertFalse(input.click(cell)); assertFalse(input.focus(true))
                assertEquals(false, input.scroll(1.0, cell))
                assertEquals(2, terminal.sent.size)
            }
        }
    }

    private fun show(terminal: Terminal) {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            SshShellScreen(terminal, onBack = {})
        } } }
        compose.onNodeWithTag("ssh.shell.terminal").assertExists()
    }

    @Test fun realShellGesturesSendWheelThenAtomicClickAndFocusTracksActivity() {
        val terminal = Terminal()
        try {
            compose.runOnUiThread { terminal.output("\u001b[?1049h\u001b[?1000h\u001b[?1006h\u001b[?1004h") }
            show(terminal)
            compose.waitUntil(10000) { terminal.sent.any { it.contentEquals(byteArrayOf(27, 91, 73)) } }
            compose.onNodeWithTag("ssh.shell.terminal").performTouchInput { swipeUp(durationMillis = 350) }
            compose.waitUntil(10000) { terminal.sent.any { it.decodeToString().startsWith("\u001b[<65;") } }
            compose.runOnIdle { assertTrue(terminal.sent.none { it.decodeToString().startsWith("\u001b[<0;") }) }
            compose.onNodeWithTag("ssh.shell.terminal").performTouchInput { click(center) }
            compose.waitUntil(10000) { terminal.sent.any { it.decodeToString().matches(Regex("\u001b\\[<0;\\d+;\\d+M\u001b\\[<0;\\d+;\\d+m")) } }
            compose.onNodeWithTag("ssh.shell.keyboard").assertExists()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.onActivity {
                assertArrayEquals(byteArrayOf(27, 91, 79), terminal.sent.last())
                terminal.sent.clear()
            }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10000) { terminal.sent.any { it.contentEquals(byteArrayOf(27, 91, 73)) } }
            compose.runOnIdle { assertEquals(1, terminal.sent.size) }
        } finally { compose.activityRule.scenario.close(); terminal.close() }
    }

    @Test fun primaryScrollbackDoesNotWriteToRemoteShell() {
        val terminal = Terminal()
        try {
            compose.runOnUiThread { terminal.output((1..100).joinToString("\r\n") { "history $it" }) }
            show(terminal)
            compose.onNodeWithTag("ssh.shell.terminal").performTouchInput { swipeDown(durationMillis = 350) }
            compose.onNodeWithText("Latest").assertExists()
            compose.runOnIdle { assertTrue(terminal.sent.isEmpty()) }
            // A program which starts capturing the mouse owns the next wheel
            // interaction, even if the user was reading history before that.
            compose.runOnIdle { terminal.output("\u001b[?1000h\u001b[?1006h") }
            compose.onNodeWithTag("ssh.shell.terminal").performTouchInput { swipeUp(durationMillis = 350) }
            compose.waitUntil(10000) { terminal.sent.any { it.decodeToString().startsWith("\u001b[<65;") } }
            compose.onNodeWithText("Latest").assertDoesNotExist()
        } finally { compose.activityRule.scenario.close(); terminal.close() }
    }
}
