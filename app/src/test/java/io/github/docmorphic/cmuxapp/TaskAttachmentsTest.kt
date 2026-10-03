package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class TaskAttachmentsTest {
    private fun request(items: List<ComposerAttachment>, shell: Boolean = false) = TaskAttachments.snapshot(
        TaskCommand.parameters(if (shell) TaskCommand.Agent.SHELL else TaskCommand.Agent.CODEX,
            "Explain the report", "/repo", UUID.randomUUID()), items)

    @Test fun limitsMatchTaskContractWithoutRelaxingTerminalLimits() {
        val file = ComposerAttachment(name = "empty.txt", size = 0)
        val image = ComposerAttachment(name = "image.png", size = TaskAttachments.IMAGE_BYTES, imageFormat = "png")
        TaskAttachments.validate(listOf(file, image))
        assertNull(ComposerAttachment.read(file.json()))
        assertNull(ComposerAttachment.read(image.json()))
        for (bad in listOf(listOf(image.copy(size = image.size + 1)), listOf(file.copy(size = -1)),
            listOf(file.copy(size = ComposerAttachment.FILE_LIMIT + 1)), listOf(image.copy(size = 0)),
            listOf(file, file), List(11) { file.copy(id = UUID.randomUUID().toString()) },
            List(3) { file.copy(id = UUID.randomUUID().toString(), size = ComposerAttachment.FILE_LIMIT) })) {
            assertThrows(IllegalArgumentException::class.java) { TaskAttachments.validate(bad) }
        }
    }

    @Test fun attachmentOnlyDraftSurvivesSerializationAndMacSelection() {
        val item = ComposerAttachment(name = "empty.txt", size = 0)
        val drafts = TaskDrafts()
        val editor = drafts.begin(UUID.randomUUID().toString(), "mac", "Mac", "/repo")
        drafts.edit(editor) { it.copy(attachments = listOf(item)) }
        drafts.end(editor)
        val restored = TaskDrafts(drafts.saved()).state.value.getValue(editor.id)
        assertFalse(restored.isEmpty)
        assertEquals(listOf(item), restored.attachments)
        assertEquals(listOf(item), restored.onMac("other", "Other", "/elsewhere").attachments)
        val oldFormat = restored.json().apply { remove("attachments") }
        assertTrue(TaskDraft.read(oldFormat).attachments.isEmpty())
    }

    @Test fun durableFirstAttachmentKeepsOldEmptyEditorInsideDraftLimit() {
        val drafts = TaskDrafts(now = { 1L })
        val old = drafts.begin(UUID.randomUUID().toString(), "mac", "Mac", "/repo")
        repeat(TaskDrafts.LIMIT) {
            val other = drafts.begin(UUID.randomUUID().toString(), "mac", "Mac", "/repo")
            drafts.edit(other) { it.copy(prompt = "Existing task") }; drafts.end(other)
        }
        val item = ComposerAttachment(name = "new.txt", size = 1)
        val proposed = drafts.state.value.getValue(old.id).copy(attachments = listOf(item))
        val durable = TaskDrafts(drafts.saved(proposed))
        assertEquals(TaskDrafts.LIMIT, durable.state.value.size)
        assertEquals(listOf(item), durable.state.value.getValue(old.id).attachments)
        assertTrue(drafts.state.value.getValue(old.id).attachments.isEmpty())
    }

    @Test fun orderedIdentityChangesSubmissionButRenamingDoesNot() {
        val first = ComposerAttachment(name = "first.txt", size = 4)
        val second = ComposerAttachment(name = "second.txt", size = 5)
        val identity = TaskSubmissionIdentity()
        val initial = identity.resolve("mac", request(listOf(first, second)))
        identity.submitted("mac", initial)
        val id = initial.getString("operation_id")
        assertEquals(id, identity.resolve("mac", request(listOf(first.copy(name = "renamed"), second))).getString("operation_id"))
        for (items in listOf(listOf(second, first), listOf(first), listOf(first.copy(size = 3), second),
            listOf(first.copy(id = UUID.randomUUID().toString()), second))) {
            assertNotEquals(id, identity.resolve("mac", request(items)).getString("operation_id"))
        }
        assertEquals(id, identity.resolve("mac", request(listOf(first, second))).getString("operation_id"))
        assertThrows(IllegalStateException::class.java) { TaskAttachments.requested(initial, listOf(first)) }
    }

    @Test fun wireUsesOfficialEnvironmentAndLeavesOriginalSnapshotUntouched() {
        val source = request(listOf(ComposerAttachment(name = "a", size = 1)))
        val before = source.toString()
        val paths = listOf("/tmp/a's file.txt", "/tmp/image.png")
        val wire = TaskAttachments.wire(source, paths)
        assertEquals(before, source.toString())
        assertFalse(wire.has("_cmux_task_attachments"))
        assertEquals(source.getString("initial_command"), wire.getString("initial_command"))
        assertEquals(source.getString("title"), wire.getString("title"))
        assertEquals(paths.joinToString("\n"), wire.getJSONObject("initial_env").getString("CMUX_TASK_ATTACHMENTS"))
        assertEquals("Explain the report\n\nAttached files (absolute paths on this machine):\n- /tmp/a's file.txt\n- /tmp/image.png",
            wire.getJSONObject("initial_env").getString("CMUX_TASK_PROMPT"))
        assertThrows(IllegalArgumentException::class.java) { TaskAttachments.wire(source, listOf("relative")) }
        assertThrows(IllegalArgumentException::class.java) { TaskAttachments.wire(source, listOf("/bad\u0000path")) }
    }

    @Test fun chunkedAndEmptyUploadsShareTaskOperationAndRetryIdentities() = runBlocking {
        val bytes = ByteArray(3 * 1024 * 1024 + 19) { (it % 251).toByte() }
        val file = ComposerAttachment(name = "report.txt", size = bytes.size)
        val empty = ComposerAttachment(name = "empty.txt", size = 0)
        val items = listOf(file, empty)
        val snapshot = request(items)
        Peer { message -> JSONObject().put("path", "/tmp/" + message.getJSONObject("params").getString("file_name")) }.use { peer ->
            val client = peer.connect()
            repeat(2) {
                val wire = TaskAttachments.prepareRequest(client, snapshot, items, true, false,
                    { if (it.id == file.id) bytes else byteArrayOf() }, {})
                assertEquals(snapshot.getString("operation_id"), wire.getString("operation_id"))
                assertEquals("/tmp/report.txt\n/tmp/empty.txt", wire.getJSONObject("initial_env").getString("CMUX_TASK_ATTACHMENTS"))
            }
            assertEquals(6, peer.requests.size)
            peer.requests.chunked(3).forEach { attempt ->
                val params = attempt.map { it.getJSONObject("params") }
                params.forEach { assertEquals(snapshot.getString("operation_id"), it.getString("operation_id")) }
                assertEquals(listOf(file.id, file.id, empty.id), params.map { it.getString("upload_id") })
                assertEquals(listOf(0, 3 * 1024 * 1024, 0), params.map { it.getInt("offset") })
                assertEquals(listOf(false, true, true), params.map { it.getBoolean("last") })
                assertArrayEquals(bytes, Base64.getDecoder().decode(params[0].getString("data_b64")) + Base64.getDecoder().decode(params[1].getString("data_b64")))
                assertEquals(0, params[2].getInt("total_bytes"))
                assertEquals("", params[2].getString("data_b64"))
            }
        }
    }

    @Test fun reconciliationAndShellSkipFilesButKeepOperationIdentity() = runBlocking {
        val file = ComposerAttachment(name = "missing.txt", size = 3)
        val disconnected = MobileRpcClient(PairingCode.Route("127.0.0.1", 1), { "unused" })
        for ((snapshot, reconcile) in listOf(request(listOf(file)) to true, request(listOf(file), shell = true) to false)) {
            val wire = TaskAttachments.prepareRequest(disconnected, snapshot, emptyList(), false, reconcile,
                { error("Must not read files") }, {})
            assertEquals(snapshot.getString("operation_id"), wire.getString("operation_id"))
            assertFalse(wire.has("_cmux_task_attachments"))
            assertFalse(wire.optJSONObject("initial_env")?.has("CMUX_TASK_ATTACHMENTS") == true)
        }
    }

    @Test fun unsupportedHostAndChangedSessionStopBeforeFurtherUpload() = runBlocking {
        val file = ComposerAttachment(name = "large.bin", size = 3 * 1024 * 1024 + 1)
        var current = true
        Peer { current = false; JSONObject() }.use { peer ->
            val client = peer.connect()
            assertTrue(runCatching { TaskAttachments.prepareRequest(client, request(listOf(file)), listOf(file), false, false,
                { error("Must not read unsupported attachments") }, {}) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(peer.requests.isEmpty())
            assertTrue(runCatching { TaskAttachments.prepareRequest(client, request(listOf(file)), listOf(file), true, false,
                { ByteArray(file.size) }, { check(current) { "Mac changed" } }) }.isFailure)
            assertEquals(1, peer.requests.size)
        }
    }

    @Test fun failedUploadCanRetryWithOriginalIdentityAndNewTaskGetsNewOperation() = runBlocking {
        val file = ComposerAttachment(name = "report.txt", size = 1)
        val snapshot = request(listOf(file))
        var reject = true
        Peer { if (reject) { reject = false; error("Upload rejected") }; JSONObject().put("path", "/tmp/report.txt") }.use { peer ->
            val client = peer.connect()
            assertTrue(runCatching { TaskAttachments.prepareRequest(client, snapshot, listOf(file), true, false, { byteArrayOf(1) }, {}) }.isFailure)
            TaskAttachments.prepareRequest(client, snapshot, listOf(file), true, false, { byteArrayOf(1) }, {})
            val newTask = TaskSubmissionIdentity().retire("mac", snapshot)
            TaskAttachments.prepareRequest(client, newTask, listOf(file), true, false, { byteArrayOf(1) }, {})
            val params = peer.requests.map { it.getJSONObject("params") }
            assertEquals(params[0].getString("operation_id"), params[1].getString("operation_id"))
            assertNotEquals(params[1].getString("operation_id"), params[2].getString("operation_id"))
            assertEquals(listOf(file.id, file.id, file.id), params.map { it.getString("upload_id") })
        }
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
