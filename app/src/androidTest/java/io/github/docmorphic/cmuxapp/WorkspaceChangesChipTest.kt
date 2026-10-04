package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkspaceChangesChipTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun chipHasIndependentAccessibleActionWithoutOpeningTerminalRow() {
        var opened = 0; var changes = 0
        val workspace = NativeWorkspace("w", "Workspace", emptyList(), null, false, null, null, false, emptyList(), null, "Latest activity", null)
        compose.setContent { CmuxTheme { Surface {
            NativeWorkspaceRow(workspace, changesChip = WorkspaceChangesChip(3, 42, 7),
                onOpen = { opened++ }, onAction = { action, _ -> assertEquals("changes", action); changes++ })
        } } }
        compose.onNodeWithContentDescription("Changes: 3 files, +42, −7").assertHasClickAction().performClick()
        assertEquals(1, changes); assertEquals(0, opened)
        val bounds = compose.onNodeWithTag("workspace.changes:w", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue((bounds.right - bounds.left).value >= 43.9f)
        assertTrue((bounds.bottom - bounds.top).value >= 43.9f)
        compose.onNodeWithTag("workspace.title:w", useUnmergedTree = true).performClick()
        assertEquals(1, opened); assertEquals(1, changes)
    }
    @Test fun binaryOnlyChipNamesFilesForAccessibility() {
        compose.setContent { CmuxTheme { Surface { NativeWorkspaceChangesChip(WorkspaceChangesChip(1, 0, 0), "binary") {} } } }
        compose.onNodeWithContentDescription("Changes: 1 file, +0, −0").assertExists()
    }
}
