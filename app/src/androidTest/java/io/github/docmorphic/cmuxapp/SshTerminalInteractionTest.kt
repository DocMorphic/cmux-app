package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

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
                assertTrue(input.ownsLocalScrollback())
                assertNull(input.scroll(3.0, cell))
                assertNull(input.scroll(.125, cell))
                assertEquals(false, input.scroll(Double.NaN, cell))
                assertEquals(false, input.scroll(Double.POSITIVE_INFINITY, cell))
                assertFalse(input.click(cell))
                terminal.output("\u001b[?1049h\u001b[?1007h")
                assertFalse(input.ownsLocalScrollback())
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
                terminal.output("\u001b[?1049l\u001b[?1000h")
                assertTrue(input.ownsLocalScrollback())
                assertNull(input.scroll(.25, cell))
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
            compose.onNodeWithText("Latest").assertDoesNotExist()
            compose.onNodeWithTag("ssh.shell.terminal").performTouchInput { swipeUp(durationMillis = 350) }
            compose.waitUntil(10000) { terminal.sent.any { it.decodeToString().startsWith("\u001b[<65;") } }
            compose.onNodeWithText("Latest").assertDoesNotExist()
        } finally { compose.activityRule.scenario.close(); terminal.close() }
    }

    @Test fun heldPrimaryDragMovesPaintedHistoryByPixelsAndScreenReplacementClearsIt() {
        val terminal = Terminal()
        try {
            show(terminal)
            compose.runOnIdle {
                terminal.output((1..150).joinToString("\r\n") {
                    val color = if (it % 2 == 0) "21;50;79" else "91;53;21"
                    "\u001b[48;2;${color}mhistory $it\u001b[K"
                } + "\u001b[0m")
            }
            val node = compose.onNodeWithTag("ssh.shell.terminal")
            node.performTouchInput { down(Offset(centerX, height * .25f)); moveBy(Offset(0f, 110f), 200) }
            compose.onNodeWithText("Latest").assertExists()
            fun capture() = node.captureToImage().asAndroidBitmap()
            val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                "ssh-pixel-scroll").apply { mkdirs() }
            fun save(bitmap: android.graphics.Bitmap, name: String) {
                File(dir, name).outputStream().use {
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            }
            fun edges(bitmap: android.graphics.Bitmap): List<Int> {
                val colors = setOf(0xff15324f.toInt(), 0xff5b3515.toInt())
                // Sample the erased, colored part of each row, inside the grid's
                // horizontal letterbox and beyond the short fixture labels.
                val x = bitmap.width / 2
                val boundaries = mutableListOf<Int>()
                var previous: Int? = null
                var lastPainted = -10
                for (y in bitmap.height / 4 until bitmap.height * 3 / 4) {
                    val color = bitmap.getPixel(x, y)
                    if (color !in colors) continue // Fractional edges include an antialiased pixel.
                    if (previous != null && previous != color && y - lastPainted <= 3) boundaries += y
                    previous = color; lastPainted = y
                }
                return boundaries
            }
            val beforeBitmap = capture()
            save(beforeBitmap, "held-before.png")
            val before = edges(beforeBitmap)
            assertTrue("Fixture must paint several alternating history rows", before.size >= 3)
            // Keep the finger down: this delta is much smaller than one cell,
            // so the old row-quantized screen would not move at all.
            node.performTouchInput { moveBy(Offset(0f, 3f), 100) }
            val afterBitmap = capture()
            save(afterBitmap, "held-history.png")
            val after = edges(afterBitmap)
            assertTrue("A 3px drag must move painted row boundaries by 3px: $before -> $after",
                before.drop(1).dropLast(1).all { old -> after.any { kotlin.math.abs(it - old - 3) <= 1 } })
            node.performTouchInput { advanceEventTime(200); up() }
            compose.runOnIdle { assertTrue(terminal.sent.isEmpty()); terminal.output("\u001b[?1049h") }
            compose.onNodeWithText("Latest").assertDoesNotExist()
            compose.runOnIdle { terminal.output("\u001b[?1049l") }
            compose.onNodeWithText("Latest").assertDoesNotExist()
        } finally { compose.activityRule.scenario.close(); terminal.close() }
    }
}
