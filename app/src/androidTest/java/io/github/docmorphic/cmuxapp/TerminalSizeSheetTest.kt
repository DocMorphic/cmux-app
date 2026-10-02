package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import org.junit.Before
import org.junit.After
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TerminalSizeSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before @After fun deviceMustNotHaveAnAnrDialog() {
        assertFalse("Android ANR dialog prevents visual acceptance", UiDevice.getInstance(
            InstrumentationRegistry.getInstrumentation()).hasObject(By.textContains("isn't responding")))
    }
    private fun fixture(): TerminalSizeState {
        fun row(id: String, name: String) = TerminalSizeParticipant(id, "fixture", "Fixture", "unknown", name, null,
            SharedTerminalGrid(67, if (id == "phone") 47 else 35), null, true, id)
        return TerminalSizeState(1, SharedTerminalGrid(67, 35), "smallest", listOf("mac"),
            TerminalSizePolicy(TerminalSizeMode.SMALLEST), listOf(row("mac", "Mac Studio"), row("phone", "Pixel 6a"), row("browser", "Browser")))
    }
    @Test fun policyCountsPriorityAndConfirmedDisconnectControls() {
        var state by mutableStateOf(fixture())
        val changes = mutableListOf<TerminalSizingAction>()
        compose.setContent { CmuxTheme {
            TerminalSizeSheet(TerminalSizingPresentation(state, "phone"), true, {}) { action ->
                changes += action
                state = when (action) {
                    is TerminalSizingAction.Policy -> state.copy(policy = action.policy)
                    is TerminalSizingAction.Counts -> state.copy(participants = state.participants.map {
                        if (it.id == "phone") it.copy(counts = action.counts ?: true, countsOverride = action.counts) else it })
                    is TerminalSizingAction.Disconnect -> state.copy(participants = state.participants.filter { it.id !in action.ids },
                        owners = state.owners.filter { it !in action.ids })
                }
            }
        } }
        compose.onNodeWithTag("terminal-size-mode").performClick()
        compose.onNodeWithText("Fixed size", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("terminal-size-columns").performTextReplacement("1")
        compose.onNodeWithTag("terminal-size-rows").performTextReplacement("999")
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(SharedTerminalGrid(20, 120), state.policy.fixed) }
        compose.onNodeWithTag("terminal-size-mode").performClick()
        compose.onNodeWithText("Priority", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("terminal-size-counts").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(false, state.participants.single { it.id == "phone" }.countsOverride) }
        compose.onNodeWithText("Use automatic rule").performScrollTo().performClick()
        compose.runOnIdle { assertNull(state.participants.single { it.id == "phone" }.countsOverride) }
        compose.onNodeWithContentDescription("Options for This phone").performScrollTo().performClick()
        compose.onNodeWithText("Move down").performClick()
        compose.runOnIdle { assertEquals(listOf("mac", "phone", "browser"), state.policy.priority) }
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "sizing-ui-check").apply { mkdirs() }
        deviceMustNotHaveAnAnrDialog()
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(output, "priority.png")))
        compose.onNodeWithTag("terminal-size-disconnect-others").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(changes.none { it is TerminalSizingAction.Disconnect }) }
        compose.onNodeWithTag("terminal-size-disconnect-others").performClick()
        compose.onNodeWithTag("terminal-size-confirm-disconnect").performClick()
        compose.runOnIdle { assertEquals(listOf("mac", "browser"), (changes.last() as TerminalSizingAction.Disconnect).ids) }
        compose.onNodeWithTag("terminal-size-disconnect-others").assertDoesNotExist()
    }
    @Test fun longPressDragMovesParticipantToBottomWithoutRankingTheFooter() {
        var state by mutableStateOf(fixture().copy(policy = TerminalSizePolicy(TerminalSizeMode.PRIORITY,
            listOf("phone", "mac", "browser", "offline"))))
        compose.setContent { CmuxTheme {
            TerminalSizeSheet(TerminalSizingPresentation(state, "phone"), true, {}) { action ->
                state = state.copy(policy = (action as TerminalSizingAction.Policy).policy)
            }
        } }
        val source = compose.onNodeWithTag("terminal-size-participant-phone").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("terminal-size-participant-browser").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("terminal-size-participants").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("terminal-size-participants").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(source.left - list.left + 4f, source.top - list.top + 8f))
            advanceEventTime(650)
            moveTo(androidx.compose.ui.geometry.Offset(source.left - list.left + 4f, last.bottom - list.top + 4f), 600)
            up()
        }
        compose.runOnIdle { assertEquals(listOf("mac", "browser", "phone", "offline"), state.policy.priority) }
    }

    @Test fun pendingFailureAndDisconnectionKeepSnapshotAndDisableMutations() {
        val acknowledgement = CompletableDeferred<Unit>()
        var enabled by mutableStateOf(true)
        var sends = 0
        compose.setContent { CmuxTheme {
            TerminalSizeSheet(TerminalSizingPresentation(fixture(), "phone"), enabled, {}) {
                sends++; acknowledgement.await(); error("Fixture host refused the change")
            }
        } }
        compose.onNodeWithTag("terminal-size-mode").performClick()
        compose.onNodeWithText("Latest activity", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("terminal-size-mode").assertIsNotEnabled()
        compose.onNodeWithTag("terminal-size-counts").assertIsNotEnabled()
        compose.onNodeWithTag("terminal-size-disconnect-others").assertIsNotEnabled()
        compose.onNodeWithText("Size: Fit everyone ▾").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, sends); acknowledgement.complete(Unit) }
        compose.waitUntil { compose.onAllNodesWithText("Fixture host refused the change").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { enabled = false }
        compose.onNodeWithTag("terminal-size-mode").assertIsNotEnabled()
        compose.onNodeWithTag("terminal-size-counts").assertIsNotEnabled()
        compose.onNodeWithTag("terminal-size-grid").assertTextEquals("67 × 35").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, sends) }
    }
}
