package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NativeSettingsConnectionRowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val mac = NativeCredentialStore.PairedMac("fixture-route", "mac", "Long computer name that should remain readable", "default")
    private val owner = NativeComputerMenuOwner("login-a", NativeTeamScope("login-a", "user", "team", 1))
    private fun row(mac: NativeCredentialStore.PairedMac = this.mac, kind: MobileConnectionTransport = MobileConnectionTransport.IROH) =
        NativeSettingsConnectionRow(mac, NativeComputerConnection(NativeFeedAvailability.CONNECTED, transport = kind))

    @Test fun sameInstanceOwnersKeepSeparateTransportAndCapturedRowActionsCannotRetarget() {
        val original = mac.copy(name = "Original Mac", accountUserId = "user", accountTeamId = "original", stableOrigin = "origin-a")
        val picker = original.copy(name = "Picker Mac", accountTeamId = "picker", stableOrigin = "origin-b")
        val source = NativeFeedSource(picker, availability = NativeFeedAvailability.CONNECTED, connectionTransport = MobileConnectionTransport.IROH)
        val projection = nativeRecordComputerConnections(listOf(original, picker), mapOf(picker.origin to source), emptyList(),
            MobileConnectionTransport.TAILSCALE, { it == original }, { false })
        var rows by mutableStateOf(nativeSettingsConnectionRows(listOf(original, picker), projection))
        val actions = mutableListOf<NativeCredentialStore.PairedMac>()
        compose.setContent { CmuxTheme { Surface { Column {
            NativeSettingsConnections(owner, rows, NativeMacAppearances(), { actions += it }, {})
        } } } }
        compose.onNodeWithText("Original Mac").assertHasClickAction(); compose.onNodeWithText("Picker Mac").assertHasClickAction()
        compose.onNodeWithText("Tailscale", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Iroh", useUnmergedTree = true).assertExists()
        val originalAction = compose.onNodeWithText("Original Mac").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithText("Picker Mac").performClick()
        compose.runOnIdle { assertEquals(listOf(picker), actions); rows = rows.filter { it.mac == picker } }
        compose.waitForIdle(); compose.runOnIdle { originalAction(); assertEquals(listOf(picker), actions) }
        compose.onNodeWithText("Original Mac").assertDoesNotExist()
        compose.onNodeWithText("Picker Mac").performClick()
        compose.runOnIdle { assertEquals(listOf(picker, picker), actions) }
    }

    @Test fun siblingBuildRowsShowActualTransportAndAllComputersRemainsAvailableAtLargeFont() {
        val sibling = mac.copy(code = "other-route", instanceTag = "nightly", name = "Another build")
        val selected = mutableListOf<NativeCredentialStore.PairedMac>(); var computers = 0
        var rows by mutableStateOf(listOf(row(), row(sibling, MobileConnectionTransport.TAILSCALE)))
        compose.setContent { CmuxTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Surface { Column(Modifier.width(260.dp).height(720.dp).verticalScroll(rememberScrollState())) {
                    NativeSettingsConnections(owner, rows, NativeMacAppearances(), { selected += it }, { computers++ })
                } }
            }
        } }
        compose.onNodeWithTag("settings.connection:mac:default").performScrollTo().assertHasClickAction().performClick()
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(mac.name, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertEquals(2f, layout.single().layoutInput.density.fontScale, .01f)
        assertTrue(layout.single().lineCount > 1); assertFalse(layout.single().hasVisualOverflow)
        val nameBounds = compose.onNodeWithText(mac.name, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val transportBounds = compose.onNodeWithText("Iroh", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Large text places the method below the full-width name", transportBounds.top >= nameBounds.bottom)
        capture("settings-connection-large-font")
        compose.onNodeWithTag("settings.connection:mac:nightly").performScrollTo().performClick()
        compose.onNodeWithText("Tailscale", useUnmergedTree = true).assertExists()
        compose.runOnIdle { assertEquals(listOf(mac, sibling), selected); rows = emptyList() }
        compose.onNodeWithTag("settings.connection:mac:default").assertDoesNotExist()
        compose.onNodeWithTag("settings.connection:mac:nightly").assertDoesNotExist()
        compose.onNodeWithTag("settings.computers").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, computers) }
    }

    @Test fun cachedRowActionRechecksOwnerMembershipAndComponentLifetime() {
        var currentOwner by mutableStateOf(owner)
        var rows by mutableStateOf(listOf(row()))
        var showing by mutableStateOf(true)
        val selected = mutableListOf<NativeCredentialStore.PairedMac>()
        compose.setContent { CmuxTheme { Surface { Column {
            if (showing) NativeSettingsConnections(currentOwner, rows, NativeMacAppearances(), { selected += it }, {})
        } } } }
        val action = compose.onNodeWithTag("settings.connection:mac:default").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { rows = listOf(row(mac.copy(deviceId = "replacement"))) }
        compose.waitForIdle(); compose.runOnIdle { action(); assertTrue(selected.isEmpty()) }
        compose.runOnIdle { rows = listOf(row()); currentOwner = owner.copy(login = "login-b") }
        compose.waitForIdle(); compose.runOnIdle { action(); assertTrue(selected.isEmpty()) }
        compose.runOnIdle { currentOwner = owner }
        compose.waitForIdle(); compose.runOnIdle { action(); assertEquals(listOf(mac), selected); showing = false }
        compose.waitForIdle(); compose.runOnIdle { action(); assertEquals(listOf(mac), selected) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "settings-connections").apply { mkdirs() }
        try { File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
