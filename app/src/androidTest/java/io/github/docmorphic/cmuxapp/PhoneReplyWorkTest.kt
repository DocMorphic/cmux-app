package io.github.docmorphic.cmuxapp

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Real encrypted storage and HTTP; only worker construction and the clock are injected. */
class PhoneReplyWorkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = NativeCredentialStore(context)
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val team = NativeTeamScope("reply-work-login", "fixture-user", "fixture-team", 1)
    private val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(PairingCodeParser.computer(
        IrohV2Computer("record", "a".repeat(64), "directory-mac", "stable", "Fixture Mac", emptyList()), team),
        "directory-mac", "Fixture Mac", "stable"), team)
    private val text = "Private worker fixture λ 中"

    @Before fun setup() {
        NativeNotificationService.setEnabled(context, false)
        store.clear()
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        store.update { state ->
            state.put("task_session", team.login).put("refresh_token", "fixture-refresh")
                .put("pairings", JSONArray().put(NativePairingRecords.encode(mac)))
            val keys = PhonePushKeyState(state); val identity = keys.identity(team.login)
            keys.pin(team, mac.origin, PhonePushPeer(PhonePushTuple(team.userId, null, context.packageName, identity.installationID,
                "physical-mac", "stable", "fixture.mac.build"), PhonePushIdentity.generate().descriptor()))
        }
    }
    @After fun cleanup() {
        store.clear(); PhoneReplyNotices(context).sync()
        WorkManager.getInstance(context).cancelAllWorkByTag("reply-work-fixture").result.get(5, TimeUnit.SECONDS)
    }
    private fun queue(id: String = "worker-reply", now: Long = System.currentTimeMillis()): PreparedPhoneReply {
        val keys = PhonePushKeyState(store.load()!!)
        val reply = PreparedPhoneReply.prepare(id, team, mac.origin, keys.peer(team, mac.origin)!!,
            keys.existingIdentity(team.login)!!, "workspace", "surface", false, text, now)
        store.update { assertEquals(ReplyEnqueueResult.QUEUED, PhoneReplyOutbox(it).enqueue(reply, now)) }
        return reply
    }
    private fun MockWebServer.profile(member: Boolean = true) {
        // Another selected team must not block replies for a saved Mac in a current membership.
        enqueue(MockResponse().setBody(JSONObject().put("id", team.userId)
            .put("selected_team", JSONObject().put("id", "other-team")).toString()))
        enqueue(MockResponse().setBody(JSONObject().put("items", JSONArray(
            (listOf("other-team") + if (member) listOf(team.teamId) else emptyList()).map {
                JSONObject().put("id", it).put("display_name", it)
            })).toString()))
    }
    private fun worker(server: MockWebServer, now: () -> Long = System::currentTimeMillis): PhoneReplyWorker =
        TestListenableWorkerBuilder<PhoneReplyWorker>(context).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters): ListenableWorker? {
                if (workerClassName != PhoneReplyWorker::class.java.name) return null
                return PhoneReplyWorker(appContext, params) {
                    NativePhoneReplyBackground(store, NativeAccountTeams({ "fixture-token" }, { store.taskSession() },
                        server.url("/api/v1/")), { "fixture-token" }, server.url("/"),
                        { PhoneReplyNotices(context).sync(now()) }, now)
                }
            }
        }).build()
    private fun alerts() = manager.activeNotifications.filter { it.notification.channelId == PhoneReplyNotices.CHANNEL &&
        it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
    private suspend fun waitFor(condition: () -> Boolean) {
        withTimeout(10_000) { while (!condition()) delay(25) }
    }
    private suspend fun runNoticeWork() {
        val job = OneTimeWorkRequestBuilder<PhoneReplyNoticeWorker>().addTag("reply-work-fixture").build()
        assertEquals(NetworkType.NOT_REQUIRED, job.workSpec.constraints.requiredNetworkType)
        assertTrue(job.workSpec.input.keyValueMap.isEmpty())
        val work = WorkManager.getInstance(context)
        work.enqueue(job).await()
        waitFor { work.getWorkInfoById(job.id).get(2, TimeUnit.SECONDS)?.state?.isFinished == true }
        assertEquals(WorkInfo.State.SUCCEEDED, checkNotNull(work.getWorkInfoById(job.id).get(2, TimeUnit.SECONDS)).state)
    }

    @Test fun recreatedWorkerPreservesCiphertextAndServerCooldownThenAccepts() = runBlocking<Unit> {
        var clock = System.currentTimeMillis()
        val reply = queue(now = clock)
        MockWebServer().use { server ->
            server.profile(); server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "10"))
            assertEquals(ListenableWorker.Result.retry(), worker(server) { clock }.doWork())
            server.takeRequest(); server.takeRequest()
            val first = server.takeRequest()
            assertEquals("/v1/replies/e2e", first.path)
            assertEquals("Bearer fixture-token", first.getHeader("Authorization"))
            assertEquals(reply.body, first.body.readUtf8())
            assertFalse(reply.body.contains(text))
            assertTrue(PhoneReplyOutbox(store.load()!!).pending(clock).isEmpty())
            assertEquals(reply.body, PhoneReplyOutbox(store.load()!!).waiting(clock).single().body)
            server.profile()
            assertEquals(ListenableWorker.Result.retry(), worker(server) { clock }.doWork())
            assertEquals(5, server.requestCount) // Account verification only during the persisted cooldown.
            server.takeRequest(); server.takeRequest()
            clock += 10_001
            server.profile(); server.enqueue(MockResponse().setResponseCode(202))
            assertEquals(ListenableWorker.Result.success(), worker(server) { clock }.doWork())
            server.takeRequest(); server.takeRequest()
            assertEquals(reply.body, server.takeRequest().body.readUtf8())
            val state = store.load()!!
            assertTrue(PhoneReplyOutbox(state).waiting(clock).isEmpty())
            assertEquals("accepted", PhoneReplyOutbox(state).receipts(clock).single().status)
            assertTrue(alerts().isEmpty())
        }
    }

    @Test fun revokedMembershipDoesNotSubmitAndExpiryReportsUnconfirmed() = runBlocking<Unit> {
        var clock = System.currentTimeMillis(); val reply = queue(now = clock)
        MockWebServer().use { server ->
            server.profile(member = false)
            assertEquals(ListenableWorker.Result.retry(), worker(server) { clock }.doWork())
            assertEquals(2, server.requestCount)
            clock += 130_000
            assertEquals(ListenableWorker.Result.success(), worker(server) { clock }.doWork())
            assertEquals(2, server.requestCount)
            waitFor { alerts().size == 1 }
            assertEquals(PhoneReplyNotices.key(reply), alerts().single().tag)
            assertEquals("unconfirmed", PhoneReplyOutbox(store.load()!!).receipts(clock).single().status)
        }
    }

    @Test fun accountReplacementDuringHttpCannotPublishAReceiptOrNotice() = runBlocking<Unit> {
        queue()
        MockWebServer().use { server ->
            server.profile()
            server.enqueue(MockResponse().setResponseCode(202).setHeadersDelay(300, TimeUnit.MILLISECONDS))
            val running = async(Dispatchers.IO) { worker(server).doWork() }
            repeat(3) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            store.update { it.put("task_session", "replacement-login") }
            assertEquals(ListenableWorker.Result.success(), withTimeout(5_000) { running.await() })
            assertFalse(store.load()!!.has(PhoneReplyOutbox.KEY))
            assertTrue(alerts().isEmpty())
        }
    }

    @Test fun realWorkManagerPostsPrivateNoticeWithoutNetworkAndLateAcceptanceCancelsIt() = runBlocking<Unit> {
        val reply = queue(now = System.currentTimeMillis() - 110_000)
        // Expire a real encrypted packet before executing the ordinary WorkManager job.
        store.update { PhoneReplyOutbox(it).prune(reply.createdAtMillis + 130_000) }
        runNoticeWork()
        waitFor { alerts().size == 1 }
        val notice = alerts().single().notification
        assertEquals("Reply delivery unconfirmed", notice.extras.getString(Notification.EXTRA_TITLE))
        assertFalse(notice.extras.toString().contains(text))
        assertFalse(notice.extras.toString().contains("workspace"))
        assertEquals(Notification.VISIBILITY_PRIVATE, notice.visibility)
        assertNull(notice.deleteIntent)
        if (Build.VERSION.SDK_INT >= 31) assertTrue(notice.contentIntent.isImmutable)
        store.update { PhoneReplyOutbox(it).finish(reply, PhoneReplyRelayResult.Accepted, System.currentTimeMillis()) }
        PhoneReplyNotices(context).sync()
        waitFor { alerts().isEmpty() }
        assertEquals("accepted", PhoneReplyOutbox(store.load()!!).receipts(System.currentTimeMillis()).single().status)
    }

    @Test fun dismissedNoticeStaysDismissedAndAccountReplacementCancelsOthers() = runBlocking<Unit> {
        val one = queue("notice-one"); val two = queue("notice-two")
        store.update {
            PhoneReplyOutbox(it).finish(one, PhoneReplyRelayResult.Rejected(409), System.currentTimeMillis())
            PhoneReplyOutbox(it).finish(two, PhoneReplyRelayResult.SignInRequired, System.currentTimeMillis())
        }
        PhoneReplyNotices(context).sync(); waitFor { alerts().size == 2 }
        manager.cancel(PhoneReplyNotices.key(one), 4)
        waitFor { alerts().size == 1 }
        PhoneReplyNotices(context).sync()
        assertEquals(PhoneReplyNotices.key(two), alerts().single().tag)
        store.update { it.put("task_session", "replacement-login") }
        PhoneReplyNotices(context).sync(); waitFor { alerts().isEmpty() }
        PhoneReplyWork.recover(context) // Empty-account recovery cannot enqueue a production HTTP send.
        assertTrue(alerts().isEmpty())
    }

    @Test fun cancelledWorkerLeavesItsUnconfirmedRequestAvailableForRecovery() = runBlocking<Unit> {
        val reply = queue()
        MockWebServer().use { server ->
            server.profile(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val running = async(Dispatchers.IO) { worker(server).doWork() }
            repeat(3) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            withTimeout(3_000) { running.cancelAndJoin() }
            assertEquals(reply.body, PhoneReplyOutbox(store.load()!!).waiting(System.currentTimeMillis()).single().body)
            assertTrue(PhoneReplyOutbox(store.load()!!).receipts(System.currentTimeMillis()).isEmpty())
            assertTrue(alerts().isEmpty())
        }
    }

    @Test fun noticeJobStillReportsAReplyThatExpiredAnHourAgo() = runBlocking<Unit> {
        val reply = queue(now = System.currentTimeMillis() - 3_600_000)
        assertTrue(PhoneReplyOutbox(store.load()!!).waiting(System.currentTimeMillis()).isEmpty())
        runNoticeWork()
        waitFor { alerts().size == 1 }
        assertEquals(PhoneReplyNotices.key(reply), alerts().single().tag)
        val receipts = PhoneReplyOutbox(store.load()!!).receipts(System.currentTimeMillis())
        assertEquals("unconfirmed", receipts.single().status)
    }
}
