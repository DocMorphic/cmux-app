package io.github.docmorphic.cmuxapp

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Base64
import java.util.UUID

class PhonePushDeliveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = NativeCredentialStore(context)
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val team = NativeTeamScope("push-delivery-login", "fixture-user", "fixture-team", 1)
    private val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(PairingCodeParser.computer(
        IrohV2Computer("record", "a".repeat(64), "directory-mac", "stable", "Fixture Mac", emptyList()), team),
        "directory-mac", "Fixture Mac", "stable"), team)
    private val remote = PhonePushIdentity.generate()

    @Before fun setup() {
        NativeNotificationService.setEnabled(context, false)
        store.clear()
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        // Opt-in fixture without starting a real account/network foreground service.
        context.getSharedPreferences("native_notification_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("background_enabled", true).commit()
        store.update { state ->
            state.put("task_session", team.login).put("refresh_token", "fixture-refresh")
                .put("pairings", JSONArray().put(NativePairingRecords.encode(mac)))
            val keys = PhonePushKeyState(state); val local = keys.identity(team.login)
            keys.pin(team, mac.origin, PhonePushPeer(PhonePushTuple(team.userId, null, context.packageName, local.installationID,
                "physical-mac", "stable", "fixture.mac"), remote.descriptor()))
        }
    }
    @After fun cleanup() { NativeNotificationService.setEnabled(context, false); store.clear() }
    private fun message(kind: String = "notify", id: String? = "push-notice", at: Long = System.currentTimeMillis()): PhonePushMessage {
        val keys = PhonePushKeyState(store.load()!!); val local = keys.existingIdentity(team.login)!!
        val peer = keys.peer(team, mac.origin)!!
        val payload = JSONObject().put("kind", kind).put("correlationId", UUID.randomUUID().toString())
            .put("expirationEpochSeconds", at / 1000 + 120).put("badgeCount", 1).put("hideContent", false)
            .put("title", "Push fixture").put("subtitle", "Workspace").put("body", "Encrypted fixture body")
            .put("workspaceId", "workspace").put("surfaceId", "surface").put("retargetsToLiveSurfaceOwner", false)
            .put("category", "cmux.terminal.reply").put("replyShape", "text").put("notificationId", id)
            .put("notificationIds", JSONArray().put(id))
        val envelope = PhonePushCrypto.encrypt(payload.toString().toByteArray(), peer.tuple, local.keyID, remote.keyID,
            Base64.getDecoder().decode(local.descriptor().publicKey), remote.privateKey)
        return PhonePushMessage.open(JSONObject().put("encryptedPayloads", JSONArray().put(envelope.wire())).toString(),
            store.load()!!, team, mac.origin, context.packageName, at)
    }
    private fun alerts() = manager.activeNotifications.filter { it.notification.channelId == NativeNotificationDelivery.ALERT_CHANNEL &&
        it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
    private fun waitFor(test: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!test() && System.currentTimeMillis() < until) Thread.sleep(25)
        assertTrue(test())
    }
    private fun routes() = NativeCredentialStore(context, "native_notification_state").load()!!
        .let { NativeNotificationLedger(it).destinations() }

    @Test fun encryptedPushPostsOnceBeforeFeedBaselineAndTapKeepsItsScopedRoute() {
        val delivery = NativeNotificationDelivery(context); val value = message()
        assertTrue(value.canReply)
        var checks = 0
        assertEquals(PhonePushAdmission.ACCEPTED, delivery.receivePush(value) {
            if (++checks == 3) {
                // The final admission check runs before the OS can expose a tap.
                // A separate store/delivery instance must already recover its route.
                val saved = routes().single()
                assertEquals(saved, NativeNotificationDelivery(context).destination(saved.routeId))
                assertTrue(alerts().isEmpty())
            }
            true
        })
        assertEquals(3, checks)
        waitFor { alerts().size == 1 }
        val alert = alerts().single().notification
        assertEquals("Push fixture", alert.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Encrypted fixture body", alert.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(Notification.VISIBILITY_PRIVATE, alert.visibility)
        val route = routes().single()
        assertEquals(team.login, route.login); assertEquals(mac.origin, route.origin)
        assertEquals("workspace", route.workspaceId); assertEquals("surface", route.surfaceId)
        assertTrue(route.dismissible); assertNotNull(alert.deleteIntent)
        assertEquals(PhonePushAdmission.DUPLICATE, NativeNotificationDelivery(context).receivePush(value) { true })
        delivery.refresh(mac.origin, "Fixture Mac", listOf(value.notification!!)) { true }
        assertEquals(1, alerts().size)
        // A fresh correlation for the same logical notification also stays quiet.
        assertEquals(PhonePushAdmission.ACCEPTED, delivery.receivePush(message()) { true })
        assertEquals(1, alerts().size)
        val intent = NativeNotificationDelivery.launchIntent(context, route.routeId)
        assertNull(intent.extras); assertFalse(intent.toUri(0).contains("workspace"))
        assertEquals(route, delivery.destination(NativeNotificationDelivery.routeFromIntent(context, intent)!!))
    }

    @Test fun remoteDismissCancelsBannerWithoutQueueingSwipeAndBlocksLateNotify() {
        val delivery = NativeNotificationDelivery(context)
        delivery.receivePush(message()) { true }; waitFor { alerts().size == 1 }
        assertEquals(PhonePushAdmission.ACCEPTED, delivery.receivePush(message("dismiss")) { true })
        waitFor { alerts().isEmpty() }
        assertTrue(NativeNotificationDismissOutbox(store.load()!!).pending().isEmpty())
        assertEquals(PhonePushAdmission.DISMISSED, NativeNotificationDelivery(context).receivePush(message()) { true })
        val earlyDismiss = message("dismiss", "out-of-order")
        delivery.receivePush(earlyDismiss) { true }
        assertEquals(PhonePushAdmission.DISMISSED, delivery.receivePush(message(id = "out-of-order")) { true })
        delivery.refresh(mac.origin, "Fixture Mac", listOf(message().notification!!, message(id = "out-of-order").notification!!)) { true }
        assertTrue(alerts().isEmpty())
    }

    @Test fun missingMacNotificationIdNeverGeneratesASyntheticDismissal() {
        val delivery = NativeNotificationDelivery(context); val value = message(id = null)
        assertEquals(PhonePushAdmission.ACCEPTED, delivery.receivePush(value) { true })
        waitFor { alerts().size == 1 }
        assertNull(alerts().single().notification.deleteIntent)
        val route = routes().single(); assertFalse(route.dismissible)
        store.update { NativeNotificationDismissOutbox(it).enqueue(route) }
        assertTrue(NativeNotificationDismissOutbox(store.load()!!).pending().isEmpty())
    }

    @Test fun expiredOptedOutRevokedAndReplacedAccountsCannotPost() {
        val delivery = NativeNotificationDelivery(context); val value = message()
        assertEquals(PhonePushAdmission.EXPIRED, delivery.receivePush(message(at = System.currentTimeMillis() - 180_000)) { true })
        var checks = 0
        assertEquals(PhonePushAdmission.RETIRED, delivery.receivePush(value) { ++checks < 2 })
        assertEquals(2, checks)
        NativeNotificationService.setEnabled(context, false)
        assertEquals(PhonePushAdmission.RETIRED, delivery.receivePush(value) { true })
        context.getSharedPreferences("native_notification_settings", Context.MODE_PRIVATE).edit().putBoolean("background_enabled", true).commit()
        store.update { it.put("task_session", "replacement") }
        assertEquals(PhonePushAdmission.RETIRED, delivery.receivePush(value) { true })
        assertTrue(alerts().isEmpty())
    }
}
