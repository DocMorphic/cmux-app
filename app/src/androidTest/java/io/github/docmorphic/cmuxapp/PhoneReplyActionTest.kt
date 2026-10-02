package io.github.docmorphic.cmuxapp

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Emulator only. Real System UI/receiver/WorkManager; radios off before fixture credentials exist. */
class PhoneReplyActionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store get() = NativeCredentialStore(context)
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val device get() = UiDevice.getInstance(instrumentation)
    private val team = NativeTeamScope("reply-ui-login", "fixture-user", "fixture-team", 1)
    private val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(PairingCodeParser.computer(
        IrohV2Computer("record", "a".repeat(64), "directory", "stable", "Fixture Mac", emptyList()), team), "directory", "Fixture Mac", "stable"), team)
    private val remote = PhonePushIdentity("fixture-mac", "mac-key", ByteArray(32) { (it + 32).toByte() })
    private var restoreWifi = false
    private var restoreData = false
    private var networkChanged = false
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .use { it.readBytes().toString(Charsets.UTF_8) }
    private fun waitFor(timeout: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(25)
        assertTrue(condition())
    }
    private fun alerts() = manager.activeNotifications.filter { it.notification.channelId == NativeNotificationDelivery.ALERT_CHANNEL &&
        it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
    private fun queued() = store.load()?.let { PhoneReplyOutbox(it).waiting(System.currentTimeMillis()) }.orEmpty()

    @Before fun setup() {
        check(Build.MODEL.startsWith("sdk_gphone")) { "This offline fixture must not change a personal phone's connectivity/account" }
        NativeNotificationService.setEnabled(context, false); store.clear()
        restoreWifi = Settings.Global.getInt(context.contentResolver, "wifi_on", 0) != 0
        restoreData = Settings.Global.getInt(context.contentResolver, "mobile_data", 0) != 0
        networkChanged = true
        shell("svc wifi disable"); shell("svc data disable")
        waitFor { context.getSystemService(ConnectivityManager::class.java).activeNetwork == null }
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        context.getSharedPreferences("native_notification_settings", Context.MODE_PRIVATE).edit().putBoolean("background_enabled", true).commit()
        store.update { state ->
            state.put("task_session", team.login).put("refresh_token", "fixture-refresh")
                .put("pairings", JSONArray().put(NativePairingRecords.encode(mac)))
            val keys = PhonePushKeyState(state); val local = keys.identity(team.login)
            keys.pin(team, mac.origin, PhonePushPeer(PhonePushTuple(team.userId, null, context.packageName, local.installationID,
                "physical-mac", "stable", "fixture.mac"), remote.descriptor()))
        }
    }
    @After fun cleanup() {
        if (!networkChanged) return
        // Remove credentials/packets before any network can return, even after a failed assertion.
        NativeNotificationService.setEnabled(context, false); store.clear()
        val work = WorkManager.getInstance(context)
        for (tag in listOf(PhoneReplyWork.SEND, PhoneReplyWork.NOTICES)) work.cancelAllWorkByTag(tag).result.get(5, TimeUnit.SECONDS)
        waitFor { listOf(PhoneReplyWork.SEND, PhoneReplyWork.NOTICES).all { tag ->
            work.getWorkInfosByTag(tag).get(2, TimeUnit.SECONDS).all { it.state.isFinished }
        } }
        device.pressBack()
        if (restoreWifi) shell("svc wifi enable")
        if (restoreData) shell("svc data enable")
    }
    private fun message(id: String = "reply-notice", surface: String = "surface", capable: Boolean = true): PhonePushMessage {
        val state = store.load()!!; val keys = PhonePushKeyState(state); val local = keys.existingIdentity(team.login)!!
        val peer = keys.peer(team, mac.origin)!!; val now = System.currentTimeMillis()
        val payload = JSONObject().put("kind", "notify").put("correlationId", UUID.randomUUID().toString())
            .put("expirationEpochSeconds", now / 1000 + 120).put("badgeCount", 1).put("hideContent", false)
            .put("title", "Reply fixture $id").put("subtitle", "").put("body", "Choose the next step")
            .put("workspaceId", "workspace").put("surfaceId", surface).put("retargetsToLiveSurfaceOwner", false)
            .put("category", if (capable) "cmux.terminal.reply" else "cmux.terminal")
            .put("replyShape", if (capable) "text" else "none").put("notificationId", id)
        val envelope = PhonePushCrypto.encrypt(payload.toString().toByteArray(), peer.tuple, local.keyID, remote.keyID,
            Base64.getDecoder().decode(local.descriptor().publicKey), remote.privateKey)
        return PhonePushMessage.open(JSONObject().put("encryptedPayloads", JSONArray().put(envelope.wire())).toString(),
            state, team, mac.origin, context.packageName, now)
    }
    private fun post(id: String = "reply-notice", surface: String = "surface", capable: Boolean = true): android.service.notification.StatusBarNotification {
        assertEquals(PhonePushAdmission.ACCEPTED, NativeNotificationDelivery(context).receivePush(message(id, surface, capable)) { true })
        waitFor { alerts().any { it.notification.extras.getString(Notification.EXTRA_TITLE) == "Reply fixture $id" } }
        return alerts().single { it.notification.extras.getString(Notification.EXTRA_TITLE) == "Reply fixture $id" }
    }
    private fun send(button: Notification.Action, text: String, fill: Intent = Intent()) {
        RemoteInput.addResultsToIntent(button.remoteInputs, fill, Bundle().apply { putCharSequence(PhoneReplyNotification.TEXT, text) })
        button.actionIntent.send(context, 0, fill)
    }
    private fun openAtMac(reply: PreparedPhoneReply): JSONObject {
        val packet = JSONObject(reply.body).getJSONObject("encryptedPayload")
        assertEquals(remote.installationID, packet.getString("installationID"))
        val tuple = PhonePushTuple.parse(packet.getJSONObject("tuple"))
        val binding = "cmux-phone-push-v2|${remote.keyID}|${reply.senderKeyID}|".toByteArray() + tuple.canonical()
        val privateKey = X25519PrivateKeyParameters(remote.privateKey)
        val sender = PhonePushKeyState(store.load()!!).existingIdentity(team.login)!!.descriptor()
        val suite = HPKE(HPKE.mode_auth, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_CHACHA20_POLY1305)
        val opened = suite.setupAuthR(Base64.getDecoder().decode(packet.getString("encapsulatedKey")),
            AsymmetricCipherKeyPair(privateKey.generatePublicKey(), privateKey), binding,
            X25519PublicKeyParameters(Base64.getDecoder().decode(sender.publicKey)))
            .open(binding, Base64.getDecoder().decode(packet.getString("ciphertext")))
        return JSONObject(opened.toString(Charsets.UTF_8))
    }
    private fun capture(name: String) {
        val dir = context.getExternalFilesDir(null)!!
        device.takeScreenshot(File(dir, "$name.png")); device.dumpWindowHierarchy(File(dir, "$name.xml"))
    }

    @Test fun actualNotificationShadeReplyQueuesOneEncryptedRequestAndWorkManagerJob() {
        val notice = post(); val button = notice.notification.actions.single()
        assertEquals("Reply", button.title.toString()); assertFalse(button.allowGeneratedReplies)
        if (Build.VERSION.SDK_INT >= 31) assertFalse(button.actionIntent.isImmutable)
        val literal = "literal reply λ 中"
        try {
            device.openNotification()
            checkNotNull(device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)reply"))), 5_000)).click()
            val input = checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000))
            input.text = literal
            waitFor { input.text == literal }; device.waitForIdle(1_000)
            capture("reply-action-input")
            val send = device.wait(Until.findObject(By.descContains("Send")), 5_000)
                ?: device.wait(Until.findObject(By.res("com.android.systemui", "remote_input_send")), 2_000)
            checkNotNull(send).click()
            waitFor { queued().size == 1 }
            waitFor { alerts().none { it.tag == notice.tag } }
            val packet = queued().single(); val opened = openAtMac(packet)
            assertEquals(literal, opened.getString("text")); assertEquals("surface", opened.getString("surfaceId"))
            assertEquals("workspace", opened.getString("workspaceId")); assertFalse(opened.getBoolean("retargetsToLiveSurfaceOwner"))
            assertNotNull(packet.peerEpoch)
            assertFalse(store.load()!!.toString().contains(literal))
            val work = WorkManager.getInstance(context).getWorkInfosByTag(PhoneReplyWork.SEND).get(5, TimeUnit.SECONDS)
                .filterNot { it.state.isFinished }
            assertEquals(1, work.size)
            assertEquals(androidx.work.WorkInfo.State.ENQUEUED, work.single().state)
            assertNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork)
            send(button, "duplicate broadcast must not become a new reply")
            instrumentation.waitForIdleSync(); Thread.sleep(300)
            assertEquals(packet.body, queued().single().body)
        } finally { capture("reply-action-after"); device.pressBack() }
    }

    @Test fun mutableRemoteInputCannotReplaceItsDestinationWithAnotherNotification() {
        val first = post("first", "first-surface"); val second = post("second", "second-surface")
        val actions = store.load()!!.getJSONObject(PhoneReplyActions.KEY).getJSONArray("items")
        val other = (0 until actions.length()).map(actions::getJSONObject).single { it.getString("surface") == "second-surface" }
        val forged = PhoneReplyNotification.intent(context, other.getString("route"), other.getString("action"))
            .setClass(context, MainActivity::class.java).putExtra("surfaceId", "second-surface").putExtra("pairing_code", "untrusted")
        assertNotEquals(first.notification.actions.single().actionIntent, second.notification.actions.single().actionIntent)
        send(first.notification.actions.single(), "original destination", forged)
        waitFor { queued().size == 1 }; waitFor { alerts().none { it.tag == first.tag } }
        assertEquals("first-surface", openAtMac(queued().single()).getString("surfaceId"))
        assertTrue(alerts().any { it.tag == second.tag })
    }

    @Test fun invalidInputDoesNotConsumeActionAndAccountReplacementRetiresIt() {
        val plain = post("plain", capable = false); assertTrue(plain.notification.actions.isNullOrEmpty())
        val notice = post(); val button = notice.notification.actions.single()
        send(button, " ")
        waitFor { alerts().any { it.tag == notice.tag && it.notification.extras.getString(Notification.EXTRA_TITLE) == "Reply not queued" } }
        assertTrue(queued().isEmpty())
        assertFalse(store.load()!!.getJSONObject(PhoneReplyActions.KEY).getJSONArray("items").getJSONObject(0).getBoolean("consumed"))
        store.update { it.put("task_session", "replacement") }
        send(button, "must not send after sign-out")
        waitFor { alerts().any { it.tag == notice.tag && it.notification.extras.getString(Notification.EXTRA_TITLE) == "Reply unavailable" } }
        assertTrue(queued().isEmpty()); assertFalse(store.load()!!.has(PhoneReplyActions.KEY))
    }

    @Test fun authenticatedPushUpgradesAnExistingFeedBannerWithoutRearmingAReplyOrResurrectingDismissal() {
        val delivery = NativeNotificationDelivery(context)
        delivery.refresh(mac.origin, "Fixture Mac", emptyList()) { true }
        delivery.refresh(mac.origin, "Fixture Mac", listOf(NativeNotification("feed-first", "workspace", "surface", "Feed fixture", "Body", false))) { true }
        waitFor { alerts().size == 1 }
        val initial = alerts().single(); assertTrue(initial.notification.actions.isNullOrEmpty())
        val upgraded = post("feed-first")
        assertEquals(initial.tag, upgraded.tag); assertEquals(1, upgraded.notification.actions.size)
        assertTrue(upgraded.notification.extras.getBoolean(PhoneReplyNotification.OFFERED))
        PhoneReplyNotification.status(context, upgraded, "Reply queued", "Waiting for delivery to your Mac.")
        waitFor { alerts().single().notification.actions.isNullOrEmpty() }
        delivery.receivePush(message("feed-first")) { true }
        assertEquals("Reply queued", alerts().single().notification.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(alerts().single().notification.actions.isNullOrEmpty())
        manager.cancel(upgraded.tag, upgraded.id); waitFor { alerts().isEmpty() }
        delivery.receivePush(message("feed-first")) { true }
        assertTrue(alerts().isEmpty())
    }
}
