package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Only an injected operation is invoked. Never clears a signed-in device. */
class NativeLocalResetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val action = "Erase All Data on This Device"

    @Test fun openingAndCancellingNeverErasesData() {
        var requests = 0
        compose.setContent { CmuxTheme { Surface { NativeLocalResetSection { requests++; true } } } }
        compose.onNodeWithText(action).performClick()
        compose.onNodeWithText("Erase all cmux data on this device?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Erase").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithText(action).assertIsEnabled()
    }

    @Test fun confirmedRequestIsSingleUseWhileAndroidClearsData() {
        var requests = 0
        compose.setContent { CmuxTheme { Surface { NativeLocalResetSection { requests++; true } } } }
        compose.onNodeWithText(action).performClick()
        compose.onNodeWithText("Erase").performClick()
        compose.onNodeWithText("Erase").assertDoesNotExist()
        compose.onNodeWithText("Erasing local data…").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun rejectedOrThrowingPlatformRequestShowsFailureAndRequiresNewConfirmation() {
        var requests = 0
        compose.setContent { CmuxTheme { Surface { NativeLocalResetSection {
            requests++
            if (requests == 1) false else throw SecurityException("test refusal")
        } } } }
        repeat(2) {
            compose.onNodeWithText(action).performClick()
            compose.onNodeWithText("Erase").performClick()
            compose.onNodeWithText("Android could not start the reset.", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Erase").assertDoesNotExist()
            compose.onNodeWithText(action).assertIsEnabled()
        }
        compose.runOnIdle { assertEquals(2, requests) }
    }
}
