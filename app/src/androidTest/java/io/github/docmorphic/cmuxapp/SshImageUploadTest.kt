package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.time.Instant
import java.util.UUID

/** Real SFTP against a disposable chroot. Never a user's SSH home or phone. */
class SshImageUploadTest {
    private lateinit var session: NativeSshSession
    private lateinit var lifetime: CoroutineScope
    private lateinit var root: File
    private lateinit var host: SshHostRecord
    private lateinit var connection: SshTransport
    private lateinit var files: SshFiles
    private val date = Instant.parse("2026-10-03T12:34:56.789Z")
    private val path = "/.cmux/uploads/20261003-123456-789.png"

    @Before fun setup() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("cmux_ssh_port") && args.getString("cmux_ssh_nonce") != "cmux")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.noBackupFilesDir, "ssh-image-test-${UUID.randomUUID()}")
        var metadata: String? = null
        val hosts = SshHostStore({ metadata }, { metadata = it })
        val vault = SshKeyVault(root, { true }, hosts::removeKeyReferences)
        val key = vault.generate("Image fixture")
        host = SshHostRecord(name = "Image fixture", keyId = key.id,
            endpoint = SshEndpoint("127.0.0.1", args.getString("cmux_ssh_port")!!.toInt(), args.getString("cmux_ssh_user")!!))
        hosts.upsert(host)
        assertTrue(hosts.confirmHostKey(hosts.dialPlan(host.id), host.id, hosts.trustSnapshot(host.endpoint),
            SshHostKey.parse(args.getString("cmux_ssh_hostkey")!!)))
        lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        session = NativeSshSession(hosts, vault, lifetime) { lifetime.isActive }
        connection = runBlocking { session.connections.open(host.id) }
        files = SshFiles { connection } // Pin the original transport: no reconnect/replay.
    }
    @After fun cleanup() {
        if (::session.isInitialized) {
            // This fixture has a disposable SFTP root. Tests own only .cmux here.
            runBlocking {
                val live = session.connections.autoConnect(host.id)
                if (live != null) live.withSftp { channel ->
                    runCatching {
                        val entries = channel.ls("/.cmux/uploads")
                        for (entry in entries) if (entry.filename !in listOf(".", "..")) channel.rm("/.cmux/uploads/${entry.filename}")
                        channel.rmdir("/.cmux/uploads")
                    }
                    runCatching { channel.rmdir("/.cmux") }
                    runCatching { channel.rm("/.cmux") }
                }
            }
            session.close(); lifetime.cancel()
            session.vault.state.value.toList().forEach { session.vault.delete(it.id) }
        }
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test fun imageBytesPublishPrivatelyWithoutReplacingSameMillisecondPaste() = runBlocking {
        val bytes = ByteArray(170_013) { (it % 239).toByte() }
        assertEquals(path, files.uploadImage(bytes, "PNG", date))
        assertEquals(path.removeSuffix(".png") + " 2.png", files.uploadImage(bytes, "../../bad", date))
        connection.withSftp { channel ->
            assertArrayEquals(bytes, channel.get(path).use { it.readBytes() })
            assertArrayEquals(bytes, channel.get(path.removeSuffix(".png") + " 2.png").use { it.readBytes() })
            assertEquals(384, channel.stat(path).permissions and 511)
            assertEquals(448, channel.stat("/.cmux").permissions and 511)
            assertEquals(448, channel.stat("/.cmux/uploads").permissions and 511)
            // Respect a user's existing directory permissions on subsequent pastes.
            channel.chmod(488, "/.cmux/uploads")
        }
        files.uploadImage(byteArrayOf(1), "jpg", date)
        connection.withSftp { assertEquals(488, it.stat("/.cmux/uploads").permissions and 511) }
        assertTrue(files.list("/.cmux/uploads").none { it.name.startsWith(".cmux-upload-") })
        assertEquals(0, connection.activeChannels)
    }

    @Test fun invalidPayloadBlockedDirectoryAndRetiredTransportNeverReturnAPath() = runBlocking {
        assertTrue(runCatching { files.uploadImage(byteArrayOf(), "png", date) }.isFailure)
        assertTrue(runCatching { files.uploadImage(ByteArray(ComposerAttachment.IMAGE_LIMIT + 1), "png", date) }.isFailure)
        assertTrue(files.list("/").none { it.name == ".cmux" })
        connection.withSftp { it.put(byteArrayOf(7).inputStream(), "/.cmux") }
        assertTrue(runCatching { files.uploadImage(byteArrayOf(1, 2), "png", date) }.isFailure)
        connection.withSftp { assertArrayEquals(byteArrayOf(7), it.get("/.cmux").use { stream -> stream.readBytes() }) }
        connection.close()
        assertTrue(runCatching { files.uploadImage(byteArrayOf(1, 2), "png", date) }.isFailure)
        assertFalse(connection.isConnected)
    }

    @Test fun lostPublicationReplyKeepsOneFileAndNeverReturnsItsPathOrReconnects() = runBlocking {
        assertEquals(0, connection.exec("files-publication-drop-reply").exitStatus)
        val bytes = byteArrayOf(3, 4, 5)
        val failure = runCatching { files.uploadImage(bytes, "png", date) }.exceptionOrNull()
        assertTrue(failure is SshUploadUnconfirmed)
        assertFalse(connection.isConnected)
        assertTrue(runCatching { files.uploadImage(bytes, "png", date) }.isFailure)
        val fresh = session.connections.autoConnect(host.id)!!
        val status = JSONObject(fresh.exec("files-publication-status").stdout.decodeToString())
        assertEquals(1, status.getInt("published")); assertEquals(1, status.getInt("droppedReplies"))
        fresh.withSftp { assertArrayEquals(bytes, it.get(path).use { stream -> stream.readBytes() }) }
        assertEquals(listOf("20261003-123456-789.png"), SshFiles { fresh }.list("/.cmux/uploads")
            .filterNot { it.name.startsWith(".cmux-upload-") }.map { it.name })
    }

    @Test fun cancellationWhileWritingDoesNotPublishOrCloseTheHealthyTransport() = runBlocking {
        assertEquals(0, connection.exec("files-transfer-arm").exitStatus)
        val pending = async(Dispatchers.IO) { files.uploadImage(ByteArray(512 * 1024) { 19 }, "png", date) }
        try {
            withTimeout(10_000) {
                while (JSONObject(connection.exec("files-transfer-status").stdout.decodeToString()).getInt("writes") == 0) delay(30)
            }
            withTimeout(3000) { pending.cancelAndJoin() }
            assertTrue(pending.isCancelled)
        } finally { connection.exec("files-transfer-release") }
        assertTrue(connection.isConnected)
        assertTrue(files.list("/.cmux/uploads").all { it.name.startsWith(".cmux-upload-") })
        assertEquals(0, connection.activeChannels)
        assertEquals(path, files.uploadImage(byteArrayOf(8), "png", date))
    }
}
