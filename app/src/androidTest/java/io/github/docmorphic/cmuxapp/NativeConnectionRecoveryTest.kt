package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

/** Production connection effects, using a disposable account and a local Mac peer. */
@OptIn(ExperimentalTestApi::class)
class NativeConnectionRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var peer: NativeFixturePeer
    private var started = false
    @Before fun setup() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "Emulator required" }
        started = true; peer = NativeFixturePeer()
        NativeCredentialStore(context).clear()
        NativeCredentialStore(context).update {
            it.put("refresh_token", "timeout-emulator-fixture")
            it.put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465")
        }
    }
    @After fun cleanup() {
        if (started) { compose.activity.finish(); peer.close(); NativeCredentialStore(context).clear() }
    }
    private suspend fun connected() = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
        .also { it.connect() }

    @Test fun expiredDialAutomaticallyRetriesAndReachesWorkspace() {
        val attempts = AtomicInteger()
        compose.setContent { MaterialTheme {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                if (attempts.incrementAndGet() == 1) withTimeout(30) { awaitCancellation() }
                connected()
            })
        } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(attempts.get() >= 2)
        compose.onNodeWithText("Claude Code task").assertIsDisplayed()
        assertTrue(peer.requests.any { it.optString("method") == "mobile.host.status" })
        assertEquals("fixture-mac", NativeCredentialStore(context).pairedMacs().single().deviceId)
    }

    @Test fun disposedScreenDoesNotRetryALateTimeout() {
        val visible = mutableStateOf(true)
        val attempts = AtomicInteger(); val release = CompletableDeferred<Unit>(); val completed = CompletableDeferred<Unit>()
        compose.setContent { MaterialTheme {
            if (visible.value) NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                attempts.incrementAndGet()
                try { withContext(NonCancellable) {
                    release.await(); withTimeout(1) { awaitCancellation() }
                } } finally { completed.complete(Unit) }
            }) else Text("Screen closed")
        } }
        try {
            compose.waitUntil(5_000) { attempts.get() == 1 }
            compose.runOnIdle { visible.value = false }
            compose.onNodeWithText("Screen closed").assertIsDisplayed()
            release.complete(Unit)
            compose.waitUntil(5_000) { completed.isCompleted }
            compose.mainClock.advanceTimeBy(35_000); compose.waitForIdle()
            assertEquals(1, attempts.get())
            assertTrue(NativeCredentialStore(context).pairedMacs().isEmpty())
            assertTrue(peer.requests.isEmpty())
        } finally { release.complete(Unit) }
    }

    @Test fun unansweredRealHostHandshakeTimesOutThenAutomaticallyRecovers() {
        peer.ignoreNextHostStatus.set(true)
        val clients = java.util.concurrent.CopyOnWriteArrayList<MobileRpcClient>()
        compose.setContent { MaterialTheme {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ -> connected().also { clients += it } })
        } }
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(clients.size >= 2)
        assertTrue("Timed out handshake lease must close", clients.first().isClosed)
        assertTrue(peer.requests.count { it.optString("method") == "mobile.host.status" } >= 2)
        compose.onNodeWithText("Claude Code task").assertIsDisplayed()
    }
}
