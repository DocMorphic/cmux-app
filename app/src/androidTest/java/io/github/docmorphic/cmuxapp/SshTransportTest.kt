package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in, generated loopback server only. Runner refuses physical devices. */
class SshTransportTest {
    private lateinit var root: File
    private lateinit var hosts: SshHostStore
    private lateinit var vault: SshKeyVault
    private lateinit var host: SshHostRecord
    private lateinit var owner: CoroutineScope
    private lateinit var expectedKey: SshHostKey
    private lateinit var nonce: String
    private val connections = mutableListOf<SshTransport>()
    private val questions = AtomicInteger()

    @Before fun setup() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires scripts/check-ssh-transport.py and its generated server", args.containsKey("cmux_ssh_port"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.noBackupFilesDir, "ssh-transport-test-${UUID.randomUUID()}")
        var metadata: String? = null
        hosts = SshHostStore({ metadata }, { metadata = it })
        vault = SshKeyVault(root, { true }, hosts::removeKeyReferences)
        val key = vault.generate("Transport fixture")
        host = SshHostRecord(name = "Generated fixture", keyId = key.id,
            endpoint = SshEndpoint("127.0.0.1", args.getString("cmux_ssh_port")!!.toInt(), args.getString("cmux_ssh_user")!!))
        hosts.upsert(host)
        owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        expectedKey = SshHostKey.parse(args.getString("cmux_ssh_hostkey")!!)
        nonce = args.getString("cmux_ssh_nonce")!!
    }
    @After fun cleanup() {
        connections.forEach(SshTransport::close)
        if (::owner.isInitialized) owner.cancel()
        if (::vault.isInitialized) vault.state.value.toList().forEach { vault.delete(it.id) }
        if (::root.isInitialized) root.deleteRecursively()
    }
    private suspend fun connect(id: UUID = host.id, explicit: Boolean = true,
        ask: suspend (SshTrustQuestion) -> Boolean = {
            questions.incrementAndGet(); assertEquals(expectedKey, it.presented); true
        }): SshTransport = SshTransport.connect(hosts, vault, id, owner, { owner.isActive }, explicit, ask,
            { _, _ -> error("Fixture keys do not require biometrics") }).also { connections += it }
    private suspend fun eventually(predicate: () -> Boolean) = withTimeout(3000) {
        while (!predicate()) delay(10)
    }
    private suspend fun readUntil(input: InputStream, expected: String) = withContext(Dispatchers.IO) {
        val output = ByteArrayOutputStream()
        withTimeout(3000) {
            while (true) {
                while (input.available() > 0) output.write(input.read())
                if (output.toString("UTF-8").contains(expected)) break
                delay(10)
            }
        }
    }
    private suspend fun probe(connection: SshTransport) {
        val result = connection.exec("probe")
        assertEquals(nonce + "\n", result.stdout.toString(Charsets.UTF_8))
        assertTrue(result.stderr.isEmpty()); assertEquals(0, result.exitStatus)
    }

    @Test fun generatedKeyTrustExecPtyResizeAndSftpUseProductionTransport() = runBlocking {
        val connection = connect()
        assertEquals(1, questions.get())
        assertEquals(expectedKey, hosts.trustSnapshot(host.endpoint).pinned)
        assertEquals(host.id, hosts.state.value.lastUsedHostId)
        probe(connection)
        connection.openPty(80, 24).use { terminal ->
            readUntil(terminal.output, "SIZE 80 24")
            terminal.resize(103, 41); readUntil(terminal.output, "SIZE 103 41")
            terminal.write("production λ input\n".toByteArray())
            readUntil(terminal.output, "ECHO production λ input")
        }
        val directory = "/production-${UUID.randomUUID()}"
        connection.withSftp { sftp ->
            sftp.mkdir(directory)
            val content = ByteArray(70000) { (it % 239).toByte() }
            sftp.put(content.inputStream(), "$directory/payload")
            assertArrayEquals(content, sftp.get("$directory/payload").use { it.readBytes() })
            sftp.rm("$directory/payload"); sftp.rmdir(directory)
        }
        connection.close()
        probe(connect())
        assertEquals("Existing pin should skip a new trust question", 1, questions.get())
    }

    @Test fun importedEd25519VaultKeyAuthenticatesAgainstIndependentServer() = runBlocking {
        SshKeyCrypto.encodeSecret(byteArrayOf(1), null)
        val key = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
        val bytes = ByteArrayOutputStream()
        try { key.writeOpenSSHv1PrivateKey(bytes, null) } finally { key.dispose() }
        val privateBytes = bytes.toByteArray()
        val record = try { vault.import("Imported transport fixture", privateBytes, null) } finally { privateBytes.fill(0) }
        hosts.upsert(host.copy(keyId = record.id))
        probe(connect())
    }

    @Test fun twoJumpHopsUseChannelsAndKeepRealEndpointPins() = runBlocking {
        val middle = host.copy(id = UUID.randomUUID(), name = "Middle", jumpHostId = host.id)
        val target = host.copy(id = UUID.randomUUID(), name = "Target", jumpHostId = middle.id)
        hosts.upsert(middle); hosts.upsert(target)
        val connection = connect(target.id)
        probe(connection)
        assertEquals(listOf(host.id, middle.id, target.id), connection.plan.hops.map { it.hostId })
        assertEquals(setOf(host.endpoint.hostKeyIdentity), hosts.state.value.pinnedKeys.keys)
        assertEquals(1, questions.get()) // This fixture uses the same endpoint at all hops.
        assertEquals(2, connection.activeChannels)
        connection.close()
        assertFalse(connection.isConnected); assertEquals(0, connection.activeChannels)
    }

    @Test fun declinedChangedIdentityPausesAutomaticDialUntilExplicitRetry() = runBlocking {
        val previous = vault.state.value.single().publicKey
        assertTrue(hosts.confirmHostKey(hosts.dialPlan(host.id), host.id, hosts.trustSnapshot(host.endpoint), previous))
        var asked = false
        val rejected = runCatching { connect(ask = {
            asked = true
            assertEquals(SshHostTrustVerdict.CHANGED, it.prior.verdict(it.presented))
            false
        }) }
        assertTrue(rejected.isFailure); assertTrue(asked)
        assertEquals(previous, hosts.trustSnapshot(host.endpoint).pinned)
        assertTrue(hosts.state.value.host(host.id)!!.autoConnectPaused)
        assertTrue(runCatching { connect(explicit = false) }.isFailure)
        probe(connect())
        assertFalse(hosts.state.value.host(host.id)!!.autoConnectPaused)
    }

    @Test fun cancellationWhileTrustPromptWaitsDoesNotPinOrPause() = runBlocking {
        val prompt = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Boolean>()
        val pending = async { connect(ask = { prompt.complete(Unit); answer.await() }) }
        withTimeout(10000) { prompt.await() }
        withTimeout(2000) { pending.cancelAndJoin() }
        answer.complete(true)
        assertTrue(hosts.state.value.pinnedKeys.isEmpty())
        assertFalse(hosts.state.value.host(host.id)!!.autoConnectPaused)
        probe(connect())
    }

    @Test fun editedRouteRejectsLateTrustAnswer() = runBlocking {
        val prompt = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Boolean>()
        val pending = async { runCatching { connect(ask = { prompt.complete(Unit); answer.await() }) } }
        withTimeout(10000) { prompt.await() }
        hosts.upsert(host.copy(endpoint = host.endpoint.copy(username = "changed-fixture-user")))
        answer.complete(true)
        assertTrue(withTimeout(3000) { pending.await() }.isFailure)
        assertTrue(hosts.state.value.pinnedKeys.isEmpty())
    }

    @Test fun ownerCancellationRetiresEstablishedTerminal() = runBlocking {
        val connection = connect()
        val terminal = connection.openPty(80, 24)
        readUntil(terminal.output, "SIZE 80 24")
        owner.cancel()
        eventually { !connection.isConnected && connection.activeChannels == 0 }
        assertTrue(runCatching { terminal.write("must not send".toByteArray()) }.isFailure)
        assertTrue(runCatching { connection.exec("probe") }.isFailure)
        terminal.close()
    }

    @Test fun labelsPreserveSessionButKeyDeletionAndPinReplacementRetireIt() = runBlocking {
        val connection = connect()
        hosts.upsert(host.copy(name = "Renamed", idleClose = SshIdleClosePolicy.NEVER))
        probe(connection)
        val wrongPin = vault.state.value.single().publicKey
        assertTrue(hosts.confirmHostKey(connection.plan, host.id, hosts.trustSnapshot(host.endpoint), wrongPin))
        eventually { !connection.isConnected }
        val replacement = connect()
        vault.delete(host.keyId!!)
        eventually { !replacement.isConnected }
        assertNull(hosts.state.value.host(host.id)!!.keyId)
    }

    @Test fun execTimeoutClosesItsChannelAndLeavesSessionUsable() = runBlocking {
        val connection = connect()
        val result = runCatching { connection.exec("stall", timeoutMillis = 200) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("timed out"))
        assertEquals(0, connection.activeChannels)
        probe(connection)
    }

    @Test fun silentJumpTargetHasAHandshakeDeadlineAndReleasesRoute() = runBlocking {
        val silentPort = InstrumentationRegistry.getArguments().getString("cmux_ssh_silentport")!!.toInt()
        val silent = host.copy(id = UUID.randomUUID(), jumpHostId = host.id,
            endpoint = host.endpoint.copy(port = silentPort))
        hosts.upsert(silent)
        val result = withTimeout(15000) { runCatching { connect(silent.id) } }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull().toString().contains("timed out"))
        assertNull(hosts.trustSnapshot(silent.endpoint).pinned)
        probe(connect())
    }

    @Test fun targetDisconnectRetiresTheWholeJumpRoute() = runBlocking {
        val target = host.copy(id = UUID.randomUUID(), jumpHostId = host.id)
        hosts.upsert(target)
        val connection = connect(target.id)
        assertEquals(1, connection.activeChannels)
        runCatching { connection.exec("drop") }
        eventually { !connection.isConnected && connection.activeChannels == 0 }
        probe(connect())
    }
    private fun coordinator() = SshConnections(hosts, owner, { owner.isActive }) { id, lifetime, allowed, ask ->
        SshTransport.connect(hosts, vault, id, lifetime, allowed, false, ask,
            { _, _ -> error("Fixture keys do not require biometrics") })
    }

    @Test fun coordinatorSharesLiveDialAndViewCancellationDoesNotCancelTrust() = runBlocking {
        coordinator().use { manager ->
            val first = async { manager.open(host.id) }
            val second = async { manager.autoConnect(host.id) }
            eventually { manager.prompts.value.size == 1 }
            first.cancelAndJoin()
            val prompt = manager.prompts.value.single()
            assertTrue(manager.answer(prompt.id, true))
            val connection = withTimeout(10000) { second.await() }!!
            probe(connection)
            assertSame(connection, manager.open(host.id))
            manager.disconnect(host.id)
            eventually { !connection.isConnected }
            assertNull(manager.autoConnect(host.id))
            assertFalse(manager.answer(prompt.id, false))
        }
    }

    @Test fun coordinatorCoalescesTheSameJumpIdentityAcrossActualConnections() = runBlocking {
        val child = host.copy(id = UUID.randomUUID(), jumpHostId = host.id)
        hosts.upsert(child)
        coordinator().use { manager ->
            val first = async { manager.open(host.id) }
            val second = async { manager.open(child.id) }
            eventually { manager.prompts.value.size == 1 }
            val prompt = manager.prompts.value.single()
            assertTrue(manager.answer(prompt.id, true))
            probe(withTimeout(10000) { first.await() })
            probe(withTimeout(10000) { second.await() })
            assertTrue(manager.prompts.value.isEmpty())
        }
    }

}
