package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeWorkspaceFilterMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun siblingBuildsRemainIndependentAndReadStateDoesNotResetMachineChoices() {
        val stable = NativeWorkspaceFilterMachine(workspaceMacFilterId("same-mac", "default")!!, "Studio", "Stable")
        val nightly = NativeWorkspaceFilterMachine(workspaceMacFilterId("same-mac", "nightly")!!, "Studio", "Nightly")
        var filter by mutableStateOf(NativeWorkspaceFilter())
        var open by mutableStateOf(false)
        compose.setContent { CmuxTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            NativeWorkspaceFilterMenu(filter, listOf(stable, nightly), open, { open = it }, { filter = it })
        } } } }
        fun menu() = compose.onNodeWithContentDescription("Filter workspaces").performClick()
        fun machine(value: NativeWorkspaceFilterMachine) = compose.onNodeWithTag("workspace.filter.machine:${value.id}")
        menu(); compose.onNodeWithText("Stable").assertIsDisplayed(); compose.onNodeWithText("Nightly").assertIsDisplayed()
        machine(stable).performClick()
        menu(); machine(stable).assertIsSelected(); machine(nightly).assertIsNotSelected(); machine(nightly).performClick()
        menu(); compose.onNodeWithText("Unread").performClick()
        compose.runOnIdle { assertEquals(NativeWorkspaceFilter(true, setOf(stable.id, nightly.id)), filter) }
        menu(); machine(stable).performClick()
        compose.runOnIdle { assertEquals(NativeWorkspaceFilter(true, setOf(nightly.id)), filter) }
        menu(); compose.onNodeWithText("All Machines").performClick()
        compose.runOnIdle { assertEquals(NativeWorkspaceFilter(true), filter) }
    }
}
