package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class NativeReconnectComputersTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val team = NativeTeamScope("reconnect-login", "reconnect-user", "reconnect-team", 1)
    private val mac = NativeCredentialStore.PairedMac("cmux-ios://attach?v=2&r=100.64.0.1:58465", "saved-mac", "Studio", "default")
    private val same = IrohV2Computer("record", "peer", "saved-mac", "default", "Directory name", emptyList())
    private val newMac = same.copy(recordId = "new", deviceId = "new-mac", name = "Laptop")
    private var state by mutableStateOf(NativeComputersState(team, loading = true, computers = listOf(same, newMac)))
    private var connecting by mutableStateOf<String?>(null)
    private var failure by mutableStateOf<String?>(null)
    private val permitted = AtomicBoolean(true)
    private var selections = 0
    private var cancels = 0
    private fun content() = compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            NativeComputerPicker(NativeAccountTeamsState(scope = team), state,
                saved = listOf(mac), lastSeenHistory = mapOf(mac.origin to System.currentTimeMillis() - 120_000),
                presence = NativeMacPresenceState(team, mapOf(NativeMacIdentity("saved-mac", "default") to
                    NativeMacPresenceInstance(NativeMacIdentity("saved-mac", "default"), null, false))),
                connectingCode = connecting, connectionFailure = failure,
                onCancelConnect = { cancels++; connecting = null },
                canSelectSaved = { permitted.get() }, canSelectDiscovered = { permitted.get() },
                onSelectSaved = { assertEquals(mac, it); selections++; connecting = it.code },
                onSelect = { selections++ }, onSettings = {}, onRefresh = {}, onPairing = {}, onNewTask = {}, onUseHelper = {},
                onLicenses = {}, onError = {})
        }
    } } }
    @Test fun savedOfflineMacSurvivesLoadingFailureAndEmptyDirectoryWithoutDuplicateRow() {
        content()
        compose.onNodeWithText("Your Computers").assertIsDisplayed()
        compose.onNodeWithText("Studio").assertIsDisplayed()
        compose.onNodeWithText("Directory name").assertDoesNotExist()
        compose.onNodeWithText("Other computers").assertIsDisplayed()
        compose.runOnIdle { state = NativeComputersState(team, error = "Discovery unavailable") }
        compose.onNodeWithText("Studio").assertIsDisplayed()
        compose.onNodeWithText("Discovery unavailable").assertIsDisplayed()
        compose.onNodeWithText("Other computers").assertDoesNotExist()
        compose.onNode(hasText("Last seen", substring = true)).assertIsDisplayed()
        capture()
        compose.runOnIdle { state = NativeComputersState(team, ready = true) }
        compose.onNodeWithText("No computers available in this team.").assertDoesNotExist()
        compose.onNodeWithText("Studio").performClick()
        compose.runOnIdle { assertEquals(1, selections) }
    }
    @Test fun currentAuthorityIsCheckedAtTapAndPendingAttemptPreventsReentryUntilCancel() {
        content()
        permitted.set(false)
        compose.onNodeWithText("Studio").performClick()
        compose.onNodeWithText("Laptop").performClick()
        compose.runOnIdle { assertEquals(0, selections) }
        permitted.set(true)
        compose.onNodeWithText("Studio").performClick()
        compose.onNodeWithContentDescription("Computer status: Reconnecting…").assertIsDisplayed()
        compose.onNodeWithText("Laptop").performClick()
        compose.onNodeWithText("Studio").performClick()
        compose.runOnIdle { assertEquals(1, selections) }
        compose.onNodeWithText("Cancel connection").performClick()
        compose.runOnIdle { failure = "Could not connect to this Mac" }
        compose.onNodeWithText("Could not connect to this Mac").assertIsDisplayed()
        compose.onNodeWithText("Studio").performClick()
        compose.runOnIdle { assertEquals(2, selections); assertEquals(1, cancels) }
    }
    private fun capture() {
        val i = InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(i.targetContext.getExternalFilesDir(null), "reconnect-computers").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder, "offline-saved.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }; bitmap.recycle()
        }
    }
}
