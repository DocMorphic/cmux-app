package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalTestApi::class)
class NativeTerminalMomentumTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private lateinit var motion: TerminalScrollMotion
    private val rows = CopyOnWriteArrayList<Double>()
    private var generation by mutableIntStateOf(0)
    private var screen by mutableStateOf("primary")
    private fun show(linePath: Boolean = false) {
        compose.setContent { CmuxTheme {
            motion = rememberTerminalScrollMotion("fixture-terminal", "fixture-connection")
            Box(Modifier.fillMaxSize().background(Color.Black).testTag("terminal")
                .terminalScrollGestures(motion, TerminalGeometry(1f, 10f, 20f, 0f, 0f, 108, 120),
                    generation, screen, linePath, true) { delta, _ -> rows += delta; true }) {
                Text("Terminal momentum fixture")
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }
    private fun flick() {
        compose.onNodeWithTag("terminal").performTouchInput {
            swipe(Offset(centerX, height * .2f), Offset(centerX, height * .65f), durationMillis = 80)
        }
        compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
    }
    private fun total() = rows.sum()
    @Test fun flingContinuesAfterReleaseAndExplicitInputStopEndsIt() {
        show(); flick(); val released = total()
        assertTrue(released > 0)
        compose.mainClock.advanceTimeBy(160); compose.waitForIdle()
        assertTrue("The real touch release must produce additional momentum rows", total() > released)
        compose.runOnUiThread { motion.stop() }
        val stopped = total()
        compose.mainClock.advanceTimeBy(1_200); compose.waitForIdle()
        assertEquals(stopped, total(), 0.0)
    }
    @Test fun linePathCapsMomentumAndNewTouchStopsASecondFling() {
        show(linePath = true); flick()
        compose.mainClock.advanceTimeBy(480); compose.waitForIdle()
        val capped = total()
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        assertEquals(capped, total(), 0.0)
        flick(); compose.mainClock.advanceTimeBy(80); compose.waitForIdle()
        compose.onNodeWithTag("terminal").performTouchInput { down(center) }
        val held = total()
        compose.mainClock.advanceTimeBy(240); compose.waitForIdle()
        assertEquals(held, total(), 0.0)
        compose.onNodeWithTag("terminal").performTouchInput { up() }
    }
    @Test fun replacedSurfaceAndScreenModeDiscardOldMomentum() {
        show(); flick()
        compose.runOnUiThread { generation++ }
        compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
        val replaced = total()
        compose.mainClock.advanceTimeBy(240); compose.waitForIdle()
        assertEquals(replaced, total(), 0.0)
        flick()
        compose.runOnUiThread { screen = "alternate" }
        compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
        val switched = total()
        compose.mainClock.advanceTimeBy(240); compose.waitForIdle()
        assertEquals(switched, total(), 0.0)
    }
}
