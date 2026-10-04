package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalBellFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle get() = registry
    }
    @Test fun inactiveViewsAndOldOwnersCannotBuzzOrQueueFeedbackForResume() {
        val owner = Owner()
        val old = TerminalBellSignal()
        var signal by mutableStateOf(old)
        var enabled by mutableStateOf(true)
        var preference = true
        val received = mutableListOf<NativeHaptic>()
        val haptics = NativeHaptics({ preference }, received::add)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalNativeHaptics provides haptics, LocalLifecycleOwner provides owner) {
                ObserveTerminalBells(signal, enabled)
            }
        }
        compose.runOnIdle { old.ring(); assertEquals(listOf(NativeHaptic.WARNING), received) }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED; old.ring() }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED; assertEquals(1, received.size) }
        compose.runOnIdle { preference = false; old.ring(); assertEquals(1, received.size); preference = true; enabled = false }
        compose.runOnIdle { old.ring(); assertEquals(1, received.size); enabled = true; signal = TerminalBellSignal() }
        compose.runOnIdle { old.ring(); assertEquals(1, received.size); signal.ring(); assertEquals(2, received.size) }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED; signal.ring(); assertEquals(2, received.size) }
    }
}
