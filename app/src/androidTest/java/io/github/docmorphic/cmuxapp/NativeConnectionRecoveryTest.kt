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
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.semantics.SemanticsProperties

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

    private val firstCode = "cmux-ios://attach?v=2&r=100.64.0.1:58465"
    private val secondCode = "cmux-ios://attach?v=2&r=100.64.0.2:58465"
    private fun seedComputers() {
        NativeCredentialStore(context).apply {
            rememberMac(secondCode, "second-mac", "Second Mac")
            rememberMac(firstCode, "fixture-mac", "Fixture Mac")
            update { it.put("computer_selection", pairedMacs().single { mac -> mac.code == firstCode }.origin) }
        }
    }
    private fun selectComputer(name: String) {
        compose.onNodeWithContentDescription("Computer filter").performClick()
        compose.onNode(hasText(name) and hasAnyAncestor(isPopup())).performClick()
    }
    private fun awaitPairingConfirmation() {
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Connect to this Mac?").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Connect to this Mac?").assertIsDisplayed()
    }

    @Test fun unresolvedIrohLaunchCanBeCancelledToResumeSavedMac() = unresolvedIrohLaunch(timeout = false)
    @Test fun unresolvedIrohLaunchExpiresAndResumesSavedMac() = unresolvedIrohLaunch(timeout = true)
    private fun unresolvedIrohLaunch(timeout: Boolean) {
        NativeCredentialStore(context).rememberMac(firstCode, "fixture-mac", "Fixture Mac")
        val incoming = mutableStateOf<String?>("cmux-ios://attach?v=3&i=endpoint&d=device&ub=user&t=team&b=stable")
        val dials = AtomicInteger()
        compose.setContent { CmuxTheme {
            NativeScreen(onUseHelper = {}, incomingCode = incoming.value,
                onPairingHandled = { if (incoming.value == it) incoming.value = null },
                connector = NativeConnector { _, _ -> dials.incrementAndGet(); connected() })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Finding this Mac…").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Finding this Mac…").assertIsDisplayed()
        assertEquals(0, dials.get())
        if (timeout) {
            compose.mainClock.advanceTimeBy(30_100)
            compose.waitUntil(35_000) { incoming.value == null }
            compose.onNodeWithText("This Mac could not be found. Check its Mobile settings and try the pairing link again.").assertExists()
        } else {
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(context.getExternalFilesDir(null), "pairing-lookup.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            compose.onNodeWithText("Cancel").performClick()
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Finding this Mac…").assertDoesNotExist()
        compose.onNodeWithText("Claude Code task").performClick()
        compose.waitUntil(15_000) { peer.requests.any { it.optString("method") == "mobile.terminal.replay" } }
    }

    @Test fun coldLaunchConfirmationDefersSavedDialAndDismissalReleasesIt() {
        seedComputers()
        val incoming = mutableStateOf<String?>(secondCode)
        val dials = AtomicInteger()
        compose.setContent { MaterialTheme {
            NativeScreen(onUseHelper = {}, incomingCode = incoming.value,
                onPairingHandled = { if (incoming.value == it) incoming.value = null },
                connector = NativeConnector { _, _ -> dials.incrementAndGet(); connected() })
        } }
        awaitPairingConfirmation()
        compose.mainClock.advanceTimeBy(5_000); compose.waitForIdle()
        assertEquals("No startup or feed dial before the launch decision", 0, dials.get())
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Claude Code task").performClick()
        compose.waitUntil(15_000) { peer.requests.any { it.optString("method") == "mobile.terminal.replay" } }
    }

    @Test fun failedApprovedLaunchPairingFallsBackToSavedMac() {
        seedComputers()
        val incoming = mutableStateOf<String?>(secondCode)
        val failed = AtomicInteger()
        compose.setContent { MaterialTheme {
            NativeScreen(onUseHelper = {}, incomingCode = incoming.value,
                onPairingHandled = { if (incoming.value == it) incoming.value = null },
                connector = NativeConnector { pairing, _ ->
                    if (pairing.routes.first().host == "100.64.0.2") {
                        failed.incrementAndGet(); throw java.io.IOException("Launch attach fixture failure")
                    }
                    connected()
                })
        } }
        awaitPairingConfirmation()
        assertEquals(0, failed.get())
        compose.onNode(hasText("Connect") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Could not switch computers. Your previous computer is selected again.").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Claude Code task").performClick()
        compose.waitUntil(15_000) { peer.requests.any { it.optString("method") == "mobile.terminal.replay" } }
        assertEquals(firstCode, NativeCredentialStore(context).load()?.optString("pairing_code"))
        assertTrue(failed.get() > 0)
    }

    @Test fun successfulLaunchPairingSelectsNewVerifiedMacInsteadOfOldFilter() {
        val other = NativeFixturePeer().apply { deviceId = "second-mac"; displayName = "Second Mac" }
        NativeCredentialStore(context).apply {
            rememberMac(firstCode, "fixture-mac", "Fixture Mac")
            update { it.put("computer_selection", pairedMacs().single().origin) }
        }
        val incoming = mutableStateOf<String?>(secondCode)
        val dials = AtomicInteger()
        try {
            compose.setContent { MaterialTheme {
                NativeScreen(onUseHelper = {}, incomingCode = incoming.value,
                    onPairingHandled = { if (incoming.value == it) incoming.value = null },
                    connector = NativeConnector { pairing, _ ->
                        dials.incrementAndGet()
                        val target = if (pairing.routes.first().host == "100.64.0.2") other else peer
                        MobileRpcClient(PairingCode.Route("127.0.0.1", target.port), { "fixture-token" }).also { it.connect() }
                    })
            } }
            awaitPairingConfirmation()
            assertEquals(0, dials.get())
            compose.onNode(hasText("Connect") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(15_000) { NativeCredentialStore(context).load()?.optString("pairing_code") == secondCode }
            compose.onNodeWithContentDescription("Computer filter").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Second Mac"))
            compose.onNodeWithText("Claude Code task").performClick()
            compose.waitUntil(15_000) { other.requests.any { it.optString("method") == "mobile.terminal.replay" } }
            assertFalse(peer.requests.any { it.optString("method") == "mobile.terminal.replay" })
        } finally { other.close() }
    }

    @Test fun failedComputerSwitchRestoresVerifiedMacAndFilter() {
        val other = NativeFixturePeer().apply { deviceId = "second-mac"; displayName = "Second Mac" }
        val failOther = AtomicBoolean(); val firstDials = AtomicInteger(); val failed = AtomicInteger()
        seedComputers()
        try {
            compose.setContent { MaterialTheme {
                NativeScreen(onUseHelper = {}, connector = NativeConnector { pairing, _ ->
                    if (pairing.routes.first().host == "100.64.0.2") {
                        if (failOther.get()) { failed.incrementAndGet(); throw java.io.IOException("Fixture dial failure") }
                        MobileRpcClient(PairingCode.Route("127.0.0.1", other.port), { "fixture-token" }).also { it.connect() }
                    } else { firstDials.incrementAndGet(); connected() }
                })
            } }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().size == 1 }
            val before = firstDials.get()
            failOther.set(true)
            selectComputer("Second Mac")
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Could not switch computers. Your previous computer is selected again.").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15_000) { firstDials.get() > before && NativeCredentialStore(context).load()?.optString("pairing_code") == firstCode }
            compose.onNodeWithContentDescription("Computer filter").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Fixture Mac"))
            compose.onNodeWithText("Claude Code task").performClick()
            compose.waitUntil(15_000) { peer.requests.any { it.optString("method") == "mobile.terminal.replay" } }
            assertTrue(failed.get() > 0)
            assertFalse(other.requests.any { it.optString("method") == "mobile.terminal.replay" })
        } finally { other.close() }
    }

    @Test fun newerComputerSelectionWinsOverLateFailedSwitch() {
        val other = NativeFixturePeer().apply { deviceId = "second-mac"; displayName = "Second Mac" }
        val third = NativeFixturePeer().apply { deviceId = "third-mac"; displayName = "Third Mac" }
        val thirdCode = "cmux-ios://attach?v=2&r=100.64.0.3:58465"
        val holdOther = AtomicBoolean(); val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>(); val completed = CompletableDeferred<Unit>()
        NativeCredentialStore(context).rememberMac(thirdCode, "third-mac", "Third Mac")
        seedComputers()
        try {
            compose.setContent { MaterialTheme {
                NativeScreen(onUseHelper = {}, connector = NativeConnector { pairing, _ ->
                    val target = when (pairing.routes.first().host) {
                        "100.64.0.2" -> {
                            if (holdOther.get()) try { withContext(NonCancellable) {
                                started.complete(Unit); release.await(); throw java.io.IOException("Late fixture failure")
                            } } finally { completed.complete(Unit) }
                            other
                        }
                        "100.64.0.3" -> third
                        else -> peer
                    }
                    MobileRpcClient(PairingCode.Route("127.0.0.1", target.port), { "fixture-token" }).also { it.connect() }
                })
            } }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().size == 1 }
            holdOther.set(true); selectComputer("Second Mac")
            compose.waitUntil(5_000) { started.isCompleted }
            selectComputer("Third Mac")
            compose.waitUntil(15_000) { NativeCredentialStore(context).load()?.optString("pairing_code") == thirdCode }
            release.complete(Unit)
            compose.waitUntil(5_000) { completed.isCompleted }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Computer filter").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Third Mac"))
            compose.onNodeWithText("Could not switch computers. Your previous computer is selected again.").assertDoesNotExist()
            compose.onNodeWithText("Claude Code task").performClick()
            compose.waitUntil(15_000) { third.requests.any { it.optString("method") == "mobile.terminal.replay" } }
            assertEquals(thirdCode, NativeCredentialStore(context).load()?.optString("pairing_code"))
            assertFalse(peer.requests.any { it.optString("method") == "mobile.terminal.replay" })
        } finally { release.complete(Unit); other.close(); third.close() }
    }

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
