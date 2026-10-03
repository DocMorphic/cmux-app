package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativePrivatePathsSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val mac = IrohV2Computer("record", "a".repeat(64), "fixture-mac", "default", "Test Mac", emptyList())

    @Test fun editValidateEnableResetAndRemoveThroughSettings() {
        var disk: String? = null
        val store = NativePrivatePathStore({ disk }, { disk = it })
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                NativePrivatePathsSection(listOf(mac), { store.load() }, { action -> action(store); store.load() })
            }
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Add addresses for Test Mac").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add addresses for Test Mac").performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("IP Addresses and Ports").performTextInput("localhost:58470")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("IP Addresses and Ports").performTextReplacement("192.168.1.5:58470\n[fd00::5]:58470")
        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOn()
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        capture("private-address-editor")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5000) { store.load().size == 1 }
        compose.onNodeWithText("Enabled · 2 addresses").assertIsDisplayed()
        assertEquals(listOf("192.168.1.5:58470", "[fd00::5]:58470"), store.addresses(mac))
        compose.onNodeWithText("Edit Test Mac").performClick()
        compose.onNodeWithText("IP Addresses and Ports").assertTextContains("192.168.1.5:58470\n[fd00::5]:58470")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Reset Private Addresses").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(store.load().single().enabled)
        compose.onNodeWithText("Reset Private Addresses").performClick()
        compose.onNodeWithText("Reset", substring = false).performClick()
        compose.onNodeWithText("Disabled").assertIsDisplayed()
        assertEquals(2, store.load().single().addresses.size)
        compose.onNodeWithText("Remove Test Mac").performClick()
        compose.onNodeWithText("Remove", substring = false).performClick()
        compose.waitUntil(5000) { store.load().isEmpty() }
    }

    @Test fun failedSaveRetainsEditorAndInputForRetry() {
        var disk: String? = null
        var fail = true
        val store = NativePrivatePathStore({ disk }, { if (fail) error("fixture-disk-failure") else disk = it })
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativePrivatePathsSection(listOf(mac), { store.load() }, { action -> action(store); store.load() })
        } } }
        compose.onNodeWithText("Add addresses for Test Mac").performClick()
        compose.onNodeWithText("IP Addresses and Ports").performTextInput("10.0.0.1:58470")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("IP Addresses and Ports").assertTextContains("10.0.0.1:58470")
        compose.onNodeWithText("Save").assertIsEnabled()
        assertNull(disk)
        compose.runOnIdle { fail = false }
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5000) { store.load().size == 1 }
    }

    private fun capture(name: String) {
        val device = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        device.takeScreenshot().let { bitmap ->
            java.io.File(root, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
