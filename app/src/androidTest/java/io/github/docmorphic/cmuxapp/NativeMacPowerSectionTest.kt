package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeMacPowerSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun disconnectedAndUnsupportedMacCannotToggle() {
        var state by mutableStateOf(NativeMacPowerState())
        var changes = 0
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding()) {
            NativeMacPowerSection(state, { changes++ }, {})
        } } }
        compose.onNodeWithContentDescription("Keep Mac Awake").assertIsNotEnabled().assertIsOff().performClick()
        compose.onNodeWithText("Connect to this Mac to control Keep Mac Awake.").assertIsDisplayed()
        compose.runOnIdle { state = NativeMacPowerState(connected = true, supported = false) }
        compose.onNodeWithText("Update cmux on this Mac to control Keep Mac Awake from Android.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Keep Mac Awake").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, changes) }
    }

    @Test fun confirmedStateTogglesOnceAndPendingChangeIsDisabled() {
        var state by mutableStateOf(NativeMacPowerState(connected = true, supported = true, enabled = false))
        val changes = mutableListOf<Boolean>()
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding()) {
            NativeMacPowerSection(state, { changes += it; state = state.copy(enabled = it, busy = true) }, {})
        } } }
        compose.onNodeWithContentDescription("Keep Mac Awake").assertIsEnabled().assertIsOff().performClick()
        compose.onNodeWithContentDescription("Keep Mac Awake").assertIsOn().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(true), changes); state = state.copy(enabled = false, busy = false) }
        compose.onNodeWithContentDescription("Keep Mac Awake").assertIsOff().assertIsEnabled()
        compose.onNodeWithText("Prevents this Mac from sleeping while cmux is open. Its display can still turn off.").assertIsDisplayed()
        capture("mac-power-confirmed")
    }

    @Test fun unknownOutcomeOffersReadRetryAndNeverShowsAnOffSwitch() {
        var state by mutableStateOf(NativeMacPowerState(connected = true, supported = true,
            error = NativeMacPowerState.SET_ERROR))
        var retries = 0
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding()) {
            NativeMacPowerSection(state, { error("Unknown status cannot be mutated") }, {
                retries++; state = state.copy(busy = true)
            })
        } } }
        compose.onNodeWithContentDescription("Keep Mac Awake").assertDoesNotExist()
        compose.onNodeWithText(NativeMacPowerState.SET_ERROR).assertIsDisplayed()
        capture("mac-power-unknown")
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithContentDescription("Loading Mac Power").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, retries); state = state.copy(enabled = true, busy = false, error = null) }
        compose.onNodeWithContentDescription("Keep Mac Awake").assertIsOn().assertIsEnabled()
        compose.onNodeWithText(NativeMacPowerState.SET_ERROR).assertDoesNotExist()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val root = instrumentation.targetContext.getExternalFilesDir(null)!!
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(root, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
