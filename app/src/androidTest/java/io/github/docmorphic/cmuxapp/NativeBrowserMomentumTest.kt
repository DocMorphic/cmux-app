package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalTestApi::class)
class NativeBrowserMomentumTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private lateinit var queue: BrowserInputQueue
    private val sent = CopyOnWriteArrayList<BrowserInput>()
    private var generation by mutableLongStateOf(0)
    private var shorter by mutableStateOf(false)
    private fun show() {
        compose.setContent { CmuxTheme {
            val scope = rememberCoroutineScope()
            queue = remember { BrowserInputQueue(scope) { sent += it } }
            DisposableEffect(queue) { onDispose { queue.close() } }
            val motion = rememberBrowserScrollMotion(queue)
            var size by remember { mutableStateOf(IntSize.Zero) }
            Box((if (shorter) Modifier.fillMaxWidth().height(250.dp) else Modifier.fillMaxSize())
                .onSizeChanged { size = it }.testTag("browser")
                .browserScrollGestures(motion, 1000.0, 2000.0, size, generation, true))
        } }
        compose.waitForIdle(); compose.mainClock.autoAdvance = false
    }
    private fun flick() {
        compose.onNodeWithTag("browser").performTouchInput {
            swipe(Offset(width * .3f, height * .2f), Offset(width * .6f, height * .6f), durationMillis = 80)
        }
        compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
    }
    private fun scrolls() = sent.filterIsInstance<BrowserInput.Scroll>()
    private fun distance() = scrolls().sumOf { kotlin.math.abs(it.dx) + kotlin.math.abs(it.dy) }

    @Test fun realFlingContinuesInPageCoordinatesAndEndsWithMomentumBoundary() {
        show(); flick()
        val released = distance()
        assertTrue(released > 0)
        compose.mainClock.advanceTimeBy(160); compose.waitForIdle()
        assertTrue(distance() > released)
        compose.mainClock.advanceTimeBy(10_000); compose.waitForIdle()
        val scrolls = scrolls()
        assertEquals("began", scrolls.first().phase)
        assertEquals("momentum_ended", scrolls.last().phase)
        assertTrue(scrolls.any { it.phase == "ended" })
        assertTrue(scrolls.any { it.phase == "momentum_began" })
        assertTrue(scrolls.filter { it.phase.startsWith("momentum_") }.any { it.dx < 0 && it.dy < 0 })
        assertTrue(scrolls.all { it.x in 0.0..1000.0 && it.y in 0.0..2000.0 })
        val ended = distance(); compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        assertEquals(ended, distance(), 0.0)
    }

    @Test fun newTouchAndKeyboardInputStopMotionWithoutLateScroll() {
        show(); flick()
        compose.onNodeWithTag("browser").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
        val held = distance()
        assertEquals("cancelled", scrolls().last().phase)
        compose.mainClock.advanceTimeBy(240); compose.waitForIdle()
        assertEquals(held, distance(), 0.0)
        compose.onNodeWithTag("browser").performTouchInput { up() }
        flick()
        compose.runOnUiThread { queue.offer(BrowserInput.Key("return")) }
        compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
        assertEquals(BrowserInput.Key("return"), sent.last())
        val count = sent.size
        compose.mainClock.advanceTimeBy(1_200); compose.waitForIdle()
        assertEquals(count, sent.size)
    }

    @Test fun viewportResizeAndStreamReplacementCancelMomentum() {
        show(); flick()
        compose.runOnUiThread { shorter = true }
        compose.mainClock.advanceTimeBy(64); compose.waitForIdle()
        val resized = distance()
        compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
        assertEquals(resized, distance(), 0.0)
        assertEquals("cancelled", scrolls().last().phase)
        flick()
        compose.runOnUiThread { generation++ }
        compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
        val replaced = distance()
        compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
        assertEquals(replaced, distance(), 0.0)
        assertEquals("cancelled", scrolls().last().phase)
    }
}
