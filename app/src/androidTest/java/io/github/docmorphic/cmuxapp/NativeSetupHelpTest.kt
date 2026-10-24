package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NativeSetupHelpTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsReferenceRestoresOpenSheetAndKeepsDoneVisibleAtLargeFont() {
        val restoration = StateRestorationTester(compose)
        val opened = mutableListOf<String>()
        var rejectLink = true
        var policy by mutableStateOf(NativeMacCompatibilityPolicy.baked)
        restoration.setContent { CmuxTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f),
                LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) {
                        opened += uri
                        if (rejectLink) throw IllegalStateException("No browser")
                    }
                }) {
                Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding()) { NativeSettingsSetupHelp(policy) } }
            }
        } }
        compose.onNodeWithTag("settings.setup-help").performClick()
        compose.onNodeWithTag("setup.help").assertIsDisplayed()
        val rendered = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Sign in", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(rendered) }
        assertEquals("Dialog must render the requested font scale", 2f, rendered.single().layoutInput.density.fontScale, .01f)
        compose.onNodeWithText("You are here").assertDoesNotExist()
        val gates = listOf("notSignedIn", "signedInNeverPaired", "macUnreachable", "accountMismatch", "network")
        val positions = gates.map { compose.onNodeWithTag("setup.help.gate:$it").getUnclippedBoundsInRoot().top.value }
        assertEquals(positions.sorted(), positions)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("setup.help").assertIsDisplayed()
        val header = compose.onNodeWithTag("setup.help.header").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("setup.help.minimum").performScrollTo().assertTextEquals("Use cmux 0.64.25 or newer on your Mac.")
        compose.runOnIdle { policy = NativeMacCompatibilityPolicy(emptyList()) }
        compose.onNodeWithTag("setup.help.minimum").assertDoesNotExist()
        gates.forEach { compose.onNodeWithTag("setup.help.gate:$it").performScrollTo().assertIsDisplayed() }
        compose.onNodeWithTag("setup.help.tailscale.android").performScrollTo().performClick()
        compose.onNodeWithTag("setup.help.link-error").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { rejectLink = false }
        compose.onNodeWithTag("setup.help.tailscale.computer").performScrollTo().performClick()
        compose.onNodeWithTag("setup.help.link-error").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("https://play.google.com/store/apps/details?id=com.tailscale.ipn",
            "https://tailscale.com/download"), opened) }
        compose.onNodeWithTag("setup.help.footer").performScrollTo().assertIsDisplayed()
        assertEquals(header, compose.onNodeWithTag("setup.help.header").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("setup.help.done").assertIsDisplayed()
        capture("setup-help-large-font")
        compose.onNodeWithTag("setup.help.done").performClick()
        compose.onNodeWithTag("setup.help").assertDoesNotExist()
        compose.onNodeWithTag("settings.setup-help").assertIsDisplayed()
    }

    @Test fun highlightedGateRestoresAndFollowsCurrentGuidanceWithAllOtherStepsVisible() {
        val restoration = StateRestorationTester(compose)
        var highlight by mutableStateOf(NativeSetupGuidance.MAC_UNREACHABLE)
        restoration.setContent { CmuxTheme { Surface { Column {
            NativeSetupHelpButton(NativeMacCompatibilityPolicy.baked, highlight, "fixture.setup-help")
        } } } }
        compose.onNodeWithTag("fixture.setup-help").performClick()
        fun assertHighlighted(gate: NativeSetupGuidance) {
            val order = NativeSetupGuidance.ordered(gate).map { it.id }
            val positions = order.map { compose.onNodeWithTag("setup.help.gate:$it").getUnclippedBoundsInRoot().top.value }
            assertEquals(positions.sorted(), positions)
            compose.onNodeWithTag("setup.help.you-are-here").assertExists()
                .assert(hasAnyAncestor(hasTestTag("setup.help.gate:${gate.id}")))
        }
        assertHighlighted(highlight)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("setup.help").assertIsDisplayed()
        assertHighlighted(highlight)
        compose.runOnIdle { highlight = NativeSetupGuidance.ACCOUNT_MISMATCH }
        assertHighlighted(highlight)
        capture("highlighted-account-gate")
        compose.onNodeWithTag("setup.help.done").performClick()
        compose.onNodeWithTag("setup.help").assertDoesNotExist()
        compose.onNodeWithTag("fixture.setup-help").assertIsDisplayed()
    }

    @Test fun signInHelpHighlightsSignInWithoutSendingCodeOrChangingEnteredEmail() {
        var actions = 0
        compose.setContent { CmuxTheme { Surface {
            NativeSignIn({ actions++ }, { actions++ }, { actions++ }, { actions++ },
                NativeMacCompatibilityPolicy.baked, { actions++ })
        } } }
        compose.onNodeWithTag("signin.email").performTextInput("fixture@example.invalid")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("signin.options").performClick()
        compose.onNodeWithTag("signin.setup-help").performClick()
        compose.onNodeWithTag("setup.help.you-are-here").assertExists()
            .assert(hasAnyAncestor(hasTestTag("setup.help.gate:notSignedIn")))
        compose.onNodeWithTag("setup.help.done").performClick()
        compose.onNodeWithTag("signin.email").assertTextContains("fixture@example.invalid")
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun highlightedLargeTextPlacesMarkerBelowScaledHeadingWithoutOverlap() {
        compose.setContent { CmuxTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                NativeSetupHelp(NativeMacCompatibilityPolicy.baked, NativeSetupGuidance.MAC_UNREACHABLE) {}
            }
        } }
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Wake the computer", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertEquals(2f, layout.single().layoutInput.density.fontScale, .01f)
        val heading = compose.onNodeWithText("Wake the computer", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val marker = compose.onNodeWithTag("setup.help.you-are-here").getUnclippedBoundsInRoot()
        assertTrue(marker.top >= heading.bottom)
        compose.onNodeWithTag("setup.help.you-are-here").assert(hasAnyAncestor(hasTestTag("setup.help.gate:macUnreachable")))
        compose.onNodeWithTag("setup.help.gate:macUnreachable").performScrollTo()
        compose.onNodeWithTag("setup.help.done").assertIsDisplayed()
        capture("highlighted-large-font")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "setup-help").apply { mkdirs() }
        try { File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
