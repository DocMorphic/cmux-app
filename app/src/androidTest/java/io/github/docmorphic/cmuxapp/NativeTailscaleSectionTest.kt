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

class NativeTailscaleSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val target = NativeComputerTarget("fixture-mac", "default", "Fixture Mac")
    private val grant = TailscaleSavedGrant(java.util.UUID.randomUUID().toString(), "user", "team", "a".repeat(64),
        target.deviceId, target.buildTag, PairingCode.Route("100.99.1.2", 58465))
    private var routes by mutableStateOf<List<TailscaleSavedGrant>?>(emptyList())
    private var method by mutableStateOf(NativeMacConnectionMethod.IROH)
    private fun content(pair: suspend (String, TailscaleSavedGrant?) -> Unit = { _, _ -> routes = listOf(grant) },
        remove: suspend (TailscaleSavedGrant) -> Unit = { routes = emptyList() }, reload: () -> Unit = {}) {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                NativeMacConnectionSection(target, NativeMacConnectionPreferences(values = mapOf(
                    NativeMacConnectionPreferences.identity(target.deviceId, target.buildTag) to NativeMacConnectionPreference(method))),
                    save = { method = it(NativeMacConnectionPreference(method)).method }, retry = {})
                NativeTailscaleSection(target, method, routes, pair, remove, reload)
            }
        } } }
    }
    private fun tailscale() {
        compose.onNodeWithContentDescription("Choose connection method").performClick()
        compose.onNodeWithText("Tailscale Only").performClick()
    }
    @Test fun methodWithoutGrantStaysOnDetailsUntilExplicitAddAndConfirmedConnect() {
        var pairs = 0
        content(pair = { code, old -> assertNull(old); assertEquals(grant.route, tailscalePairingInput(code).routes.single()); pairs++; routes = listOf(grant) })
        tailscale()
        compose.onNodeWithText("No authorized Tailscale route yet. This computer stays disconnected until you add a Tailscale connection.").assertIsDisplayed()
        compose.onNodeWithText("Pairing Code or IP Address:Port").assertDoesNotExist()
        compose.onNodeWithText("Add Tailscale Connection").performScrollTo().performClick()
        compose.onNodeWithText("Pairing Code or IP Address:Port").performTextInput("100.100.100.100:58465")
        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithText("Pairing Code or IP Address:Port").performTextReplacement("100.99.1.2:58465")
        compose.runOnIdle { assertEquals(0, pairs) }
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("100.99.1.2:58465").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pairing Code or IP Address:Port").assertDoesNotExist()
        assertEquals(1, pairs)
        capture()
    }
    @Test fun replacementFailureKeepsOriginalRouteAndEditorForRetry() {
        routes = listOf(grant); var tries = 0
        content(pair = { _, old -> assertEquals(grant, old); if (++tries == 1) error("Fixture verification failed")
            routes = listOf(grant.copy(route = PairingCode.Route("100.99.1.3", 58465))) })
        compose.onNodeWithContentDescription("Tailscale actions for 100.99.1.2:58465").performScrollTo().performClick()
        compose.onNodeWithText("Edit connection").performClick()
        compose.onNodeWithText("Pairing Code or IP Address:Port").performTextReplacement("100.99.1.3:58465")
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("Fixture verification failed").assertIsDisplayed()
        assertEquals(listOf(grant), routes)
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("100.99.1.3:58465").performScrollTo().assertIsDisplayed()
        assertEquals(2, tries)
    }
    @Test fun busyPairingBlocksDuplicateInputAndRemovalRequiresConfirmation() {
        val gate = CompletableDeferred<Unit>(); var pairs = 0; var removals = 0
        content(pair = { _, _ -> pairs++; gate.await(); routes = listOf(grant) }, remove = { removals++; routes = emptyList() })
        compose.onNodeWithText("Add Tailscale Connection").performScrollTo().performClick()
        compose.onNodeWithText("Pairing Code or IP Address:Port").performTextInput("100.99.1.2:58465")
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("Connect").assertIsNotEnabled(); compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, pairs); gate.complete(Unit) }
        compose.onNodeWithContentDescription("Tailscale actions for 100.99.1.2:58465").performScrollTo().performClick()
        compose.onNodeWithText("Remove connection").performClick()
        compose.onNodeWithText("Remove Tailscale connection?").assertIsDisplayed()
        assertEquals(0, removals)
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Tailscale actions for 100.99.1.2:58465").performClick()
        compose.onNodeWithText("Remove connection").performClick(); compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("100.99.1.2:58465").assertDoesNotExist(); assertEquals(1, removals)
    }
    @Test fun unreadableRoutesCannotBeOverwrittenByAddingAndCanReload() {
        routes = null
        content(reload = { routes = emptyList() })
        compose.onNodeWithText("Add Tailscale Connection").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Retry Tailscale connections").performClick()
        compose.onNodeWithText("Add Tailscale Connection").assertIsEnabled()
    }
    private fun capture() {
        compose.waitUntil(5_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false
        }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val folder = java.io.File(instrumentation.targetContext.filesDir, "test-captures").apply { mkdirs() }
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(folder, "tailscale-settings.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
