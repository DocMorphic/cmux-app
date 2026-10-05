package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual lazy-list geometry: a moved visible row must not drag the reader to the top. */
class WorkspaceViewportRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun y(title: String) = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top
    private fun scroll(index: Int) = compose.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
    private fun assertY(title: String, expected: Float) {
        compose.waitForIdle()
        assertEquals("Screen position for $title", expected, y(title), 1f)
    }

    @Test fun mainFeedAnchorsStableNeighborThroughMovesInsertionsAndStatusChanges() {
        val workspaces = parseWorkspaces(JSONObject().put("workspaces", JSONArray((0 until 80).map {
            JSONObject().put("id", "w$it").put("title", "Workspace $it")
        })))
        val mac = NativeCredentialStore.PairedMac("fixture", "fixture", "Fixture Mac")
        var rows by mutableStateOf(workspaces)
        var status by mutableStateOf(false)
        compose.setContent { CmuxTheme {
            val source = NativeFeedSource(mac, workspaces = rows)
            NativeWorkspaceDragList(workspaceHierarchy(source), false, Modifier.width(320.dp).height(360.dp),
                onMove = { _, _, _ -> fail("Live updates must not move a host workspace"); false },
                prefixKeys = if (status) listOf("status") else emptyList(),
                before = { if (status) item("status") { Text("Connecting", Modifier.height(48.dp)) } }, empty = {}) { entry ->
                Text((entry as WorkspaceListEntry.Workspace).workspace.title, Modifier.fillMaxWidth().height(60.dp))
            }
        } }
        scroll(20)
        val neighborY = y("Workspace 21")
        compose.runOnIdle { rows = listOf(workspaces[20]) + workspaces.filterNot { it.id == "w20" } }
        assertY("Workspace 21", neighborY)
        compose.onNodeWithText("Workspace 20").assertIsNotDisplayed()
        compose.runOnIdle { status = true }
        assertY("Workspace 21", neighborY)
        compose.runOnIdle { status = false; rows = rows.filterNot { it.id == "w3" } }
        assertY("Workspace 21", neighborY)
        scroll(0)
        compose.runOnIdle { status = true }
        compose.onNodeWithText("Connecting").assertIsDisplayed()
    }

    @Test fun browserFeedAnchorsStableNeighborAndKeepsTopInsertionVisible() {
        val original = (0 until 80).map { RoutedSidebarRow("w$it", "workspace", "Workspace $it") }
        var rows by mutableStateOf(original)
        compose.setContent { CmuxTheme { Box(Modifier.width(320.dp).height(360.dp)) {
            RoutedSidebarDragList(rows, "fixture", false, {}, { fail("Live updates must not send a drop") }) {
                Text(it.title, Modifier.fillMaxWidth().height(60.dp))
            }
        } } }
        scroll(20)
        val neighborY = y("Workspace 21")
        compose.runOnIdle { rows = listOf(original[20]) + original.filterNot { it.key == "w20" } }
        assertY("Workspace 21", neighborY)
        compose.runOnIdle { rows = rows.filterNot { it.key == "w1" } }
        assertY("Workspace 21", neighborY)
        scroll(0)
        compose.runOnIdle { rows = listOf(RoutedSidebarRow("new", "workspace", "New workspace")) + rows }
        compose.onNodeWithText("New workspace").assertIsDisplayed()
    }
}
