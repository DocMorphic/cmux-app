package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeMacConnectionSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val target = NativeComputerTarget("direct-fixture", "default", "Fixture Mac")
    private var disk: String? = null
    private val store = NativeMacConnectionStore({ disk }, { disk = it })
    private fun content(save: suspend ((NativeMacConnectionPreference) -> NativeMacConnectionPreference) -> Unit = {
        store.update(target, { true }, it)
    }) {
        compose.setContent {
            val state by store.state.collectAsState()
            CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    NativeMacConnectionSection(target, state, save, retry = store::reload)
                }
            } }
        }
    }
    private fun method(name: String) {
        compose.onNodeWithContentDescription("Choose connection method").performClick()
        compose.onNodeWithText(name).performClick()
    }

    @Test fun directRequiresAddressAndSupportsLabelToggleEditAndRemoval() {
        content(); method("Direct")
        compose.onNodeWithText("No address is enabled. This computer stays disconnected until you enable or add one.").assertIsDisplayed()
        compose.onNodeWithText("Add Address").performClick()
        compose.onNodeWithText("IP Address and UDP Port").performTextInput("example.com:443")
        compose.onNodeWithText("Save address").assertIsNotEnabled()
        compose.onNodeWithText("IP Address and UDP Port").performTextReplacement("192.168.1.20:58470")
        compose.onNodeWithText("Label (optional)").performTextInput("Desk")
        compose.onNodeWithText("Save address").performClick()
        compose.onNodeWithText("Desk").assertIsDisplayed()
        compose.onNodeWithContentDescription("Enable direct address 192.168.1.20:58470").assertIsOn().performClick()
        compose.onNodeWithContentDescription("Enable direct address 192.168.1.20:58470").assertIsOff()
        compose.onNodeWithContentDescription("Actions for 192.168.1.20:58470").performClick()
        compose.onNodeWithText("Edit address").performClick()
        compose.onNodeWithText("IP Address and UDP Port").performTextReplacement("192.168.1.21:58470")
        compose.onNodeWithText("Label (optional)").performTextReplacement("Office")
        compose.onNodeWithText("Save address").performClick()
        compose.onNodeWithContentDescription("Enable direct address 192.168.1.21:58470").assertIsOff().performClick()
        compose.onNodeWithText("Office").assertIsDisplayed()
        capture("direct-address-settings")
        val restored = NativeMacConnectionStore({ disk }, {})
        assertEquals(NativeMacConnectionPreference(NativeMacConnectionMethod.DIRECT,
            listOf(NativeDirectAddress("192.168.1.21:58470", "Office", true))), restored.state.value.get(target))
        compose.onNodeWithContentDescription("Actions for 192.168.1.21:58470").performClick()
        compose.onNodeWithText("Remove address").performClick()
        compose.onNodeWithText("Office").assertDoesNotExist()
        assertTrue(store.state.value.get(target).addresses.isEmpty())
    }

    @Test fun failedMethodSaveDoesNotPretendDirectIsActiveAndCanRetry() {
        val gate = CompletableDeferred<Unit>(); var attempts = 0
        content { change ->
            if (++attempts == 1) { gate.await(); error("fixture disk full") }
            store.update(target, { true }, change)
        }
        method("Direct")
        compose.onNodeWithContentDescription("Choose connection method").assertIsNotEnabled()
        compose.onNodeWithText("Iroh ▾").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, attempts); gate.complete(Unit) }
        compose.onNodeWithText("Could not save connection settings. Check the address and your account, then retry.").assertIsDisplayed()
        method("Direct")
        compose.onNodeWithText("Direct ▾").assertIsDisplayed()
        assertEquals(2, attempts)
    }

    @Test fun duplicateAddressFailureRetainsEditorAndChangingItAllowsRetry() {
        store.update(target, { true }) { NativeMacConnectionPreference(NativeMacConnectionMethod.DIRECT,
            listOf(NativeDirectAddress("10.0.0.2:58470"))) }
        content()
        compose.onNodeWithText("Add Address").performClick()
        compose.onNodeWithText("IP Address and UDP Port").performTextInput("10.0.0.2:58470")
        compose.onNodeWithText("Save address").performClick()
        compose.onNodeWithText("Add Direct Address").assertIsDisplayed()
        compose.onNodeWithText("Could not save connection settings. Check the address and your account, then retry.").assertIsDisplayed()
        assertEquals(1, store.state.value.get(target).addresses.size)
        compose.onNodeWithText("IP Address and UDP Port").performTextReplacement("10.0.0.3:58470")
        compose.onNodeWithText("Save address").performClick()
        compose.onNodeWithText("Add Direct Address").assertDoesNotExist()
        assertEquals(2, store.state.value.get(target).addresses.size)
    }

    @Test fun unreadablePreferencesBlockSelectorUntilReloadSucceeds() {
        store.update(target, { true }) { it.copy(method = NativeMacConnectionMethod.DIRECT) }
        val saved = disk
        disk = "not json"; store.reload()
        content()
        compose.onNodeWithContentDescription("Choose connection method").assertIsNotEnabled()
        compose.onNodeWithText("Could not read connection settings. Connections stay blocked until these settings can be loaded.").assertIsDisplayed()
        compose.runOnIdle { disk = saved }
        compose.onNodeWithText("Retry connection settings").performClick()
        compose.onNodeWithContentDescription("Choose connection method").assertIsEnabled()
        compose.onNodeWithText("Direct ▾").assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(400)
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
