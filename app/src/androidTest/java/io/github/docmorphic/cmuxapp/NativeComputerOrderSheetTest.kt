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
        var open by mutableStateOf(false); var selected by mutableStateOf(NativeWorkspaceSortMode.AUTOMATIC)
        compose.setContent { CmuxTheme { NativeWorkspaceFilterMenu(NativeWorkspaceFilter(),emptyList(),open,{open=it},{},
            sortMode=selected,onSort={selected=it}) } }
        compose.onNodeWithContentDescription("Filter workspaces").performClick()
        NativeWorkspaceSortMode.entries.forEach { compose.onNodeWithText(it.title).assertIsDisplayed() }
        compose.onNodeWithText("Recent Activity").performClick()
        compose.runOnIdle { assertEquals(NativeWorkspaceSortMode.ACTIVITY,selected) }
    }
}
