package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NativeForegroundNotificationAccessTest {
    private inner class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val methods = CopyOnWriteArrayList<String>()
        val published = CopyOnWriteArrayList<Pair<Long, List<NativeNotification>>>()
        var live = true
        var revision = 1L
        var read = false
        var present = true
        var connects = 0
        var staleReads = 0
        var reply: suspend (String) -> JSONObject = { method ->
            when (method) {
                "notification.feed.list" -> if (staleReads > 0) { staleReads--; listing(1, false) } else listing(revision, read)
                else -> { read = method != "notification.feed.mark_unread"; JSONObject().put("revision", ++revision) }
            }
        }
        fun listing(revision: Long, read: Boolean) = JSONObject().put("revision", revision).put("notifications",
            JSONArray().also { if (present) it.put(JSONObject().put("id", "note").put("workspace_id", "workspace").put("is_read", read)) })
        val wire = object : MobileRpcTransport {
            override suspend fun connect() { connects++ }
            override suspend fun read() = incoming.receiveCatching().getOrNull()
            override suspend fun write(bytes: ByteArray) {
                val request = JSONObject(MobileFrameDecoder().feed(bytes).single().toString(Charsets.UTF_8))
                val method = request.getString("method"); methods += method
                val response = JSONObject().put("id", request.getString("id"))
                try { response.put("ok", true).put("result", reply(method)) }
                catch (failure: MobileRpcException) { response.put("ok", false).put("error",
                    JSONObject().put("code", failure.code).put("message", "Fixture rejection")) }
                incoming.send(MobileFrameCodec.encode(response.toString().toByteArray()))
            }
            override fun close() { incoming.close() }
        }
        val client = MobileRpcClient(wire, { "fixture" })
        val access = NativeForegroundNotificationAccess(scope, client, { live }) { values, revision -> published += revision to values }
        suspend fun start() { client.connect() }
        override fun close() { client.close(); scope.cancel() }
    }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        Fixture().use { it.start(); withTimeout(5_000) { block(it) } }
    }
    @Test fun freshReadAndUnreadActionsUseOneAdmittedClientAndPublishConfirmedRevisions() = runBlocking<Unit> {
        fixture { f ->
            f.access.setRead("note", true); assertTrue(f.published.last().second.single().isRead)
            f.access.setRead("note", false); assertFalse(f.published.last().second.single().isRead)
            assertEquals(3L, f.published.last().first); assertEquals(1, f.connects)
            assertEquals(1, f.methods.count { it == "notification.feed.mark_read" })
            assertEquals(1, f.methods.count { it == "notification.feed.mark_unread" })
        }
    }
    @Test fun removedOrAlreadyReadNotificationsDoNotWriteAndMarkAllUsesTheCurrentCompleteFeed() = runBlocking<Unit> {
        fixture { f ->
            f.read = true; f.access.setRead("note", true); f.access.markAllRead()
            assertTrue(f.methods.all { it == "notification.feed.list" })
            f.present = false; assertTrue(runCatching { f.access.setRead("note", false) }.isFailure)
            f.present = true; f.read = false; f.access.markAllRead()
            assertEquals(1, f.methods.count { it == "notification.feed.mark_all_read" })
            assertTrue(f.published.last().second.single().isRead)
        }
    }
    @Test fun aStalePostAcknowledgementFeedIsRetriedWithoutRepeatingTheWriteOrPublishingOldUnreadState() = runBlocking<Unit> {
        fixture { f ->
            val normal = f.reply
            f.reply = { method -> normal(method).also { if (method == "notification.feed.mark_read") f.staleReads = 1 } }
            f.access.setRead("note", true)
            assertEquals(listOf(1L, 2L), f.published.map { it.first })
            assertTrue(f.published.last().second.single().isRead)
            assertEquals(1, f.methods.count { it == "notification.feed.mark_read" })
            assertEquals(3, f.methods.count { it == "notification.feed.list" })
        }
    }
    @Test fun aPermanentlyStaleFeedStopsAfterThreeReadsAndNeverReplaysAnAcknowledgedMutation() = runBlocking<Unit> {
        fixture { f ->
            val normal = f.reply
            f.reply = { method -> normal(method).also { if (method == "notification.feed.mark_read") f.staleReads = 100 } }
            assertTrue(runCatching { f.access.setRead("note", true) }.isFailure)
            assertEquals(1, f.methods.count { it == "notification.feed.mark_read" })
            assertEquals(4, f.methods.count { it == "notification.feed.list" })
            assertEquals(listOf(1L), f.published.map { it.first })
        }
    }
    @Test fun lostAuthorityRejectsAnInFlightReplyAndQueuedActionWithoutPublishingOrWriting() = runBlocking<Unit> {
        fixture { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.reply = { entered.complete(Unit); release.await(); f.listing(1, false) }
            val read = async { runCatching { f.access.refresh() } }
            entered.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.access.setRead("note", true) } }
            f.live = false; release.complete(Unit)
            assertTrue(read.await().isFailure); assertTrue(queued.await().isFailure)
            assertEquals(listOf("notification.feed.list"), f.methods.toList()); assertTrue(f.published.isEmpty())
        }
    }
    @Test fun rejectedReadMutationRetiresItsClientAndCannotBeRetriedOnThatOwner() = runBlocking<Unit> {
        fixture { f ->
            val normal = f.reply
            f.reply = { method -> if (method == "notification.feed.mark_read") throw MobileRpcException("unauthorized", "Fixture", true) else normal(method) }
            assertTrue(runCatching { f.access.setRead("note", true) }.isFailure); assertTrue(f.client.isClosed)
            assertTrue(runCatching { f.access.markAllRead() }.isFailure)
            assertEquals(1, f.methods.count { it == "notification.feed.mark_read" })
        }
    }
    @Test fun expiredUiActionDoesNotFetchWriteOrReplaceItsSnapshot() = runBlocking<Unit> {
        fixture { f ->
            assertTrue(runCatching { f.access.setRead("note", true) { false } }.isFailure)
            assertTrue(f.methods.isEmpty()); assertTrue(f.published.isEmpty()); assertFalse(f.client.isClosed)
        }
    }
}
