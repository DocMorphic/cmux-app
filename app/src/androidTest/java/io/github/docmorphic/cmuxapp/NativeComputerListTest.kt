package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeComputerListTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val team = NativeTeamScope("fixture-login", "fixture-user", "fixture-team", 1)
    private fun mac(id: String, name: String) = NativeCredentialStore.PairedMac(
        PairingCodeParser.computer(IrohV2Computer("record-$id", "$id-peer-0123456789", id, "default", name, emptyList()), team), id, name, "default")
    private val studio = mac("studio", "Studio")
    private val older = mac("old", "Studio")
    private val laptop = mac("laptop", "Laptop")
    private var preferences by mutableStateOf(NativeMacConnectionPreferences(values = mapOf(
        NativeMacIdentity("laptop", "default") to NativeMacConnectionPreference(NativeMacConnectionMethod.DIRECT,
            listOf(NativeDirectAddress("192.168.1.20:58470"))))))
    private val instances = listOf(studio to true, older to false, laptop to true).associate { (mac, status) ->
        val id = NativeMacIdentity(mac.deviceId, mac.instanceTag)
        id to NativeMacPresenceInstance(id, null, status)
    }
    @Test fun savedRowsGroupMethodsShowEndpointsAndMoveAfterPreferenceChangeWithoutSelecting() {
        var selected: NativeCredentialStore.PairedMac? = null
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(vertical = 16.dp)) {
                NativeSavedComputerRows(listOf(older, laptop, studio), NativeMacAppearances(), emptyMap(), null,
                    NativeComputersState(account = team), emptyMap(), NativeMacPresenceState(team, instances),
                    mapOf(studio.origin to System.currentTimeMillis(), laptop.origin to System.currentTimeMillis() - 60_000,
                        older.origin to System.currentTimeMillis() - 120_000), preferences, emptyMap(),
                    NativeComputerForgetCallbacks(), {}, { selected = it })
            }
        } } }
        compose.onNodeWithText("Iroh").assertIsDisplayed()
        compose.onNodeWithText("Direct").assertIsDisplayed()
        compose.onNodeWithText("Presence: Online · 192.168.1.20:58470", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText("Older pairing ·", substring = true), useUnmergedTree = true).assertIsDisplayed()
        capture()
        compose.runOnIdle {
            assertNull(selected)
            preferences = NativeMacConnectionPreferences()
        }
        compose.onNodeWithText("Direct").assertDoesNotExist()
        compose.onNodeWithText("Presence: Online · laptop-peer-…", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Laptop").performClick()
        compose.runOnIdle { assertEquals(laptop, selected) }
    }
    @Test fun reconnectCaptionOmitsDuplicatePresenceAndShowsEndpointWithOlderMarker() {
        compose.setContent { CmuxTheme { Surface {
            NativeComputerRowLabel("Studio", "Nightly", NativeComputerConnection(workspaceCount = 8),
                NativeComputerPresence(true), reconnect = true, routeDescription = "0123456789ab…", olderPairing = true)
        } } }
        compose.onNodeWithText("Online").assertIsDisplayed()
        compose.onNodeWithText("Older pairing · 0123456789ab…").assertIsDisplayed()
        compose.onNode(hasText("Presence:", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("workspaces", substring = true)).assertDoesNotExist()
    }
    private fun capture() {
        val i = InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(i.targetContext.getExternalFilesDir(null), "computer-list").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder, "method-sections.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }; bitmap.recycle()
        }
    }
}
