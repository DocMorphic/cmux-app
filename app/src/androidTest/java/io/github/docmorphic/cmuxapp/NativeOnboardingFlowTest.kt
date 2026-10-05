package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeOnboardingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(i.targetContext.getExternalFilesDir(null), "onboarding").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun permissionIsExplicitSingleFlightAndConnectionRequiresExplicitCompletion() {
        var busy by mutableStateOf(false); var result by mutableLongStateOf(0)
        var phase by mutableStateOf(NativeOnboardingPhase.IDLE)
        var enables = 0; var reached = 0; var completed = 0; var scans = 0
        compose.setContent { CmuxTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            NativeOnboardingFlow(NativeOnboardingProgress.WELCOME, false, phase, "Test Mac", NativeMacCompatibilityPolicy.baked,
                busy, result, null, { enables++; busy = true }, { reached++ }, { completed++ }, {}, { scans++ }, {}, {})
        } } } }
        compose.onNodeWithTag("onboarding.title.AGENTS").assertIsDisplayed(); capture("agents")
        compose.onNodeWithTag("onboarding.pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("onboarding.title.NOTIFICATIONS").assertIsDisplayed(); capture("notifications")
        compose.onNodeWithTag("onboarding.primary").performClick()
        compose.onNodeWithTag("onboarding.title.PUSH").assertIsDisplayed(); capture("push")
        compose.runOnIdle { assertEquals(0, enables) }
        compose.onNodeWithTag("onboarding.primary").performClick()
        compose.onNodeWithTag("onboarding.primary").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("onboarding.secondary").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, enables); busy = false; result++ }
        compose.onNodeWithTag("onboarding.title.PAIRING").assertIsDisplayed()
        compose.onNodeWithTag("onboarding.skip").assertDoesNotExist(); capture("pairing")
        compose.onNodeWithTag("onboarding.primary").performClick()
        compose.onNodeWithTag("onboarding.title.CONNECT").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, reached); phase = NativeOnboardingPhase.READY }
        compose.onNodeWithTag("onboarding.primary").assertTextEquals("Open Workspaces")
        compose.onNodeWithTag("onboarding.tailscale").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, scans); assertEquals(0, completed) }
        capture("connected")
        compose.onNodeWithTag("onboarding.primary").performClick()
        compose.runOnIdle { assertEquals(1, completed) }
    }
    @Test fun restoredConnectKeepsPublicDraftWithoutRepeatingEntryOrPairing() {
        val restore = StateRestorationTester(compose)
        var reached = 0; var completed = 0; var paired: String? = null
        restore.setContent { CmuxTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            NativeOnboardingFlow(NativeOnboardingProgress.CONNECT, false, NativeOnboardingPhase.FALLBACK, null,
                NativeMacCompatibilityPolicy.baked, false, 0, null, {}, { reached++ }, { completed++ }, {}, {}, { paired = it }, {})
        } } } }
        compose.onNodeWithTag("onboarding.title.CONNECT").assertIsDisplayed()
        compose.onNodeWithTag("onboarding.tailscale").performScrollTo().performClick()
        compose.onNodeWithText("Paste a pairing code").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding.paste").performScrollTo().performTextInput("cmux-ios://attach?v=2&r=100.64.0.7:58465")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("onboarding.paste").performScrollTo().assertTextContains("cmux-ios://attach?v=2&r=100.64.0.7:58465")
        compose.runOnIdle { assertEquals(1, reached); assertNull(paired); assertEquals(0, completed) }
        compose.onNodeWithTag("onboarding.back").performClick()
        compose.onNodeWithTag("onboarding.title.PAIRING").assertIsDisplayed()
        compose.onNodeWithTag("onboarding.primary").performClick()
        compose.runOnIdle { assertEquals(1, reached) }
    }
    @Test fun unverifiedReplayCanExplainSetupButRoutesConnectionToAccountSettings() {
        var settings = 0; var requests = 0
        compose.setContent { CmuxTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            NativeOnboardingFlow(NativeOnboardingProgress.COMPLETE, true, NativeOnboardingPhase.FALLBACK, null,
                NativeMacCompatibilityPolicy.baked, false, 0, "Refresh your account in Settings before connecting to a Mac.",
                {}, {}, {}, { requests++ }, { requests++ }, { requests++ }, { settings++ }, canConnect = false)
        } } } }
        compose.onNodeWithTag("onboarding.primary").performClick()
        compose.onNodeWithTag("onboarding.primary").performClick()
        compose.onNodeWithTag("onboarding.secondary").performClick()
        compose.onNodeWithTag("onboarding.primary").performClick()
        compose.onNodeWithTag("onboarding.primary").assertTextEquals("Open Settings")
        compose.onNodeWithTag("onboarding.tailscale").performScrollTo().performClick()
        compose.onNodeWithText("Paste a pairing code").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding.paste").performScrollTo().performTextInput("cmux-ios://test")
        compose.onNodeWithText("Connect").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("onboarding.primary").performClick()
        compose.runOnIdle { assertEquals(0, requests); assertEquals(1, settings) }
    }
    @Test fun latePermissionResultDoesNotMoveUserBackAndReplayStartsAtWelcome() {
        var result by mutableLongStateOf(0); var enables = 0; var complete = 0
        compose.setContent { CmuxTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            NativeOnboardingFlow(NativeOnboardingProgress.COMPLETE, true, NativeOnboardingPhase.READY, "Mac",
                NativeMacCompatibilityPolicy.baked, false, result, null, { enables++ }, {}, { complete++ }, {}, {}, {}, {})
        } } } }
        compose.onNodeWithTag("onboarding.title.AGENTS").assertIsDisplayed()
        compose.onNodeWithTag("onboarding.primary").performClick()
        compose.onNodeWithTag("onboarding.primary").performClick()
        compose.onNodeWithTag("onboarding.primary").performClick()
        compose.onNodeWithTag("onboarding.back").performClick()
        compose.runOnIdle { result++ }
        compose.onNodeWithTag("onboarding.title.NOTIFICATIONS").assertIsDisplayed()
        compose.onNodeWithTag("onboarding.skip").performClick()
        compose.runOnIdle { assertEquals(1, enables); assertEquals(1, complete) }
    }
}
