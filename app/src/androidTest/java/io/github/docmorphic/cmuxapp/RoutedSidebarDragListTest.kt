package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RoutedSidebarDragListTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun accessibilityMoveUsesLatestRevisionAndRevokedActionCannotSend() {
        var rows by mutableStateOf(listOf(RoutedSidebarRow("one", "workspace", "First", drag = RoutedSidebarDragRow(down = true)),
            RoutedSidebarRow("two", "workspace", "Second", drag = RoutedSidebarDragRow(up = true))))
        var revision by mutableStateOf<String?>("original")
        val drops = mutableListOf<RoutedSidebarDrop>()
        compose.setContent { CmuxTheme { Surface {
            RoutedSidebarDragList(rows, revision, false, {}, { drops += it }) { row ->
                NativeWorkspaceRow(row.workspace(), onOpen = { fail("Moving must not open a workspace") }, onAction = { _, _ -> })
            }
        } } }
        val action = compose.onNodeWithTag("workspace.row:one").fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Move down" }
        compose.runOnIdle { revision = "updated" }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(action.action()) }
        assertEquals(listOf(RoutedSidebarDrop("updated", "one", RoutedSidebarDropPlacement.DOWN)), drops)
        compose.runOnIdle { rows = rows.map { it.copy(drag = null) } }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(action.action()) }
        assertEquals(1, drops.size)
        assertTrue(compose.onNodeWithTag("workspace.row:one").fetchSemanticsNode().config[SemanticsActions.CustomActions].none { it.label.startsWith("Move ") })
    }
}
