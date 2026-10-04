package io.github.docmorphic.cmuxapp

import android.view.HapticFeedbackConstants
import org.junit.Assert.*
import org.junit.Test

class NativeHapticsTest {
    @Test fun changesApplyToAlreadyCapturedCallbacksAndToolkitActions() {
        var enabled = true
        val emitted = mutableListOf<NativeHaptic>()
        var toolkitCalls = 0
        val haptics = NativeHaptics({ enabled }, emitted::add)
        val completion = { haptics.perform(NativeHaptic.SUCCESS) }
        haptics.perform(NativeHaptic.LIGHT)
        enabled = false
        completion()
        NativeHaptic.entries.forEach(haptics::perform)
        haptics.whenEnabled { toolkitCalls++ }
        assertEquals(listOf(NativeHaptic.LIGHT), emitted)
        assertEquals(0, toolkitCalls)
        enabled = true
        completion()
        haptics.whenEnabled { toolkitCalls++ }
        assertEquals(listOf(NativeHaptic.LIGHT, NativeHaptic.SUCCESS), emitted)
        assertEquals(1, toolkitCalls)
    }

    @Test fun semanticFeedbackUsesSupportedConstantsOnBothSidesOfApi30() {
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, nativeHapticConstant(NativeHaptic.SUCCESS, 26))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, nativeHapticConstant(NativeHaptic.ERROR, 29))
        assertEquals(HapticFeedbackConstants.CONFIRM, nativeHapticConstant(NativeHaptic.SUCCESS, 30))
        assertEquals(HapticFeedbackConstants.REJECT, nativeHapticConstant(NativeHaptic.ERROR, 37))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, nativeHapticConstant(NativeHaptic.LIGHT, 26))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, nativeHapticConstant(NativeHaptic.WARNING, 37))
    }
}
