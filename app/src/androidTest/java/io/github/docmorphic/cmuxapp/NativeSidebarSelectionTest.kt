package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeSidebarSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sharedRowsExposeSelectionAndRenderDistinctWorkspaceAndAnchorHighlightsOnlyInSidebar() {
        val workspace = NativeWorkspace("selected", "Selected workspace", emptyList(), null, false, null, null,
            false, emptyList(), null, "Preview", "#00FF00")
        val group = NativeGroup("anchor", "Selected group", false, false, anchorWorkspaceId = workspace.id)
        var selected by mutableStateOf(false)
        var split by mutableStateOf(true)
        compose.setContent { CmuxTheme { Surface { CompositionLocalProvider(
            LocalWorkspaceShellChrome provides WorkspaceShellChrome(split, split)) {
            Column(Modifier.width(360.dp)) {
                NativeWorkspaceRow(workspace, isSelected = selected, onOpen = {}, onAction = { _, _ -> })
                NativeGroupHeaderRow(group, true, NativeWorkspaceUnread.Read, onOpen = {}, canEdit = false,
                    onToggle = {}, onAction = { _, _ -> }, isSelected = selected)
            }
        } } } }
        val row = compose.onNodeWithTag("workspace.row:selected")
        val header = compose.onNodeWithContentDescription("Open Selected group")
        row.assertIsNotSelected(); header.assertIsNotSelected()
        val ordinary = compose.onNodeWithTag("group.row:anchor").captureToImage().toPixelMap()
        compose.runOnIdle { selected = true }
        row.assertIsSelected(); header.assertIsSelected()
        val highlighted = compose.onNodeWithTag("group.row:anchor").captureToImage().toPixelMap()
        assertEquals(ordinary.width, highlighted.width); assertEquals(ordinary.height, highlighted.height)
        var grayPixels = 0
        for (y in 0 until ordinary.height) for (x in 0 until ordinary.width) {
            if (highlighted[x,y].red > ordinary[x,y].red + .02f &&
                highlighted[x,y].green > ordinary[x,y].green + .02f &&
                highlighted[x,y].blue > ordinary[x,y].blue + .02f) grayPixels++
        }
        assertTrue("Anchor must visibly highlight, not only expose selection semantics", grayPixels > 100)
        val workspacePixels = row.captureToImage().toPixelMap()
        var bluePixels = 0
        for (y in 0 until workspacePixels.height) for (x in 0 until workspacePixels.width) {
            val pixel = workspacePixels[x,y]
            if (pixel.blue > pixel.red + .035f && pixel.blue > pixel.green + .01f) bluePixels++
        }
        assertTrue("Workspace must visibly highlight blue", bluePixels > 100)
        compose.runOnIdle { split = false }
        row.assertIsNotSelected(); header.assertIsNotSelected()
        val compact = compose.onNodeWithTag("group.row:anchor").captureToImage().toPixelMap()
        for (y in 0 until ordinary.height) for (x in 0 until ordinary.width) assertEquals(ordinary[x,y], compact[x,y])
        compose.runOnIdle { split = true; selected = false }
        row.assertIsNotSelected(); header.assertIsNotSelected()
    }
}
