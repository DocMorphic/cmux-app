package io.github.docmorphic.cmuxapp

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class NativeSettingsToggleRowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun productionSettingsHaveOneSwitchPerRowAndLabelTapsPersistAtLargeFont() {
        val name = "settings-row-${UUID.randomUUID()}"
        val preferences = compose.activity.getSharedPreferences(name, Context.MODE_PRIVATE)
        var state = NativeDisplayPreferences()
        var generation by mutableIntStateOf(0)
        try {
            compose.setContent { CmuxTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Surface { Column(Modifier.width(260.dp).verticalScroll(rememberScrollState())) { key(generation) {
                        state = rememberNativeDisplayPreferences(preferences)
                        NativeTerminalSizingSettings(preferences, state)
                        NativeHapticSettings(preferences, state)
                        NativeDisplaySettings(preferences, state)
                    } } }
                }
            } }
            val tags = listOf("settings.alt-screen-notice", "settings.folder-tap", "settings.full-terminal-height",
                "settings.haptics", "settings.missing-files", "settings.wrap-titles", "settings.legacy-notifications",
                "settings.feed-tab") + if (BuildConfig.DEBUG) listOf("settings.feed-bubbles") else emptyList()
            val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
            compose.onAllNodes(switch).assertCountEquals(tags.size)
            tags.forEach { tag -> compose.onNodeWithTag(tag).assertHasClickAction() }
            fun tapLabel(tag: String) = compose.onNodeWithTag(tag).performScrollTo()
                .performTouchInput { click(Offset(12f, centerY)) }
            tapLabel("settings.folder-tap")
            compose.waitUntil { !state.terminalFolderTapEnabled }
            compose.onNodeWithTag("settings.folder-tap").assertIsOff()
            assertEquals(false, preferences.all[NativeDisplayPreferences.folderTapKey])
            tapLabel("settings.missing-files")
            compose.waitUntil { state.showMissingFiles }
            assertEquals(true, preferences.all[NativeDisplayPreferences.missingFilesKey])
            tapLabel("settings.wrap-titles")
            compose.waitUntil { state.wrapTitles }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Wrap Workspace Titles", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.single().lineCount > 1); assertFalse(layouts.single().hasVisualOverflow)
            compose.waitForIdle()
            val bounds = compose.onNodeWithTag("settings.wrap-titles").fetchSemanticsNode().boundsInWindow
            val thumbX = (bounds.right - 16 * compose.activity.resources.displayMetrics.density).toInt()
            val thumbY = bounds.center.y.toInt()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                try {
                    val thumb = bitmap.getPixel(thumbX, thumbY)
                    assertTrue("Checked settings knob should paint the app's light foreground",
                        android.graphics.Color.red(thumb) > 220 && android.graphics.Color.green(thumb) > 220 &&
                            android.graphics.Color.blue(thumb) > 220)
                    compose.activity.openFileOutput("settings-large-font.png", Context.MODE_PRIVATE).use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { bitmap.recycle() }
            }
            compose.runOnIdle { generation++ }
            compose.onNodeWithTag("settings.wrap-titles").performScrollTo().assertIsOn()
            compose.runOnIdle { preferences.edit().putString(NativeDisplayPreferences.folderTapKey, "invalid")
                .putInt(NativeDisplayPreferences.missingFilesKey, 1).commit() }
            compose.waitUntil { state.terminalFolderTapEnabled && !state.showMissingFiles }
            compose.runOnIdle { preferences.edit().clear().commit() }
            compose.waitUntil { state == NativeDisplayPreferences() }
        } finally { compose.activity.deleteSharedPreferences(name) }
    }

    @Test fun disabledRowsHaveNoEnabledClickTargetAndCannotChangeValue() {
        var changed = false
        compose.setContent { CmuxTheme { Surface {
            NativeSettingsToggleRow("Unavailable setting", true, { changed = true },
                Modifier.testTag("disabled-row"), enabled = false)
        } } }
        compose.onNodeWithTag("disabled-row").assertIsNotEnabled().assertIsOn()
            .performTouchInput { click() }
        compose.runOnIdle { assertFalse(changed) }
    }
}
