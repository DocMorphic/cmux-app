package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class ComposerDeliveryTest {
    private val target = TerminalDrafts.Target("mac", "workspace", "terminal")

    @Test fun chunkedFileRetryUsesStableIdentityAndPreservesTextUntilPasteAcknowledgement() = runBlocking {
        val bytes = ByteArray(3 * 1024 * 1024 + 17) { (it % 251).toByte() }
        val file = ComposerAttachment(name = "report's data.txt", size = bytes.size)
        val image = ComposerAttachment(name = "screen.png", size = 3, imageFormat = "png")
        val drafts = TerminalDrafts()
        drafts.edit(target, "Read this\nThen explain")
        drafts.attach(target, image, drafts.generation)
        drafts.attach(target, file, drafts.generation)
        var rejectPaste = true
        Peer { request ->
            when (request.getString("method")) {
                "mobile.task.attachment.upload" -> JSONObject().put("path", "/tmp/report's data.txt")
                "terminal.paste" -> {
                    if (rejectPaste) { rejectPaste = false; error("Rejected paste") }
                    JSONObject()
                }
                else -> JSONObject()
            }
        }.use { peer ->
            val client = peer.connect()
            val send = drafts.begin(target)!!
            assertTrue(runCatching { deliverTerminalComposer(client, drafts, send, true, true,
                { if (it.imageFormat != null) byteArrayOf(1, 2, 3) else bytes }, {}, { true }) }.isFailure)
            drafts.finish(send, TerminalDrafts.DELIVERY_UNCONFIRMED)
            assertEquals(listOf(file), drafts.state.value[target]!!.attachments)
            assertEquals(send.text, drafts.state.value[target]!!.text)
            val restored = TerminalDrafts(drafts.saved())
            assertEquals(file, restored.state.value[target]!!.attachments.single())
            val retry = restored.begin(target)!!
            val delivered = deliverTerminalComposer(client, restored, retry, true, true, { bytes }, {}, { true })
            restored.finish(retry, deliveredFiles = delivered)
            assertFalse(restored.state.value.containsKey(target))
            assertEquals(1, peer.requests.count { it.getString("method") == "terminal.paste_image" })
            val uploads = peer.requests.filter { it.getString("method") == "mobile.task.attachment.upload" }
            assertEquals(4, uploads.size)
            uploads.chunked(2).forEach { attempt ->
                val first = attempt[0].getJSONObject("params")
                val last = attempt[1].getJSONObject("params")
                assertEquals(file.id, first.getString("operation_id"))
                assertEquals(file.id, last.getString("upload_id"))
                assertEquals(0, first.getInt("offset"))
                assertEquals(3 * 1024 * 1024, last.getInt("offset"))
                assertFalse(first.getBoolean("last")); assertTrue(last.getBoolean("last"))
                assertArrayEquals(bytes, Base64.getDecoder().decode(first.getString("data_b64")) +
                    Base64.getDecoder().decode(last.getString("data_b64")))
            }
            val paste = peer.requests.last().getJSONObject("params")
            assertEquals("'/tmp/report'\\''s data.txt' Read this\nThen explain", paste.getString("text"))
            assertEquals("return", paste.getString("submit_key"))
            assertEquals("workspace", paste.getString("workspace_id"))
            assertEquals("terminal", paste.getString("surface_id"))
        }
    }

    @Test fun connectionChangeDuringUploadStopsRemainingChunksAndMessage() = runBlocking {
        val file = ComposerAttachment(name = "large.bin", size = 3 * 1024 * 1024 + 1)
        val drafts = TerminalDrafts()
        drafts.attach(target, file, drafts.generation)
        drafts.edit(target, "Do not send on another Mac")
        var current = true
        Peer { current = false; JSONObject() }.use { peer ->
            val send = drafts.begin(target)!!
            assertTrue(runCatching {
                deliverTerminalComposer(peer.connect(), drafts, send, true, true, { ByteArray(file.size) }, {}, { current })
            }.isFailure)
            drafts.finish(send, TerminalDrafts.DELIVERY_UNCONFIRMED)
            assertEquals(1, peer.requests.size)
            assertEquals("mobile.task.attachment.upload", peer.requests.single().getString("method"))
            assertEquals(listOf(file), drafts.state.value[target]!!.attachments)
        }
    }

    @Test fun removedAttachmentIsSkippedAndNewAttachmentSurvivesOriginalSend() = runBlocking {
        val first = ComposerAttachment(name = "first.png", size = 1, imageFormat = "png")
        val removed = first.copy(id = java.util.UUID.randomUUID().toString(), name = "removed.png")
        val added = first.copy(id = java.util.UUID.randomUUID().toString(), name = "new.png")
        val drafts = TerminalDrafts()
        drafts.attach(target, first, drafts.generation)
        drafts.attach(target, removed, drafts.generation)
        Peer {
            drafts.removeAttachment(target, removed.id)
            drafts.attach(target, added, drafts.generation)
            JSONObject()
        }.use { peer ->
            val send = drafts.begin(target)!!
            val delivered = deliverTerminalComposer(peer.connect(), drafts, send, true, true, { byteArrayOf(42) }, {}, { true })
            drafts.finish(send, deliveredFiles = delivered)
            assertEquals(1, peer.requests.size)
            assertEquals(listOf(added), drafts.state.value[target]!!.attachments)
        }
    }

    @Test fun budgetsAndSessionChangeRejectWithoutEvictingExistingAttachments() {
        val drafts = TerminalDrafts()
        val generation = drafts.generation
        val file = ComposerAttachment(name = "full.bin", size = ComposerAttachment.FILE_LIMIT)
        drafts.attach(target, file, generation)
        assertTrue(runCatching { drafts.attach(target, file.copy(id = java.util.UUID.randomUUID().toString()), generation) }.isFailure)
        assertEquals(listOf(file), drafts.state.value[target]!!.attachments)
        drafts.clear()
        assertTrue(runCatching { drafts.attach(target, file, generation) }.isFailure)
        assertTrue(drafts.state.value.isEmpty())
    }

    private class Peer(val response: (JSONObject) -> JSONObject) : AutoCloseable {
        private val server = ServerSocket(0)
        private var socket: Socket? = null
        private var client: MobileRpcClient? = null
        val requests = CopyOnWriteArrayList<JSONObject>()
        private val thread = Thread {
            runCatching {
                server.accept().use { active ->
                    socket = active
                    val decoder = MobileFrameDecoder()
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = active.getInputStream().read(buffer)
                        if (count < 0) break
                        decoder.feed(buffer.copyOf(count)).forEach { frame ->
                            val request = JSONObject(String(frame, Charsets.UTF_8)); requests += request
                            val answer = JSONObject().put("id", request.getString("id"))
                            runCatching { response(request) }.fold(
                                onSuccess = { answer.put("ok", true).put("result", it) },
                                onFailure = { answer.put("ok", false).put("error", JSONObject().put("code", "rejected").put("message", it.message)) })
                            active.getOutputStream().write(MobileFrameCodec.encode(answer.toString().toByteArray()))
                            active.getOutputStream().flush()
                        }
                    }
                }
            }
        }.apply { start() }
        suspend fun connect(): MobileRpcClient = MobileRpcClient(PairingCode.Route("127.0.0.1", server.localPort), { "fixture" })
            .also { client = it; it.connect() }
        override fun close() { client?.close(); socket?.close(); server.close(); thread.join(1000) }
    }
}
