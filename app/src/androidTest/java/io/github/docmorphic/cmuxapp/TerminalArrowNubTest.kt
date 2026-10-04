package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalArrowNubTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle get() = registry
    }
    @Test fun dragTicksDirectionChangesAndReleaseUseLiveHapticSetting() {
        val arrows = mutableListOf<TerminalToolbarButton>()
        val feedback = mutableListOf<NativeHaptic>()
        var buzz = true
        val haptics = NativeHaptics({ buzz }, feedback::add)
        compose.setContent { CompositionLocalProvider(LocalNativeHaptics provides haptics) {
            CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row { TerminalArrowNub("one", true, arrows::add) }
            } }
        } }
        compose.mainClock.autoAdvance = false
        val pad = compose.onNodeWithTag("terminal-arrow-nub")
        pad.performTouchInput { down(center); moveTo(center + Offset(width * .4f, 0f)) }
        compose.mainClock.advanceTimeBy(180)
        compose.runOnIdle {
            assertTrue(arrows.size >= 2); assertTrue(arrows.all { it == TerminalToolbarButton.RIGHT })
            assertEquals(arrows.size, feedback.size); buzz = false
        }
        val before = arrows.size
        pad.performTouchInput { moveTo(center + Offset(0f, -height * .4f)) }
        compose.mainClock.advanceTimeBy(180)
        compose.runOnIdle { assertTrue(arrows.drop(before).all { it == TerminalToolbarButton.UP }); assertTrue(arrows.size > before); assertEquals(before, feedback.size) }
        pad.performTouchInput { moveTo(center) }
        val stopped = arrows.size
        compose.mainClock.advanceTimeBy(240)
        compose.runOnIdle { assertEquals(stopped, arrows.size) }
        pad.performTouchInput { moveTo(center + Offset(-width * .4f, 0f)); up() }
        val ended = arrows.size
        compose.mainClock.advanceTimeBy(240)
        compose.runOnIdle { assertEquals(ended, arrows.size); assertEquals(TerminalToolbarButton.LEFT, arrows.last()) }
    }

    @Test fun disablingBackgroundingAndReplacingOwnerRetireTheDragAndAccessibilityStaysSingleStep() {
        val owner = Owner()
        var target by mutableStateOf("one")
        var enabled by mutableStateOf(true)
        val received = mutableListOf<Pair<String, TerminalToolbarButton>>()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row { val captured = target; TerminalArrowNub(target, enabled) { received += captured to it } }
            } }
        } }
        compose.mainClock.autoAdvance = false
        val pad = compose.onNodeWithTag("terminal-arrow-nub")
        fun begin() = pad.performTouchInput { down(center); moveTo(center + Offset(width * .4f, 0f)) }
        fun finish() = pad.performTouchInput { up() }
        begin(); compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { target = "two" }
        compose.mainClock.advanceTimeByFrame(); val retired = received.size
        compose.mainClock.advanceTimeBy(240)
        compose.runOnIdle { assertEquals(retired, received.size); assertTrue(received.all { it.first == "one" }) }; finish()
        begin(); compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { enabled = false }; compose.mainClock.advanceTimeByFrame(); val disabled = received.size
        compose.mainClock.advanceTimeBy(240); compose.runOnIdle { assertEquals(disabled, received.size); enabled = true }
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle { assertEquals(disabled, received.size) }; finish()
        begin(); compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }; val paused = received.size
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeBy(240)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeBy(240); compose.runOnIdle { assertEquals(paused, received.size) }; finish()
        val actions = pad.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single { it.label == "Up Arrow" }.action()) }
        compose.mainClock.advanceTimeBy(240)
        compose.runOnIdle { assertEquals(paused + 1, received.size); assertEquals("two" to TerminalToolbarButton.UP, received.last()) }
    }
}
