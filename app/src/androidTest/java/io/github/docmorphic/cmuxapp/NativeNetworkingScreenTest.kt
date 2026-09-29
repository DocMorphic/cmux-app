package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.docmorphic.cmuxapp.iroh.IrxEndpointStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NativeNetworkingScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun snapshot(revision: Long = 7, endpoint: IrxEndpointStatus? = IrxEndpointStatus(true, "https://relay.example/")) =
        NativeNetworkingSnapshot.from(IrohV2ControlState(ready = true, mode = "websocket", directoryRevision = revision,
            permissionExpiresAt = 1790686500, relays = listOf(IrohV2Relay("https://relay.example/", "never-displayed-token", 1790686500, 1790686000))),
            endpoint, 1790685000)

    @Test fun pendingRefreshCannotRepeatAndDisplaysUpdatedLiveSnapshot() {
        val gate = CompletableDeferred<NativeNetworkingSnapshot>()
        val refreshes = AtomicInteger()
        var back = false
        compose.setContent { CmuxTheme { NativeNetworkingScreen(load = { refresh ->
            if (refresh) { refreshes.incrementAndGet(); gate.await() } else snapshot()
        }, onBack = { back = true }, pollMillis = 60_000) } }
        compose.onNodeWithText("Refresh Networking").assertIsEnabled()
        compose.onNodeWithText("Active", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Available · Home Relay").performScrollTo().assertIsDisplayed()
        capture("networking-live-relay")
        compose.onNodeWithText("Refresh Networking").performScrollTo().performClick()
        compose.onNodeWithText("Refreshing…").assertIsNotEnabled().performClick()
        compose.waitUntil(5000) { refreshes.get() == 1 }
        compose.runOnIdle { gate.complete(snapshot(8, null)) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Waiting for a Mac").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("8", substring = false).assertExists()
        compose.onNodeWithText("Available · Home Relay").assertDoesNotExist()
        compose.onNodeWithText("never-displayed-token").assertDoesNotExist()
        compose.onNodeWithText("‹  Back").performClick()
        compose.runOnIdle { assertTrue(back); assertEquals(1, refreshes.get()) }
    }

    @Test fun failedRefreshShowsSafeErrorAndCanRetry() {
        var failures = true
        compose.setContent { CmuxTheme { NativeNetworkingScreen(load = { refresh ->
            if (refresh && failures) error("private-fixture-server-body")
            snapshot()
        }, onBack = {}, pollMillis = 60_000) } }
        compose.onNodeWithText("Refresh Networking").performClick()
        compose.onNodeWithText("Could not refresh networking. Try again.").assertIsDisplayed()
        compose.onNodeWithText("private-fixture-server-body").assertDoesNotExist()
        compose.onNodeWithText("Active", substring = false).assertDoesNotExist()
        compose.runOnIdle { failures = false }
        compose.onNodeWithText("Refresh Networking").performClick()
        compose.onNodeWithText("Could not refresh networking. Try again.").assertDoesNotExist()
        compose.onNodeWithText("Active", substring = false).assertIsDisplayed()
    }

    @Test fun leavingPageCancelsReadAndDoesNotPublishIntoReplacement() {
        val entered = AtomicInteger()
        val cancelled = AtomicInteger()
        var scope by mutableIntStateOf(0)
        compose.setContent { CmuxTheme { key(scope) { NativeNetworkingScreen(load = {
            if (scope == 0) {
                entered.incrementAndGet()
                try { awaitCancellation() } finally { cancelled.incrementAndGet() }
            } else snapshot(9, null)
        }, onBack = {}, pollMillis = 60_000) } } }
        compose.waitUntil(5000) { entered.get() == 1 }
        compose.runOnIdle { scope = 1 }
        compose.waitUntil(5000) { cancelled.get() == 1 }
        compose.onNodeWithText("Waiting for a Mac").assertIsDisplayed()
        compose.onNodeWithText("9", substring = false).assertExists()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val root = instrumentation.targetContext.getExternalFilesDir(null)!!
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(root, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
