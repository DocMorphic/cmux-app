package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class NativeTerminalSizingPreferencesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun noticeRetiresWithOwnerOrScreenAndSettingsRestorePersistedSuppressionAtLargeFont() {
        val name = "terminal-sizing-${UUID.randomUUID()}"
        val preferences = compose.activity.getSharedPreferences(name, Context.MODE_PRIVATE)
        var owner by mutableStateOf(Any())
        var alternate by mutableStateOf(true)
        var generation by mutableIntStateOf(0)
        var state = NativeDisplayPreferences()
        try {
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    key(generation) {
                        state = rememberNativeDisplayPreferences(preferences)
                        Column(Modifier.statusBarsPadding()) {
                            NativeTerminalSizingSettings(preferences, state)
                            NativeAltScreenNotice(owner, "terminal", alternate && state.showAltScreenNotice) {
                                preferences.edit().putBoolean(NativeDisplayPreferences.altScreenNoticeKey, false).apply()
                            }
                        }
                    }
                }
            } } }
            fun open() = compose.onNodeWithContentDescription("Explain full-screen terminal sizing").performClick()
            open()
            compose.onNodeWithText("Full-screen terminal app").assertExists()
            compose.runOnIdle { owner = Any() }
            compose.onNodeWithText("Full-screen terminal app").assertDoesNotExist()
            open()
            compose.runOnIdle { alternate = false }
            compose.onNodeWithText("Full-screen terminal app").assertDoesNotExist()
            compose.onNodeWithContentDescription("Explain full-screen terminal sizing").assertDoesNotExist()
            compose.runOnIdle { alternate = true }
            open()
            compose.onNodeWithText("Don't Show Again").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil { !state.showAltScreenNotice }
            compose.runOnIdle { generation++ }
            compose.onNodeWithContentDescription("Explain full-screen terminal sizing").assertDoesNotExist()
            compose.onNodeWithTag("settings.alt-screen-notice").assertIsOff().performClick()
            compose.waitUntil { state.showAltScreenNotice }
            compose.onNodeWithContentDescription("Explain full-screen terminal sizing").assertIsDisplayed()
            compose.onNodeWithTag("settings.full-terminal-height").assertIsOff().performClick()
            compose.waitUntil { state.useFullTerminalHeight }
            compose.runOnIdle { generation++ }
            compose.onNodeWithTag("settings.full-terminal-height").assertIsOn()
            compose.runOnIdle {
                preferences.edit().putString(NativeDisplayPreferences.altScreenNoticeKey, "invalid")
                    .putInt(NativeDisplayPreferences.fullTerminalHeightKey, 1).commit()
            }
            compose.waitUntil { state.showAltScreenNotice && !state.useFullTerminalHeight }
        } finally { compose.activity.deleteSharedPreferences(name) }
    }
}
