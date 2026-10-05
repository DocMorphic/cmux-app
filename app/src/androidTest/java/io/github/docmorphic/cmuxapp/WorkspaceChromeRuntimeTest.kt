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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkspaceChromeRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun nativeChromeDefersGeometryAndRetiresRemovedRetry() = exercise(native = true)
    @Test fun browserChromeDefersGeometryAndRetiresRemovedRetry() = exercise(native = false)

    private fun exercise(native: Boolean) {
        val original = WorkspaceListChrome("status", "Short notice", "Retry", actionTag = "retry")
        var leading by mutableStateOf(listOf(original))
        var trailing by mutableStateOf(emptyList<WorkspaceListChrome>())
        var invoked = 0
        val workspace = NativeWorkspace("a", "Workspace", emptyList(), null, true, null, null,
            false, emptyList(), null, "Preview", null)
        compose.setContent { CmuxTheme { Box(Modifier.width(320.dp).height(500.dp)) {
            if (native) {
                val source = NativeFeedSource(NativeCredentialStore.PairedMac("fixture", "fixture", "Fixture"), workspaces = listOf(workspace))
                NativeWorkspaceDragList(workspaceHierarchy(source), false, onMove = { _, _, _ -> false },
                    leading = leading, trailing = trailing, onChromeAction = { invoked++ }, empty = {}) {
                    NativeWorkspaceRow(workspace, canReadState = true, onOpen = {}, onAction = { _, _ -> })
                }
            } else RoutedSidebarDragList(listOf(RoutedSidebarRow("a", "workspace", "Workspace")), "fixture", false, {}, {},
                leading = leading, trailing = trailing, onChromeAction = { invoked++ }) {
                NativeWorkspaceRow(workspace, canReadState = true, onOpen = {}, onAction = { _, _ -> })
            }
        } } }
        val cachedRetry = compose.onNodeWithTag("retry").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val before = compose.onNodeWithTag("workspace.row:a").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("workspace.swipe:a").performTouchInput {
            swipe(start = center.copy(x = 5f), end = center.copy(x = width * .45f), durationMillis = 400)
        }
        compose.runOnIdle { leading = listOf(original.copy(text = "A long connection error that wraps across the notice. ".repeat(8))) }
        compose.onNodeWithText("Short notice").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("workspace.row:a").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnIdle {
            leading = listOf(WorkspaceListChrome("new", "Replacement notice"))
            trailing = listOf(WorkspaceListChrome("more", "", "Load more", kind = WorkspaceChromeKind.MORE))
        }
        compose.onNodeWithText("Replacement notice").assertDoesNotExist()
        compose.onNodeWithText("Load more").assertDoesNotExist()
        compose.runOnUiThread { cachedRetry() }
        compose.runOnIdle { assertEquals(0, invoked) }
        assertEquals(before, compose.onNodeWithTag("workspace.row:a").fetchSemanticsNode().boundsInRoot.top, 1f)
        Espresso.pressBack()
        compose.onNodeWithText("Replacement notice").assertIsDisplayed()
        compose.onNodeWithText("Load more").assertIsDisplayed().performClick()
        compose.runOnUiThread { cachedRetry() }
        compose.runOnIdle { assertEquals(1, invoked) }
    }
}
