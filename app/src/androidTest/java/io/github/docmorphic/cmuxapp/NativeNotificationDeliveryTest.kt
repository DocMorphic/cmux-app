package io.github.docmorphic.cmuxapp

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NativeNotificationDeliveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val a = "cmux-ios://attach?v=2&r=100.64.0.1:58465"
    private val b = "cmux-ios://attach?v=2&r=100.64.0.2:58465"
    private fun item(id: String, read: Boolean = false) = NativeNotification(id,
        "workspace", "surface", "Ready", "Task finished", read)
    private fun alerts() = manager.activeNotifications.filter { it.notification.channelId == NativeNotificationDelivery.ALERT_CHANNEL }
    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(25)
        assertTrue(condition())
    }
    @Before fun setup() {
        NativeNotificationService.setEnabled(context, false)
        NativeCredentialStore(context).clear()
        NativeCredentialStore(context).rememberMac(a, "a", "First Mac", "stable")
        NativeCredentialStore(context).rememberMac(b, "b", "Second Mac", "stable")
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        }
    }
    @After fun cleanup() {
        NativeNotificationService.setEnabled(context, false)
        NativeCredentialStore(context).clear()
    }

    @Test fun independentAlertsPersistWithoutPairingDataInIntentsAndReadCancelsOnlyItsMac() {
        val delivery = NativeNotificationDelivery(context)
        delivery.refresh(pairingOrigin(a, "a", "stable"), "First Mac", emptyList()) { true }
        delivery.refresh(pairingOrigin(b, "b", "stable"), "Second Mac", emptyList()) { true }
        delivery.refresh(pairingOrigin(a, "a", "stable"), "First Mac", listOf(item("FB"), item("Ea"))) { true }
        delivery.refresh(pairingOrigin(b, "b", "stable"), "Second Mac", listOf(item("FB"))) { true }
        waitFor { alerts().size == 3 }
        assertEquals(3, alerts().map { it.tag }.toSet().size)
        assertEquals(3, alerts().map { it.notification.contentIntent }.toSet().size)
        val routes = NativeCredentialStore(context, "native_notification_state").load()!!
            .let { NativeNotificationLedger(it).destinations() }
        assertEquals(3, routes.size)
        routes.forEach { route ->
            assertEquals(route, NativeNotificationDelivery(context).destination(route.routeId))
            val launch = NativeNotificationDelivery.launchIntent(context, route.routeId)
            assertEquals(route.routeId, NativeNotificationDelivery.routeFromIntent(context, launch))
            assertTrue(launch.extras == null)
            assertFalse(launch.toUri(0).contains("100.64"))
            assertFalse(launch.toUri(0).contains("workspace"))
        }
        val encrypted = context.getSharedPreferences("native_notification_state", Context.MODE_PRIVATE).getString("state", "")!!
        assertFalse(encrypted.contains("workspace"))
        // A fresh delivery object simulates reconstruction after process death; no duplicate alert.
        NativeNotificationDelivery(context).refresh(pairingOrigin(a, "a", "stable"), "First Mac", listOf(item("FB"), item("Ea"))) { true }
        waitFor { alerts().size == 3 }
        delivery.refresh(pairingOrigin(a, "a", "stable"), "First Mac", listOf(item("FB", true), item("Ea"))) { true }
        waitFor { alerts().size == 2 }
        assertEquals(setOf("First Mac", "Second Mac"), alerts().map { it.notification.extras.getString("android.subText") }.toSet())
        NativeCredentialStore(context).forgetMac(b)
        delivery.prune(setOf(pairingOrigin(a, "a", "stable")))
        waitFor { alerts().size == 1 }
        assertNull(delivery.destination(routes.single { it.origin == pairingOrigin(b, "b", "stable") }.routeId))
    }

    @Test fun lateFeedCannotPublishAndUntrustedIntentCannotSelectMac() {
        val delivery = NativeNotificationDelivery(context)
        delivery.refresh(pairingOrigin(a, "a", "stable"), "First Mac", emptyList()) { true }
        delivery.refresh(pairingOrigin(a, "a", "stable"), "First Mac", listOf(item("late"))) { false }
        assertTrue(alerts().isEmpty())
        var checks = 0
        delivery.refresh(pairingOrigin(a, "a", "stable"), "First Mac", listOf(item("late"))) { ++checks < 3 }
        assertEquals(3, checks)
        assertTrue(alerts().isEmpty())
        // Losing eligibility while the destination is saved must not acknowledge the alert.
        delivery.refresh(pairingOrigin(a, "a", "stable"), "First Mac", listOf(item("late"))) { true }
        waitFor { alerts().size == 1 }
        val valid = NativeNotificationDelivery.launchIntent(context, java.util.UUID.randomUUID().toString())
        assertNull(NativeNotificationDelivery.routeFromIntent(context, Intent().putExtra("notification_workspace_id", "workspace")))
        assertNull(NativeNotificationDelivery.routeFromIntent(context, Intent(valid).setData(valid.data!!.buildUpon().authority("other.app").build())))
        assertNull(NativeNotificationDelivery.routeFromIntent(context, Intent(valid).setData(valid.data!!.buildUpon().appendQueryParameter("pairing", a).build())))
    }

    @Test fun siblingInstallationsAreRetainedAndHostMismatchIsRejected() {
        val store = NativeCredentialStore(context)
        store.clear()
        store.rememberMac(a, "same-device", "Stable", "stable")
        store.rememberMac(b, "same-device", "Dev", "dev")
        assertEquals(2, store.pairedMacs().size)
        val mac = store.pairedMacs().first()
        mac.requireMatchingHost(JSONObject().put("mac_device_id", "same-device").put("mac_instance_tag", "stable"))
        assertTrue(runCatching { mac.requireMatchingHost(JSONObject().put("mac_device_id", "other").put("mac_instance_tag", "stable")) }.isFailure)
        assertTrue(runCatching { mac.requireMatchingHost(JSONObject().put("mac_device_id", "same-device").put("mac_instance_tag", "dev")) }.isFailure)
        store.rememberMac(a + "&ub=test", "same-device", "Stable", "stable")
        assertEquals(2, store.pairedMacs().size)
        assertFalse(store.pairedMacs().any { it.code == a })
    }

    @Test fun foregroundServiceStartsAndOptOutClearsItsAlerts() {
        // No signed-in account: lifecycle test must not contact production services.
        compose.runOnUiThread { NativeNotificationService.setEnabled(context, true) }
        waitFor { manager.activeNotifications.any { it.notification.channelId == "cmux_connection" } }
        assertTrue(NativeNotificationService.isEnabled(context))
        compose.runOnUiThread { NativeNotificationService.setEnabled(context, false) }
        waitFor { manager.activeNotifications.none { it.notification.channelId == "cmux_connection" } }
        assertFalse(NativeNotificationService.isEnabled(context))
    }
    @Test fun nativeDuplicateRepairKeepsPostedIntentsAndDoesNotAlertOldItemsAgain() {
        val store = NativeCredentialStore(context)
        val team = NativeTeamScope("fixture-login", "fixture-user", "fixture-team", 1)
        fun mac(peer: String) = NativeCredentialStore.PairedMac(PairingCodeParser.computer(
            IrohV2Computer("record", peer.repeat(64), "fixture-mac", "default", "Fixture Mac", emptyList()), team),
            "fixture-mac", "Fixture Mac", "default")
        val first = mac("a"); val second = mac("b")
        store.update { it.put("task_session", team.login).put("refresh_token", "fixture-refresh")
            .put("pairings", org.json.JSONArray(listOf(first, second).map(NativePairingRecords::encode))) }
        val delivery = NativeNotificationDelivery(context)
        delivery.refresh(first.origin, first.name, emptyList()) { true }
        delivery.refresh(second.origin, second.name, emptyList()) { true }
        delivery.refresh(first.origin, first.name, listOf(item("shared"))) { true }
        delivery.refresh(second.origin, second.name, listOf(item("shared"))) { true }
        waitFor { alerts().size == 2 }
        val oldRoutes = NativeCredentialStore(context, "native_notification_state").load()!!
            .let { NativeNotificationLedger(it).destinations() }
        val oldIntents = alerts().map { it.notification.contentIntent }.toSet()
        val repaired = store.rememberAuthenticatedMac(mac("c"), team) { true }
        assertEquals(setOf(first.origin, second.origin), NativeCredentialStore(context).pairedMacs().single().origins)
        delivery.prune(setOf(repaired.origin))
        oldRoutes.forEach { assertEquals(it.copy(origin = repaired.origin), delivery.destination(it.routeId)) }
        delivery.refresh(repaired.origin, repaired.name, listOf(item("shared"), item("new"))) { true }
        waitFor { alerts().size == 3 }
        assertTrue(alerts().map { it.notification.contentIntent }.containsAll(oldIntents))
        delivery.refresh(repaired.origin, repaired.name, listOf(item("shared", true), item("new"))) { true }
        waitFor { alerts().size == 1 }
        store.forgetMac(repaired.code, team) { true }
        delivery.prune(emptySet())
        waitFor { alerts().isEmpty() }
    }

}
