package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Only the current, interactive terminal may generate tactile feedback. */
@Composable
internal fun ObserveTerminalBells(signal: TerminalBellSignal?, enabled: Boolean = true) {
    val haptics = rememberNativeHaptics()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val available by rememberUpdatedState(enabled)
    DisposableEffect(signal, lifecycle, haptics) {
        val observation = signal?.listen {
            if (available && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                haptics.perform(NativeHaptic.WARNING)
        }
        onDispose { observation?.close() }
    }
}
