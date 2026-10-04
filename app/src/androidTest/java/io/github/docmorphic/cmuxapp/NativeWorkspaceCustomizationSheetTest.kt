package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import kotlinx.coroutines.CompletableDeferred
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class NativeWorkspaceCustomizationSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private fun awaitDialog() = compose.waitUntil(5000) {
        compose.onAllNodesWithTag("workspace.customize.description").fetchSemanticsNodes().isNotEmpty()
    }
    private fun row(truncated: Boolean = false) = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"w","title":"Original","description":"Baseline","description_truncated":$truncated}]}""")).single()
    @Test fun editsSurviveRestorationAndFailedSaveRebasesWithoutDiscardingPendingChanges() {
        val restore = StateRestorationTester(compose)
        var dismissed = false
        val attempts = mutableListOf<Pair<WorkspaceCustomizationDraft, WorkspaceCustomizationDraft>>()
        restore.setContent { CmuxTheme {
            NativeWorkspaceCustomizationSheet(row(), { dismissed = true }) { baseline, draft ->
                attempts += baseline to draft
                if (attempts.size == 1) {
                    val fresh = baseline.copy(name = "Mac name")
                    WorkspaceCustomizationResult(false, fresh, draft.retainingEdits(baseline, fresh), "Fixture retry")
                } else WorkspaceCustomizationResult(true)
            }
        } }
        awaitDialog()
        compose.onNodeWithTag("workspace.customize.description").performScrollTo().performTextReplacement("Phone description")
        restore.emulateSavedInstanceStateRestore()
        awaitDialog()
        compose.onNodeWithTag("workspace.customize.description").assertTextContains("Phone description")
        compose.onNodeWithContentDescription("Use Workspace Color").performScrollTo().performClick()
        compose.onNodeWithTag("workspace.customize.color").performScrollTo().performTextReplacement("invalid")
        compose.onNodeWithTag("workspace.customize.save").assertIsNotEnabled()
        compose.onNodeWithTag("workspace.customize.color").performScrollTo().performTextReplacement("#AbCDeF")
        compose.onNodeWithTag("workspace.customize.save").performClick()
        compose.onNodeWithText("Fixture retry").assertExists()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithTag("workspace.customize.name").assertTextContains("Mac name")
        compose.onNodeWithTag("workspace.customize.description").assertTextContains("Phone description")
        compose.onNodeWithTag("workspace.customize.save").performClick()
        compose.runOnIdle {
            assertTrue(dismissed); assertEquals("Mac name", attempts.last().first.name)
            assertEquals("#ABCDEF", attempts.last().second.color)
        }
    }
    @Test fun rapidSaveClicksCannotStartDuplicateWritesAndBusyDialogCannotBeDismissed() {
        val result = CompletableDeferred<WorkspaceCustomizationResult>()
        var saves = 0
        var dismissed = false
        compose.setContent { CmuxTheme { NativeWorkspaceCustomizationSheet(row(), { dismissed = true }) { _, _ ->
            saves++; result.await()
        } } }
        awaitDialog()
        compose.onNodeWithTag("workspace.customize.name").performTextReplacement("New name")
        // Two click deliveries before Compose can publish disabled semantics.
        val click = compose.onNodeWithTag("workspace.customize.save").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread { click(); click() }
        compose.waitUntil(5000) { saves > 0 }
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.onNodeWithTag("workspace.customize.name").assertIsNotEnabled()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.runOnIdle { assertEquals(1, saves); assertFalse(dismissed) }
        result.complete(WorkspaceCustomizationResult(false, message = "Fixture retry"))
        compose.waitUntil(5000) { compose.onAllNodesWithText("Fixture retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun truncatedDescriptionsAreReadOnlyButOtherFieldsRemainEditable() {
        compose.setContent { CmuxTheme { NativeWorkspaceCustomizationSheet(row(true), {}) { _, _ -> WorkspaceCustomizationResult(true) } } }
        awaitDialog()
        compose.onNodeWithTag("workspace.customize.description").assertIsNotEnabled()
        compose.onNodeWithTag("workspace.customize.truncated").assertExists()
        compose.onNodeWithTag("workspace.customize.name").performTextReplacement("New name")
        compose.onNodeWithTag("workspace.customize.save").assertIsEnabled()
    }
}
