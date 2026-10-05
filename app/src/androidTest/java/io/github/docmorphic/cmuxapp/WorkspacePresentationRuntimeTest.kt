package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkspacePresentationRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun openSwipeHoldsRowsRefreshesContentAndRejectsRemovedRowsCachedClick() {
        val initial = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"a","title":"First"},
            {"id":"b","title":"Second"},{"id":"c","title":"Third"}]}"""))
        var workspaces by mutableStateOf(initial)
        var opened = 0
        var actions = 0
        compose.setContent { CmuxTheme {
            val source = NativeFeedSource(NativeCredentialStore.PairedMac("fixture", "fixture", "Fixture"), workspaces = workspaces)
            NativeWorkspaceDragList(workspaceHierarchy(source), false, Modifier.width(320.dp).height(500.dp),
                onMove = { _, _, _ -> false }, empty = {}) { entry ->
                NativeWorkspaceRow((entry as WorkspaceListEntry.Workspace).workspace,
                    canReadState = true, onOpen = { opened++ }, onAction = { _, _ -> actions++ })
            }
        } }
        val cachedClick = compose.onNodeWithTag("workspace.row:a").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val beforeY = compose.onNodeWithTag("workspace.row:b").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("workspace.swipe:b").performTouchInput {
            swipe(start = center.copy(x = 5f), end = center.copy(x = width * .45f), durationMillis = 400)
        }
        compose.onNodeWithTag("workspace.swipe.action:b").assertIsDisplayed()
        compose.runOnIdle {
            workspaces = listOf(initial[2].copy(id = "new", title = "Arrived"), initial[1].copy(title = "Updated"), initial[2])
        }
        compose.onNodeWithTag("workspace.row:new").assertDoesNotExist()
        compose.onNodeWithTag("workspace.title:b", useUnmergedTree = true).assertTextEquals("Updated")
        assertEquals(beforeY, compose.onNodeWithTag("workspace.row:b").fetchSemanticsNode().boundsInRoot.top, 1f)
        workspaceMilestoneCapture("held-swipe-latest-content")
        compose.runOnIdle { cachedClick(); assertEquals(0, opened); assertEquals(0, actions) }
        Espresso.pressBack()
        compose.onNodeWithTag("workspace.row:new").assertIsDisplayed()
        compose.onNodeWithTag("workspace.row:a").assertDoesNotExist()
        compose.runOnIdle { cachedClick(); assertEquals(0, opened) }
        compose.onNodeWithTag("workspace.row:b").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
    @Test fun recycledSwipeOwnerReleasesOrderHoldWithoutClearingAnotherOwner() {
        val coordinator = WorkspaceSwipeCoordinator()
        var shown by mutableStateOf(true)
        compose.setContent { CmuxTheme {
            CompositionLocalProvider(LocalWorkspaceSwipeCoordinator provides coordinator) {
                if (shown) NativeWorkspaceSwipeActions("owner", false, true, false, {}, {}) {
                    androidx.compose.material3.Text("Owner")
                }
            }
        } }
        compose.runOnIdle { coordinator.activeKey = "owner" }
        compose.runOnIdle { shown = false }
        compose.runOnIdle { assertNull(coordinator.activeKey); shown = true }
        compose.runOnIdle { coordinator.activeKey = "another-owner" }
        compose.runOnIdle { shown = false }
        compose.runOnIdle { assertEquals("another-owner", coordinator.activeKey) }
    }

}
