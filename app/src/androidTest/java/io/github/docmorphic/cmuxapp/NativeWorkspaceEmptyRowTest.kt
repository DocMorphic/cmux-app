package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeWorkspaceEmptyRowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "empty-workspaces").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun macDocsAndRecoveryAreAbsentFromSshAndFilteredResults() {
        var guidance by mutableStateOf(NativeWorkspaceEmptyGuidance.MAC)
        var openedUri: String? = null; var retry = 0
        compose.setContent { CmuxTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { openedUri = uri } }) {
                NativeWorkspaceEmptyRow(guidance, onRetry = { retry++ })
            }
        } } } }
        compose.onNodeWithText("No workspaces yet").assertIsDisplayed(); capture("mac")
        compose.onNodeWithTag("workspaces.empty.docs").performClick()
        compose.runOnIdle { assertEquals("https://cmux.com/docs/ios#prerequisites", openedUri) }
        compose.onNodeWithTag("workspaces.empty.retry").performClick()
        compose.runOnIdle { assertEquals(1, retry); guidance = NativeWorkspaceEmptyGuidance.SSH_HOST }
        compose.onNodeWithTag("workspaces.empty.docs").assertDoesNotExist()
        compose.onNodeWithTag("workspaces.empty.retry").assertDoesNotExist()
        compose.onNodeWithText("No workspaces yet").assertIsDisplayed(); capture("ssh")
        compose.runOnIdle { guidance = NativeWorkspaceEmptyGuidance.SEARCH }
        compose.onNodeWithText("No workspaces match your search").assertIsDisplayed()
        compose.onNodeWithTag("workspaces.empty.retry").assertDoesNotExist()
        compose.runOnIdle { guidance = NativeWorkspaceEmptyGuidance.UNREAD }
        compose.onNodeWithText("No unread workspaces").assertIsDisplayed()
    }
    @Test fun heldErrorWaitsAndBusyOrDisposedRetryCannotDispatch() {
        var held by mutableStateOf(false)
        var shown by mutableStateOf(true)
        var recovery by mutableStateOf(NativeWorkspaceEmptyRecoveryState())
        var retries = 0
        compose.setContent { CmuxTheme { Surface { Box(Modifier.width(320.dp)) {
            CompositionLocalProvider(LocalWorkspaceGeometryHeld provides held) {
                if (shown) NativeWorkspaceEmptyRow(NativeWorkspaceEmptyGuidance.MAC, recovery, onRetry = { retries++ })
            }
        } } } }
        val cached = compose.onNodeWithTag("workspaces.empty.retry").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val height = compose.onNodeWithTag("workspaces.empty").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { held = true }
        compose.runOnIdle { recovery = NativeWorkspaceEmptyRecoveryState(busy = true, message = "The remote Mac is taking longer to respond. ".repeat(3)) }
        compose.onNodeWithTag("workspaces.empty.message").assertDoesNotExist()
        assertEquals(height, compose.onNodeWithTag("workspaces.empty").fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.onNodeWithTag("workspaces.empty.retry").assertIsNotEnabled()
        compose.runOnUiThread { cached() }
        compose.runOnIdle { assertEquals(0, retries); held = false }
        compose.onNodeWithTag("workspaces.empty.message").assertIsDisplayed()
        compose.runOnIdle { shown = false }
        compose.onNodeWithTag("workspaces.empty").assertDoesNotExist()
        compose.runOnUiThread { cached() }
        compose.runOnIdle { assertEquals(0, retries) }
    }

    @Test fun removingAndRemountingRowPreservesRetryUntilTimeout() {
        var show by mutableStateOf(true)
        lateinit var recovery: NativeWorkspaceEmptyRecovery
        var calls = 0
        compose.setContent { CmuxTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            val scope = rememberCoroutineScope()
            recovery = remember { NativeWorkspaceEmptyRecovery(scope, 1000) }
            DisposableEffect(recovery) { onDispose { recovery.close() } }
            val state by recovery.state.collectAsState()
            if (show) NativeWorkspaceEmptyRow(NativeWorkspaceEmptyGuidance.MAC, state, onRetry = {
                recovery.start { calls++; awaitCancellation() }
            })
        } } } }
        compose.onNodeWithTag("workspaces.empty.retry").performClick()
        compose.onNodeWithTag("workspaces.empty.retry").assertIsNotEnabled()
        compose.runOnIdle { show = false }
        compose.onNodeWithTag("workspaces.empty").assertDoesNotExist()
        compose.waitUntil(4000) { !recovery.state.value.busy }
        compose.runOnIdle { show = true }
        compose.onNodeWithText(NativeWorkspaceEmptyRecovery.TIMEOUT).assertIsDisplayed()
        compose.onNodeWithTag("workspaces.empty.retry").assertIsEnabled(); capture("timeout")
        compose.runOnIdle { assertEquals(1, calls) }
    }
}
