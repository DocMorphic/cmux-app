package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeComputerSelectorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val a = NativeCredentialStore.PairedMac("route-a", "a", "Mac A", "default", stableOrigin = "a")
    private val b = NativeCredentialStore.PairedMac("route-b", "b", "Mac B", "nightly", stableOrigin = "b")
    private var rows by mutableStateOf(listOf(a, b))
    private var selected by mutableStateOf<NativeCredentialStore.PairedMac?>(a)
    private var pending by mutableStateOf<NativeCredentialStore.PairedMac?>(null)
    private var appearances by mutableStateOf(NativeMacAppearances())
    private var connections by mutableStateOf(mapOf<NativeMacIdentity, NativeComputerConnection>())
    private var owner by mutableStateOf(NativeComputerMenuOwner("login", NativeTeamScope("login", "user", "team", 1)))
    private var open by mutableStateOf(false)
    private var version by mutableIntStateOf(1)
    private var permitted = true
    private var canAdd by mutableStateOf(true)
    private var presence by mutableStateOf(NativeMacPresenceState())
    private val actions = mutableListOf<String>()

    private fun content() {
        compose.setContent {
            val callbackVersion = version
            CmuxTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.statusBarsPadding()) {
                NativeComputerSelector(rows, selected, appearances, emptyMap(), connections, open, { open = it },
                    { actions += "$callbackVersion:${it?.code ?: "all"}" }, pending,
                    if (canAdd) ({ actions += "$callbackVersion:pair" }) else null, owner, { it == owner && permitted },
                    { NativeComputerMenuPairing.isCurrent(it, rows) }, presence)
            } } }
        }
    }
    private fun openMenu() = compose.onNodeWithContentDescription("Computer filter").performClick()

    @Test fun openRowsStayFixedThroughRefreshWhileToolbarUpdatesAndNextOpeningUsesNewState() {
        content(); openMenu()
        repeat(20) { n -> compose.runOnIdle {
            rows = listOf(b, a.copy(name = "Updated $n"))
            selected = b; pending = b; version = n + 2
            appearances = NativeMacAppearances(mapOf(NativeMacIdentity("b", "nightly") to NativeMacAppearance(name = "Studio $n")))
            connections = mapOf(NativeMacIdentity("a", "default") to NativeComputerConnection(NativeFeedAvailability.CONNECTED, keepAwake = true))
        } }
        compose.onNodeWithText("Mac A").assertIsSelected()
        compose.onNodeWithText("Mac B").assertIsNotSelected()
        compose.onNodeWithContentDescription("Keeping Mac awake").assertDoesNotExist()
        compose.onNodeWithContentDescription("Computer filter").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Connecting to Studio 19"))
        compose.onNodeWithText("Mac B").performClick()
        compose.runOnIdle { assertEquals(listOf("1:route-b"), actions); assertFalse(open) }
        openMenu()
        compose.onNodeWithText("Updated 19").assertIsNotSelected()
        compose.onNodeWithText("Studio 19").assertIsSelected()
        compose.onNodeWithContentDescription("Keeping Mac awake").assertIsDisplayed()
        compose.onNodeWithText("Studio 19").performClick()
        compose.runOnIdle { assertEquals(listOf("1:route-b", "21:route-b"), actions) }
    }

    @Test fun buildSubtitlesAndAddAvailabilityStayWithTheirOpening() {
        content(); openMenu()
        compose.onNodeWithText("Stable").assertIsDisplayed()
        compose.onNodeWithText("Nightly").assertIsDisplayed()
        compose.onNodeWithText("Add Computer").assertIsDisplayed()
        compose.runOnIdle {
            canAdd = false
            val identity = NativeMacIdentity("a", "default")
            presence = NativeMacPresenceState(owner.team, mapOf(identity to NativeMacPresenceInstance(identity, "com.cmuxterm.app.rc")))
        }
        compose.onNodeWithText("Stable").assertIsDisplayed()
        compose.onNodeWithText("RC").assertDoesNotExist()
        compose.onNodeWithText("Add Computer").assertIsDisplayed()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "computer-menu-builds").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder, "build-subtitles.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithText("All Computers").performClick()
        openMenu()
        compose.onNodeWithText("RC").assertIsDisplayed()
        compose.onNodeWithText("Stable").assertDoesNotExist()
        compose.onNodeWithText("Add Computer").assertDoesNotExist()
    }

    @Test fun equalRowsStillUseOpeningSpecificAllAndPairCallbacks() {
        content(); openMenu()
        compose.runOnIdle { version = 2 }
        compose.onNodeWithText("All Computers").performClick()
        openMenu()
        compose.runOnIdle { version = 3 }
        compose.onNodeWithText("Add Computer").performClick()
        openMenu()
        compose.onNodeWithText("All Computers").performClick()
        compose.runOnIdle { assertEquals(listOf("1:all", "2:pair", "3:all"), actions) }
    }

    @Test fun forgottenOrReplacedRouteCannotBeSelectedFromFrozenRow() {
        content(); openMenu()
        compose.runOnIdle { rows = listOf(b) }
        compose.onNodeWithText("Mac A").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(actions.isEmpty()); rows = listOf(a, b) }
        openMenu()
        compose.runOnIdle { rows = listOf(a.copy(code = "new-route"), b) }
        compose.onNodeWithText("Mac A").performClick()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        openMenu()
        compose.onNodeWithText("Mac A").performClick()
        compose.runOnIdle { assertEquals(listOf("1:new-route"), actions) }
    }

    @Test fun ownerChangeDismissesAndRevocationWithoutRecompositionRejectsActions() {
        content(); openMenu()
        compose.runOnIdle { owner = owner.copy(login = "new-login") }
        compose.waitUntil { !open }
        compose.onNodeWithText("Mac A").assertDoesNotExist()
        openMenu()
        compose.runOnIdle { permitted = false }
        compose.onNodeWithText("Add Computer").performClick()
        compose.runOnIdle { assertTrue(actions.isEmpty()); permitted = true }
        openMenu()
        compose.runOnIdle { permitted = false }
        compose.onNodeWithText("All Computers").performClick()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }
}
