package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SshCmuxRemoteTest {
    @Test fun namedAndHashedSocketsUseRuntimePrecedenceAndServerSessionRules() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SshCmuxDiscovery.digest("abc"))
        val longName = "a-very-long-cmux-tui-session-name-".repeat(4)
        val digest = SshCmuxDiscovery.digest(longName)
        val sockets = SshCmuxDiscovery.parseSockets("""
            /run/user/501/cmux-tui-501/main.sock
            /run/user/501/cmux-tui-hashed-501/$digest.sock
            /tmp/cmux-tui-hashed-501/$digest.sock
            /tmp/cmux-tui-hashed-501/not-a-digest.sock
            /tmp/cmux-tui-hashed-501/${digest.uppercase()}.sock
            /tmp/cmux-tui-501/my work λ 中.sock
            /tmp/cmux-tui-501/$digest.sock
        """.trimIndent())
        assertEquals(4, sockets.size)
        assertTrue(sockets[0].serves("main")); assertFalse(sockets[0].serves("other"))
        assertNull(sockets[1].name); assertTrue(sockets[1].serves(longName)); assertFalse(sockets[1].serves("main"))
        assertEquals("/run/user/501/cmux-tui-hashed-501/$digest.sock", sockets[1].path)
        assertEquals("my work λ 中", sockets[2].name); assertEquals(digest, sockets[3].name)
        for (name in listOf("", ".", "..", "a/b", "a\\b", "a\u001bb", "a\u2028b", "a\u0085b")) assertFalse(SshCmuxDiscovery.validSession(name))
        for (name in listOf("main", "my work", "日本語", ":colon", longName, "'\$(false)")) assertTrue(SshCmuxDiscovery.validSession(name))
    }
    @Test fun unrelatedAndUnsafePathsAreIgnoredAndLargeListingsFailVisibly() {
        val paths = listOf("relative/cmux-tui-501/a.sock", "/tmp/other/a.sock", "/tmp/cmux-tui-user/a.sock",
            "/tmp/../cmux-tui-501/a.sock", "/tmp/cmux-tui-501/a\r.sock", "/tmp/cmux-tui-501/..sock")
        assertTrue(SshCmuxDiscovery.parseSockets(paths.joinToString("\n")).isEmpty())
        val many = (0..128).joinToString("\n") { "/tmp/cmux-tui-501/session$it.sock" }
        assertThrows(Exception::class.java) { SshCmuxDiscovery.parseSockets(many) }
        assertThrows(Exception::class.java) { SshCmuxDiscovery.parseSockets("a".repeat(1024 * 1024 + 1)) }
    }
    @Test fun discoveryDistinguishesAbsentBinaryFromExecutionFailureAndPreservesPathSpaces() = runTest {
        val commands = mutableListOf<String>()
        var result = SshExecResult("/home/user/ cmux '\$(false) \n".toByteArray(), byteArrayOf(), 0)
        val remote = SshCmuxRemote({ commands += it; result }, { error("Discovery must not open a relay") }, backgroundScope)
        assertEquals("/home/user/ cmux '\$(false) ", remote.locateBinary())
        result = SshExecResult(byteArrayOf(), byteArrayOf(), 1); assertNull(remote.locateBinary())
        result = result.copy(exitStatus = 2); assertTrue(runCatching { remote.locateBinary() }.isFailure)
        result = SshExecResult("/tmp/cmux-tui-501/main.sock\n".toByteArray(), byteArrayOf(), 0)
        assertEquals("main", remote.listSockets().single().name)
        assertTrue(commands.none { "server ensure" in it || "server stop" in it })
    }
    private class Pipe(private val session: String?, private val reply: Boolean = true) : SshExecPipe {
        private val input = Channel<ByteArray>(Channel.UNLIMITED)
        override val output = input.receiveAsFlow()
        var closed = false
        override suspend fun write(bytes: ByteArray) {
            if (!reply) return
            val request = JSONObject(bytes.toString(Charsets.UTF_8))
            val data = if (request.getString("cmd") == "identify") JSONObject().put("app", "cmux-tui")
                .put("version", "fixture").put("protocol", 12).put("session", session).put("pid", 1).put("generation", "boot-a")
                .put("capabilities", JSONArray(listOf("workspace-registry-v1", "attach-initial-size"))) else JSONObject()
            input.send((JSONObject().put("id", request.getString("id")).put("ok", true).put("data", data).toString() + "\n").toByteArray())
        }
        override fun close() { closed = true; input.close() }
    }
    @Test fun hashedRelayIdentityMustMatchAndRejectedConnectionIsClosed() = runTest {
        val path = "/tmp/cmux-tui-hashed-501/${SshCmuxDiscovery.digest("expected")}.sock"
        val socket = SshCmuxDiscovery.parseSockets(path).single()
        val wrong = Pipe("replacement")
        val bad = SshCmuxRemote({ error("No discovery") }, { wrong }, backgroundScope)
        assertTrue(runCatching { bad.connect("/bin/cmux-tui", socket) }.isFailure); assertTrue(wrong.closed)
        val valid = Pipe("expected")
        val good = SshCmuxRemote({ error("No discovery") }, { command ->
            assertEquals(SshCmuxDiscovery.relayCommand("/bin/cmux-tui", socket), command); valid
        }, backgroundScope)
        val client = good.connect("/bin/cmux-tui", socket)
        assertEquals("expected", client.server?.session); assertFalse(valid.closed); client.close(); assertTrue(valid.closed)
    }
    @Test fun timeoutAndCallerCancellationCloseOpenedRelay() = runTest {
        val socket = SshCmuxDiscovery.parseSockets("/tmp/cmux-tui-501/main.sock").single()
        val pipe = Pipe("main", reply = false)
        val remote = SshCmuxRemote({ error("No discovery") }, { pipe }, backgroundScope)
        val waiting = async { runCatching { remote.connect("/bin/cmux-tui", socket) } }; runCurrent()
        advanceTimeBy(10000); runCurrent()
        assertTrue(waiting.await().isFailure); assertTrue(pipe.closed)
        val second = Pipe("main", reply = false)
        val other = SshCmuxRemote({ error("No discovery") }, { second }, backgroundScope)
        val canceled = async { other.connect("/bin/cmux-tui", socket) }; runCurrent(); canceled.cancelAndJoin()
        assertTrue(second.closed)
    }
}
