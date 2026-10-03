package io.github.docmorphic.cmuxapp

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.Base64
import java.util.UUID

/** Local membership HTTP only; no Firebase project, token or cloud message is created. */
class PhoneFcmIngressTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = NativeCredentialStore(context)
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val team = NativeTeamScope("fcm-fixture-login", "fixture-user", "fixture-team", 1)
    private val remote = PhonePushIdentity.generate()
    private val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(PairingCodeParser.computer(
        IrohV2Computer("record", "a".repeat(64), "directory-mac", "stable", "Fixture Mac", emptyList()), team),
        "directory-mac", "Fixture Mac", "stable"), team)

    @Before fun setup() {
        NativeNotificationService.setEnabled(context, false); store.clear()
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        context.getSharedPreferences("native_notification_settings", Context.MODE_PRIVATE).edit().putBoolean("background_enabled", true).commit()
        store.update { state ->
            state.put("task_session", team.login).put("refresh_token", "fixture-refresh")
                .put("pairings", JSONArray().put(NativePairingRecords.encode(mac)))
            val keys = PhonePushKeyState(state); val local = keys.identity(team.login)
            keys.pin(team, mac.origin, PhonePushPeer(PhonePushTuple(team.userId, null, context.packageName,
                local.installationID, "physical-mac", "stable", "fixture.mac"), remote.descriptor()))
        }
    }
    @After fun cleanup() { NativeNotificationService.setEnabled(context, false); store.clear() }
    private fun raw(kind: String = "notify", at: Long = System.currentTimeMillis()): String {
        val keys = PhonePushKeyState(store.load()!!); val local = keys.existingIdentity(team.login)!!; val peer = keys.peer(team, mac.origin)!!
        val payload = JSONObject().put("kind", kind).put("correlationId", UUID.randomUUID().toString())
            .put("expirationEpochSeconds", at / 1000 + 120).put("badgeCount", 1).put("hideContent", false)
            .put("title", "FCM fixture").put("subtitle", "Workspace").put("body", "Private ingress λ")
            .put("workspaceId", "workspace").put("surfaceId", "surface").put("retargetsToLiveSurfaceOwner", false)
            .put("category", "cmux.terminal.reply").put("replyShape", "text").put("notificationId", "fcm-notice")
            .put("notificationIds", JSONArray().put("fcm-notice"))
        val envelope = PhonePushCrypto.encrypt(payload.toString().toByteArray(), peer.tuple, local.keyID, remote.keyID,
            Base64.getDecoder().decode(local.descriptor().publicKey), remote.privateKey)
        return JSONObject().put("encryptedPayloads", JSONArray().put(envelope.wire())).toString()
    }
    private fun queue(raw: String) { store.update { assertTrue(PhoneFcmQueue(it).enqueue(raw, System.currentTimeMillis())) } }
    private fun alerts() = manager.activeNotifications.filter { it.notification.channelId == NativeNotificationDelivery.ALERT_CHANNEL &&
        it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(25) }
    private fun MockWebServer.profile(member: Boolean = true) {
        enqueue(MockResponse().setBody("""{"id":"fixture-user","selected_team":{"id":"other-team"}}"""))
        enqueue(MockResponse().setBody(JSONObject().put("items", JSONArray((listOf("other-team") +
            if (member) listOf(team.teamId) else emptyList()).map { JSONObject().put("id", it).put("display_name", it) })).toString()))
    }
    private fun worker(server: MockWebServer, audience: NativeMacBuildAudience? = null) = TestListenableWorkerBuilder<PhoneFcmWorker>(context)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName != PhoneFcmWorker::class.java.name) null else PhoneFcmWorker(appContext, workerParameters) {
                    PhoneFcmBackground(context, store, NativeAccountTeams({ "fixture-token" }, { store.taskSession() }, server.url("/api/v1/")),
                        audience = audience)
                }
        }).build()

    @Test fun consumerAudienceDiscardsAnAuthenticatedPushFromUnsupportedBuild() = runBlocking {
        queue(raw())
        MockWebServer().use { server ->
            server.profile()
            assertEquals(ListenableWorker.Result.success(), worker(server, NativeMacBuildAudience.consumer).doWork())
            assertTrue(alerts().isEmpty())
            assertTrue(PhoneFcmQueue(store.load()!!).waiting(System.currentTimeMillis()).isEmpty())
        }
    }

    @Test fun freshMembershipDecryptsStoredCiphertextPostsOnceAndDismisses() = runBlocking {
        val data = raw(); queue(data)
        assertFalse(context.getSharedPreferences("native_cmux", Context.MODE_PRIVATE).all.toString().contains(data))
        assertFalse(context.getSharedPreferences("native_cmux", Context.MODE_PRIVATE).all.toString().contains("Private ingress"))
        MockWebServer().use { server ->
            server.profile(); assertEquals(ListenableWorker.Result.success(), worker(server).doWork())
            waitFor { alerts().size == 1 }
            assertEquals("Private ingress λ", alerts().single().notification.extras.getString(Notification.EXTRA_TEXT))
            val firstPost = alerts().single().postTime
            assertFalse(store.load()!!.has(PhoneFcmQueue.KEY))
            queue(data); server.profile(); assertEquals(ListenableWorker.Result.success(), worker(server).doWork())
            assertEquals(firstPost, alerts().single().postTime)
            queue(raw("dismiss")); server.profile(); assertEquals(ListenableWorker.Result.success(), worker(server).doWork())
            waitFor { alerts().isEmpty() }
            assertEquals(6, server.requestCount)
        }
    }
    @Test fun failedMembershipWaitsAndFreshRevocationCannotPost() = runBlocking {
        queue(raw())
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(ListenableWorker.Result.retry(), worker(server).doWork())
            assertTrue(alerts().isEmpty()); assertTrue(store.load()!!.has(PhoneFcmQueue.KEY))
            server.profile(member = false)
            assertEquals(ListenableWorker.Result.success(), worker(server).doWork())
            assertTrue(alerts().isEmpty()); assertFalse(store.load()!!.has(PhoneFcmQueue.KEY))
        }
    }
    @Test fun expiredAndForgottenMacMessagesCannotPost() = runBlocking {
        queue(raw(at = System.currentTimeMillis() - 180_000))
        MockWebServer().use { server ->
            server.profile(); assertEquals(ListenableWorker.Result.success(), worker(server).doWork()); assertTrue(alerts().isEmpty())
            queue(raw()); store.forgetMac(mac.code)
            server.profile(); assertEquals(ListenableWorker.Result.success(), worker(server).doWork()); assertTrue(alerts().isEmpty())
        }
    }
    @Test fun optOutAndReplacedLoginRemovePendingMessagesWithoutNetwork() = runBlocking {
        queue(raw()); NativeNotificationService.setEnabled(context, false)
        MockWebServer().use { server ->
            assertEquals(ListenableWorker.Result.success(), worker(server).doWork()); assertEquals(0, server.requestCount)
            assertFalse(store.load()!!.has(PhoneFcmQueue.KEY))
            context.getSharedPreferences("native_notification_settings", Context.MODE_PRIVATE).edit().putBoolean("background_enabled", true).commit()
            queue(raw()); store.update { it.put("task_session", "replacement") }
            assertEquals(ListenableWorker.Result.success(), worker(server).doWork()); assertEquals(0, server.requestCount)
            assertTrue(alerts().isEmpty()); assertFalse(store.load()!!.has(PhoneFcmQueue.KEY))
        }
    }
    @Test fun sdkDisplayPayloadsAreDiscardedAndUnconfiguredAppDoesNotInitializeFirebase() {
        assertTrue(FirebaseApp.getApps(context).isEmpty())
        val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        for (key in listOf("firebase_messaging_auto_init_enabled", "firebase_analytics_collection_enabled", "firebase_messaging_notification_delegation_enabled")) {
            assertTrue(info.metaData.containsKey(key)); assertFalse(info.metaData.getBoolean(key))
        }
        val service = context.packageManager.getServiceInfo(ComponentName(context, PhoneFcmService::class.java), 0)
        assertFalse(service.exported); assertFalse(service.directBootAware)
        val data = raw()
        for (prefix in listOf("gcm.n.", "gcm.notification.")) {
            val input = Intent("com.google.android.c2dm.intent.RECEIVE").putExtra("google.message_id", "fixture-id")
                .putExtra(prefix + "e", "1").putExtra(prefix + "title", "Untrusted banner").putExtra("cmux", data)
            val sanitized = phoneFcmDataOnlyIntent(input)
            assertEquals(setOf("google.message_id"), sanitized.extras!!.keySet())
            assertEquals("fixture-id", sanitized.getStringExtra("google.message_id"))
            assertNull(RemoteMessage(sanitized.extras!!).notification)
            assertTrue(RemoteMessage(sanitized.extras!!).data.isEmpty())
        }
        val input = Intent("com.google.android.c2dm.intent.RECEIVE").putExtra("cmux", data)
        assertSame(input, phoneFcmDataOnlyIntent(input))
        assertEquals(mapOf("cmux" to data), RemoteMessage(input.extras!!).data)
        assertTrue(alerts().isEmpty())
    }
}
