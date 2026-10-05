package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeComputerOrderSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun draggingAndAccessibilityCommitLocallyAndDoneDismisses() {
        val rows = listOf(NativeSortComputer("a","Stable",buildLabel="Stable"),
            NativeSortComputer("b","Nightly",buildLabel="Nightly"),NativeSortComputer("c","Offline Mac"))
        val saved = mutableListOf<List<String>>(); var dismissed = false
        compose.setContent { CmuxTheme { NativeComputerOrderSheet(rows, { dismissed = true }, save = { saved += it }) } }
        val list = compose.onNodeWithTag("workspace.sort.computers")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("workspace.sort.computer:a").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        val last = compose.onNodeWithTag("workspace.sort.computer:c").fetchSemanticsNode().boundsInRoot.bottom - bounds.top
        list.performTouchInput { down(first); advanceEventTime(650); moveTo(Offset(first.x,last-2f),delayMillis=180); up() }
        compose.runOnIdle { assertEquals(listOf("b","c","a"),saved.last()) }
        val action = compose.onNodeWithTag("workspace.sort.computer:a").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "Move up" }
        compose.runOnUiThread { assertTrue(action.action()) }
        compose.runOnIdle { assertEquals(listOf("b","a","c"),saved.last()) }
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }
    @Test fun allComputerSortControlsRemainAvailableWithNoMachineFilterChoices() {
        var open by mutableStateOf(false); var selected by mutableStateOf(NativeWorkspaceSortMode.AUTOMATIC); var editorOpens = 0
        compose.setContent { CmuxTheme { NativeWorkspaceFilterMenu(NativeWorkspaceFilter(),emptyList(),open,{open=it},{},
            sortMode=selected,onSort={selected=it},onOrder={editorOpens++}) } }
        compose.onNodeWithContentDescription("Filter workspaces").performClick()
        NativeWorkspaceSortMode.entries.forEach { compose.onNodeWithText(it.title).assertIsDisplayed() }
        compose.onNodeWithText("Recent Activity").performClick()
        compose.runOnIdle { assertEquals(NativeWorkspaceSortMode.ACTIVITY,selected); assertTrue(open) }
        compose.onNodeWithTag("workspace.sort.recentActivity").assertIsSelected()
        workspaceMilestoneCapture("view-options")
        compose.onNodeWithText("Edit Computer Order").assertDoesNotExist()
        compose.onNodeWithText("Custom Order").performClick()
        compose.runOnIdle { assertEquals(0, editorOpens); assertTrue(open) }
        compose.onNodeWithTag("workspace.sort.computerPriority").assertIsSelected()
        compose.onNodeWithTag("workspace.sort.recentActivity").assertIsNotSelected()
        compose.onNodeWithText("Edit Computer Order").performClick()
        compose.runOnIdle { assertEquals(1, editorOpens); assertFalse(open) }
    }
    @Test fun enlargedTextKeepsSortChoicesAndScrollableFiltersUsable() {
        org.junit.Assume.assumeTrue(android.os.Build.MODEL.contains("sdk"))
        val device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        val originalScale = device.executeShellCommand("settings get system font_scale").trim()
        try {
            device.executeShellCommand("settings put system font_scale 2.0")
            compose.waitUntil(10_000) { compose.activity.resources.configuration.fontScale >= 1.99f }
            var open by mutableStateOf(false)
            var selected by mutableStateOf(NativeWorkspaceSortMode.AUTOMATIC)
            var filter by mutableStateOf(NativeWorkspaceFilter())
            compose.setContent { CmuxTheme {
                NativeWorkspaceFilterMenu(filter, emptyList(), open, { open = it }, { filter = it },
                    sortMode = selected, onSort = { selected = it }, onOrder = {})
            } }
            compose.onNodeWithContentDescription("Filter workspaces").performClick()
            NativeWorkspaceSortMode.entries.forEach { compose.onNodeWithText(it.title).assertIsDisplayed() }
            val headerHeight = compose.onNodeWithText("Sort Computers By").fetchSemanticsNode().boundsInRoot.height
            assertTrue("Popup text did not actually enlarge", headerHeight / compose.activity.resources.displayMetrics.density > 26f)
            compose.onNodeWithText("Custom Order").performClick()
            compose.onNodeWithTag("workspace.sort.computerPriority").assertIsSelected()
            workspaceMilestoneCapture("view-options-large-text")
            compose.onNodeWithText("Unread").performScrollTo().performClick()
            compose.runOnIdle { assertTrue(filter.unread); assertTrue(open) }
            workspaceMilestoneCapture("view-options-large-text-filter")
        } finally {
            device.executeShellCommand(if (originalScale == "null") "settings delete system font_scale" else
                "settings put system font_scale $originalScale")
            compose.waitUntil(10_000) { kotlin.math.abs(compose.activity.resources.configuration.fontScale - (originalScale.toFloatOrNull() ?: 1f)) < .01f }
        }
    }
}
