package io.github.docmorphic.cmuxapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class NativeHaptic { LIGHT, SUCCESS, WARNING, ERROR }

/** Read at emission time: disabling feedback also gates callbacks already in flight. */
internal class NativeHaptics(private val enabled: () -> Boolean, private val emit: (NativeHaptic) -> Unit) {
    fun perform(feedback: NativeHaptic) = whenEnabled { emit(feedback) }
    fun whenEnabled(action: () -> Unit) { if (enabled()) action() }
}

internal fun nativeHapticConstant(feedback: NativeHaptic, sdk: Int): Int = when (feedback) {
    NativeHaptic.LIGHT -> HapticFeedbackConstants.CLOCK_TICK
    NativeHaptic.SUCCESS -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    NativeHaptic.WARNING -> HapticFeedbackConstants.LONG_PRESS
    NativeHaptic.ERROR -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
}

internal val LocalNativeHaptics = staticCompositionLocalOf<NativeHaptics?> { null }

@Composable
internal fun rememberNativeHaptics(): NativeHaptics {
    LocalNativeHaptics.current?.let { return it }
    val context = LocalContext.current
    val view = LocalView.current
    val preferences = remember(context) { context.getSharedPreferences("cmux-display", Context.MODE_PRIVATE) }
    return remember(preferences, view) {
        NativeHaptics({ preferences.all[NativeDisplayPreferences.hapticsKey] as? Boolean ?: true }) {
            // No ignore-setting flags or VIBRATE permission: Android retains its system/device policy.
            view.performHapticFeedback(nativeHapticConstant(it, Build.VERSION.SDK_INT))
        }
    }
}

@Composable
internal fun NativeHapticsProvider(content: @Composable () -> Unit) {
    val haptics = rememberNativeHaptics()
    val platform = LocalHapticFeedback.current
    val gated = remember(haptics, platform) { object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            haptics.whenEnabled { platform.performHapticFeedback(hapticFeedbackType) }
        }
    } }
    CompositionLocalProvider(LocalNativeHaptics provides haptics, LocalHapticFeedback provides gated, content = content)
}

@Composable
internal fun NativeHapticSettings(preferences: SharedPreferences, state: NativeDisplayPreferences) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text("HAPTICS", style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Haptic Feedback", Modifier.weight(1f))
            Switch(state.hapticFeedbackEnabled, onCheckedChange = {
                preferences.edit().putBoolean(NativeDisplayPreferences.hapticsKey, it).apply()
            }, modifier = Modifier.testTag("settings.haptics").semantics { contentDescription = "Haptic Feedback" })
        }
        Text("When off, cmux does not vibrate for actions, confirmations, warnings, or errors.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
