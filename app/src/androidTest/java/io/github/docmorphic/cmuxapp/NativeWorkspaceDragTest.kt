package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.test.StandardTestDispatcher

@OptIn(ExperimentalTestApi::class)
class NativeWorkspaceDragTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private fun source(count: Int) = NativeFeedSource(NativeCredentialStore.PairedMac("test", "test", "Test Mac"),
        workspaces = parseWorkspaces(JSONObject().put("workspaces", JSONArray((0 until count).map {
            JSONObject().put("id", "w$it").put("title", "Workspace $it")
        }))))

    @Test fun longHeldDragAutoScrollsPastRecycledSourceRowAndKeepsCapturedTarget() {
        val source = source(30)
        var moved: Pair<String, NativeWorkspaceMove>? = null
        compose.setContent { CmuxTheme {
            NativeWorkspaceDragList(workspaceHierarchy(source), true,
                Modifier.width(320.dp).height(320.dp).testTag("list"),
                onMove = { _, id, intent -> moved = id to intent; true }, rowHandlesAccessibility = false, empty = {}) { entry ->
                Text((entry as WorkspaceListEntry.Workspace).workspace.title, Modifier.fillMaxWidth().height(56.dp))
            }
        } }
        val list = compose.onNodeWithTag("list")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("Workspace 0").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        // Deliver one complete held gesture so Espresso never waits for idle with
        // the auto-scroll frame loop active and the pointer still down.
        list.performTouchInput {
            down(first)
            advanceEventTime(650)
            moveTo(Offset(first.x, bounds.height - 3f), delayMillis = 100)
            repeat(120) { moveBy(Offset.Zero, delayMillis = 16) }
            up()
        }
        compose.runOnIdle {
            assertNotNull(moved)
            assertEquals("w0", moved!!.first)
            val before = moved!!.second.beforeWorkspaceId?.removePrefix("w")?.toInt() ?: 30
            assertTrue("Drag did not cross virtualized rows: $moved", before > 10)
        }
    }

    @Test fun accessibilityMoveAndDisabledStateUseSamePolicy() {
        val source = source(3)
        var enabled by mutableStateOf(true)
        var moved: Pair<String, NativeWorkspaceMove>? = null
        var moveCount = 0
        compose.setContent { CmuxTheme {
            NativeWorkspaceDragList(workspaceHierarchy(source), enabled, Modifier.fillMaxSize(),
                onMove = { _, id, intent -> moveCount++; moved = id to intent; true }, rowHandlesAccessibility = false, empty = {}) { entry ->
                Text((entry as WorkspaceListEntry.Workspace).workspace.title, Modifier.height(56.dp).fillMaxWidth())
            }
        } }
        fun action(label: String) = SemanticsMatcher("$label action for first row") { node ->
            node.config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.any { it.label == label }
        } and hasAnyDescendant(hasText("Workspace 0"))
        val moveDown = compose.onNode(action("Move down")).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "Move down" }
        compose.runOnUiThread { assertTrue(moveDown.action()) }
        assertEquals("w0" to NativeWorkspaceMove(null, "w2"), moved)
        compose.runOnIdle { enabled = false }
        compose.waitUntil(5_000) { compose.onAllNodes(action("Move down")).fetchSemanticsNodes().isEmpty() }
        compose.onAllNodes(action("Move down")).assertCountEquals(0)
        compose.runOnUiThread { assertFalse(moveDown.action()) }
        compose.onNodeWithText("Workspace 0").performTouchInput { down(center); advanceEventTime(650); moveBy(Offset(0f, 150f)); up() }
        assertEquals("w0" to NativeWorkspaceMove(null, "w2"), moved)
        assertEquals(1, moveCount)
    }
}
