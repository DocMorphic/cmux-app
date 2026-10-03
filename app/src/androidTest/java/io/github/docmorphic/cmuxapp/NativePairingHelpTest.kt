package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativePairingHelpTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(i.targetContext.getExternalFilesDir(null), "pairing-help").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun policyChangesAndSignedOutHelpUseRealScreenshotAndFixedDownloadWithoutPairing() {
        var policy by mutableStateOf(NativeMacCompatibilityPolicy.baked)
        var light by mutableStateOf(false)
        var found = 0; var paired = 0; var closed = 0; var url: String? = null
        compose.setContent {
            MaterialTheme(colorScheme = if (light) lightColorScheme() else darkColorScheme()) {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { url = uri }
                }) {
                    NativePairingHelp(policy, false, { closed++ }, { found++ }, { paired++ })
                }
            }
        }
        compose.onNodeWithText("Enable iOS pairing on your Mac").assertIsDisplayed()
        compose.onNodeWithTag("pairing.help.screenshot").assertIsDisplayed()
        compose.onNodeWithTag("pairing.help.minimum").performScrollTo().assertTextEquals("Use cmux 0.64.25 or newer on your Mac.")
        capture("dark")
        compose.onNodeWithText("Download cmux for Mac").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("https://github.com/manaflow-ai/cmux/releases/latest", url) }
        compose.onNodeWithTag("pairing.help.tailscale").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, paired); policy = NativeMacCompatibilityPolicy(emptyList()); light = true }
        compose.onNodeWithTag("pairing.help.minimum").assertDoesNotExist()
        compose.onNodeWithText("Enable iOS pairing on your Mac").performScrollTo()
        compose.onNodeWithTag("pairing.help.screenshot").assertIsDisplayed()
        capture("light")
        compose.onNodeWithText("Back to sign in").performScrollTo().performClick()
        compose.onNodeWithTag("pairing.help.done").performClick()
        compose.runOnIdle { assertEquals(1, found); assertEquals(1, closed); assertEquals(0, paired) }
    }

    @Test fun pickerRestoresOpenHelpAndPastedCodeThenRoutesEachActionExactlyOnce() {
        val restoration = StateRestorationTester(compose)
        var refreshes = 0; val pairings = mutableListOf<String>()
        val team = NativeTeamScope("fixture", "user", "team", 1)
        val draft = "cmux-ios://attach?v=2&r=100.64.0.8:58465"
        restoration.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding()) {
            NativeComputerPicker(NativeAccountTeamsState(userId = "user", selectedTeamId = "team", scope = team),
                NativeComputersState(team, ready = true), canSelectSaved = { false }, canSelectDiscovered = { false },
                onSelectSaved = {}, onSelect = {}, onSettings = {}, onRefresh = { refreshes++ },
                onPairing = { pairings += it }, onNewTask = {}, onUseHelper = {}, onLicenses = {}, onError = {})
        } } } }
        compose.onNodeWithText("Scan or paste a pairing code").performScrollTo().performClick()
        compose.onNodeWithText("Or paste pairing code").performScrollTo().performTextInput(draft)
        compose.onNodeWithTag("computers.pairing.help").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("pairing.help").assertIsDisplayed()
        compose.onNodeWithTag("pairing.help.tailscale").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("pairing.help").assertDoesNotExist()
        compose.onNodeWithText(draft).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, refreshes); assertTrue(pairings.isEmpty()) }
        compose.onNodeWithText("Connect", substring = false).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(draft), pairings) }
        compose.onNodeWithTag("computers.pairing.help").performScrollTo().performClick()
        compose.onNodeWithTag("pairing.help.find").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, refreshes); assertEquals(listOf(draft), pairings) }
        compose.onNodeWithTag("pairing.help").assertDoesNotExist()
    }
}
