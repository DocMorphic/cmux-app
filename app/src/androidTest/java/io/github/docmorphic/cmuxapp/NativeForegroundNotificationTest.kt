package io.github.docmorphic.cmuxapp

import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch

/** Real lifecycle, RPC and OS banner cleanup without starting the background service. */
@OptIn(ExperimentalTestApi::class)
class NativeForegroundNotificationTest {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = NativeCredentialStore(context)
    private val delivery get() = NativeNotificationDelivery(context)
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var peer: NativeFixturePeer
    private var started = false
    private val code = "cmux-ios://attach?v=2&r=100.64.0.1:58465"
    private val otherCode = "cmux-ios://attach?v=2&r=100.64.0.2:58465"
    private fun alerts() = manager.activeNotifications.filter {
        it.notification.channelId == NativeNotificationDelivery.ALERT_CHANNEL &&
            it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
    }
    private fun origin() = store.pairedMacs().single { it.code == code }.origin
    private fun post(origin: String, vararg ids: String) {
        delivery.refresh(origin, "Fixture", emptyList()) { true }
        delivery.refresh(origin, "Fixture", ids.map { NativeNotification(it, "background-workspace", "background-terminal", "Ready", "Fixture", false) }) { true }
    }
    @Before fun setup() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "Emulator required" }
        started = true
        NativeNotificationService.setEnabled(context, false)
        store.clear(); NativeCredentialStore(context, "native_notification_state").clear()
        store.update { it.put("refresh_token", "foreground-fixture").put("task_session", "foreground-login") }
        store.rememberMac(otherCode, "other-mac", "Other Mac")
        store.rememberMac(code, "fixture-mac", "Fixture Mac")
        peer = NativeFixturePeer()
        NativeLifecycleTestActivity.connector = object : NativeConnector {
            override suspend fun connect(pairing: PairingCode.Tailscale, account: NativeAccount) =
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            override fun allowsSaved(mac: NativeCredentialStore.PairedMac) = mac.deviceId == "fixture-mac"
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
    }
    @After fun cleanup() {
        if (!started) return
        peer.releaseReconcile?.countDown(); NativeLifecycleTestActivity.connector = null; peer.close()
        NativeNotificationService.setEnabled(context, false)
        store.clear(); NativeCredentialStore(context, "native_notification_state").clear()
    }
    private fun openTerminal() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("cmux Android terminal", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun resumeRetainedTerminalClearsOnlyHandledBannersForItsMac() {
        ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use { scenario ->
            openTerminal()
            compose.waitForIdle()
            assertTrue(peer.requests.none { it.optString("method") == "notification.reconcile" })
            scenario.moveToState(Lifecycle.State.CREATED)
            val first = origin()
            post(first, "handled", "unhandled")
            // The sibling is saved but not admitted to this fixture connector.
            val other = pairingOrigin(otherCode, "other-mac", null)
            post(other, "handled")
            compose.waitUntil(5_000) { alerts().size == 3 }
            val replays = peer.requests.count { it.optString("method") == "mobile.terminal.replay" }
            peer.reconciledNotificationIds = listOf("handled", "foreign-not-requested")
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { alerts().size == 2 }
            assertEquals(listOf("unhandled"), delivery.deliveredIDs(first) { true })
            assertEquals(listOf("handled"), delivery.deliveredIDs(other) { true })
            val request = peer.requests.single { it.optString("method") == "notification.reconcile" }
            val ids = request.getJSONObject("params").getJSONArray("delivered_ids")
            assertEquals(setOf("handled", "unhandled"), (0 until ids.length()).map(ids::getString).toSet())
            assertEquals(replays, peer.requests.count { it.optString("method") == "mobile.terminal.replay" })
            assertTrue(NativeNotificationDismissOutbox(store.load()!!).pending().isEmpty())
            assertFalse(NativeNotificationService.isEnabled(context))
        }
    }

    @Test fun responseArrivingAfterBackgroundCannotClearBannersAndNextResumeRetries() {
        ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use { scenario ->
            openTerminal(); scenario.moveToState(Lifecycle.State.CREATED)
            val first = origin(); post(first, "handled")
            compose.waitUntil(5_000) { alerts().size == 1 }
            val gate = CountDownLatch(1); peer.releaseReconcile = gate
            peer.reconciledNotificationIds = listOf("handled")
            try {
                scenario.moveToState(Lifecycle.State.RESUMED)
                compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "notification.reconcile" } }
                scenario.moveToState(Lifecycle.State.CREATED)
                gate.countDown(); peer.releaseReconcile = null
                compose.waitForIdle()
                assertEquals(listOf("handled"), delivery.deliveredIDs(first) { true })
                scenario.moveToState(Lifecycle.State.RESUMED)
                compose.waitUntil(10_000) { alerts().isEmpty() }
                assertEquals(2, peer.requests.count { it.optString("method") == "notification.reconcile" })
            } finally { gate.countDown() }
        }
    }
}
