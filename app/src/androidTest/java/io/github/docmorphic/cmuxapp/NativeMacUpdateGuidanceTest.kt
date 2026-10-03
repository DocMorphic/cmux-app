package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeMacUpdateGuidanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val stable = NativeMacIdentity("mac", "default")
    private val nightly = NativeMacIdentity("mac", "nightly")
    private var warnings by mutableStateOf<Map<NativeMacIdentity, MacCompatibilityViolation>>(emptyMap())
    private var opened: String? = null
    private fun content() = compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalMacCompatibilityWarnings provides warnings,
            LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened = uri } }) {
            Column(Modifier.statusBarsPadding().padding(22.dp)) {
                NativeComputerRowLabel("Studio", "Stable", NativeComputerConnection(), NativeComputerPresence(),
                    reconnect = true, identity = stable)
                NativeComputerRowLabel("Studio", "Nightly", NativeComputerConnection(), NativeComputerPresence(),
                    reconnect = false, identity = nightly)
            }
        }
    } } }

    @Test fun onlyAffectedBuildShowsGuidanceAndReleaseLinkUsesFixedOfficialDestination() {
        warnings = mapOf(stable to MacCompatibilityViolation(false, "0.64.24", "0.64.25"))
        content()
        compose.onAllNodesWithText("Mac update required").assertCountEquals(1)
        compose.onNodeWithText("Mac update required").performClick()
        compose.onNodeWithText("Update cmux on your Mac").assertIsDisplayed()
        compose.onNode(hasText("to 0.64.25 or later", substring = true)).assertIsDisplayed()
        capture("stable-update-guidance")
        compose.onNodeWithText("View Mac release").performClick()
        compose.runOnIdle { assertEquals("https://github.com/manaflow-ai/cmux/releases/latest", opened) }
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { warnings = emptyMap() }
        compose.onNodeWithText("Mac update required").assertDoesNotExist()
    }

    @Test fun stricterRefreshOpensNightlyAdviceThenRetiredScopeRemovesOpenDialog() {
        content()
        compose.onNodeWithText("Mac update required").assertDoesNotExist()
        compose.runOnIdle { warnings = mapOf(nightly to MacCompatibilityViolation(true, null, "0.64.25-nightly.3522337919701")) }
        compose.onNodeWithText("Mac update required").performClick()
        compose.onNode(hasText("did not report", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("View Mac release").performClick()
        compose.runOnIdle { assertEquals("https://github.com/manaflow-ai/cmux/releases/tag/nightly", opened); warnings = emptyMap() }
        compose.onNodeWithText("Update cmux on your Mac").assertDoesNotExist()
        compose.onNodeWithText("Mac update required").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "mac-compatibility").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
