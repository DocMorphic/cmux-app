package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CloudComputerVisibilityScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun hiddenCloudComputerStaysInManagementAndCanBeShownAgain() {
        val machine = CloudMachine("fixture", "fixture", "running", "Build machine", null, null)
        val snapshot = CloudWorkspaceSnapshot(machine, availability = NativeFeedAvailability.CONNECTED, authoritative = true)
        var hidden by mutableStateOf(emptySet<String>())
        var enabled by mutableStateOf(true)
        val changed = mutableListOf<Boolean>()
        compose.setContent { CmuxTheme { Surface { Column {
            NativeCloudComputerRows(listOf(snapshot), hidden, enabled) { target, visible ->
                assertEquals(machine.id, target.machine.id)
                changed += visible; hidden = if (visible) emptySet() else setOf(machine.id)
            }
        } } } }
        val toggle = compose.onNodeWithTag("computer.visibility.cloud:fixture")
        toggle.assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("Cloud · Hidden on this phone").assertIsDisplayed()
        compose.onNodeWithText("Build machine").assertIsDisplayed()
        toggle.performClick().assertIsOn()
        compose.runOnIdle { assertEquals(listOf(false, true), changed); enabled = false }
        toggle.assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(2, changed.size) }
    }
}
