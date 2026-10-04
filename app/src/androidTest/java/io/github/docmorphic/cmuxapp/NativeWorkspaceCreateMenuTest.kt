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

class NativeWorkspaceCreateMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val stable = NativeCredentialStore.PairedMac("route-stable", "mac", "Stable Mac", "default")
    private val nightly = stable.copy(code = "route-nightly", name = "Nightly Mac", instanceTag = "nightly")
    private var rows by mutableStateOf(listOf(stable, nightly))
    private var selection by mutableStateOf<String?>(null)
    private var owner by mutableStateOf(NativeComputerMenuOwner("login", null))
    private var open by mutableStateOf(false)
    private var connected by mutableStateOf(setOf(stable.origin, nightly.origin))
    private var busy by mutableStateOf(false)
    private var allowed = true
    private val created = mutableListOf<NativeCredentialStore.PairedMac>()

    private fun content() {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.statusBarsPadding()) {
            NativeWorkspaceCreateMenu(rows, NativeMacAppearances(), NativeMacPresenceState(),
                rows.associate { NativeMacIdentity(it.deviceId, it.instanceTag) to NativeComputerConnection(
                    if (it.origin in connected) NativeFeedAvailability.CONNECTED else NativeFeedAvailability.OFFLINE) },
                open, { open = it }, owner, selection, busy, { it == owner && allowed },
                { NativeComputerMenuPairing.isCurrent(it, rows) && it.origin in connected },
                { created += it }, {}, null)
        } } } }
    }
    private fun openMenu() = compose.onNodeWithContentDescription("New workspace").performClick()

    @Test fun sameDeviceDifferentBuildUsesDisplayedTargetAndNextOpeningUpdatesItsName() {
        content(); openMenu()
        compose.onAllNodesWithContentDescription("Computer status: Connected").assertCountEquals(2)
        compose.runOnIdle { rows = listOf(nightly.copy(name = "Renamed nightly"), stable) }
        compose.onNodeWithText("Nightly Mac").performClick()
        compose.runOnIdle { assertEquals(listOf(nightly), created) }
        openMenu(); compose.onNodeWithText("Renamed nightly").assertIsDisplayed()
    }

    @Test fun replacementDisconnectionAndAuthorityRevocationCannotSendAnOldMenuAction() {
        content(); openMenu()
        compose.runOnIdle { rows = listOf(stable, nightly.copy(code = "replacement")) }
        compose.onNodeWithText("Nightly Mac").assertIsNotEnabled()
        compose.runOnIdle { connected = emptySet() }
        compose.onNodeWithText("Stable Mac").assertIsNotEnabled()
        compose.runOnIdle { connected = setOf(stable.origin); allowed = false }
        compose.onNodeWithText("Stable Mac").performClick()
        compose.runOnIdle { assertTrue(created.isEmpty()) }
    }

    @Test fun accountAndFilterChangesDismissTargetsAndSingleSelectionCannotChooseOtherMac() {
        content(); openMenu()
        compose.runOnIdle { owner = owner.copy(login = "new-login") }
        compose.waitUntil { !open }
        openMenu()
        compose.runOnIdle { selection = nightly.origin; rows = listOf(nightly) }
        compose.waitUntil { !open }
        openMenu()
        compose.onNodeWithText("Stable Mac").assertDoesNotExist()
        compose.onNode(hasText("New workspace") and hasAnyAncestor(isPopup())).performClick()
        compose.runOnIdle { assertEquals(listOf(nightly), created); busy = true }
        compose.onNodeWithContentDescription("New workspace").assertIsNotEnabled()
    }
}
