package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

class MobileRpcClientTest {
    @Test fun sendsAuthenticatedFramedRequestAndMatchesResponseById() = runBlocking {
        ServerSocket(0).use { server ->
            val observed = AtomicReference<JSONObject>()
            val peer = Thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val header = input.readNBytes(4)
                    val length = header.fold(0) { n, byte -> (n shl 8) or (byte.toInt() and 0xff) }
                    val request = JSONObject(String(input.readNBytes(length), Charsets.UTF_8))
                    observed.set(request)
                    val response = JSONObject()
                        .put("id", request.getString("id"))
                        .put("ok", true)
                        .put("result", JSONObject().put("workspaces", org.json.JSONArray()))
                    socket.getOutputStream().write(MobileFrameCodec.encode(response.toString().toByteArray()))
                }
            }
            peer.start()
            val client = MobileRpcClient(PairingCode.Route("127.0.0.1", server.localPort), { "test-token" })
            try {
                client.connect()
                assertEquals(0, client.workspaces().getJSONArray("workspaces").length())
                peer.join(3_000)
                assertEquals("mobile.workspace.list", observed.get().getString("method"))
                assertEquals("test-token", observed.get().getJSONObject("auth").getString("stack_access_token"))
            } finally { client.close() }
        }
    }

    @Test fun rejectsAuthorizedRequestWithoutSameAccountToken() = runBlocking {
        ServerSocket(0).use { server ->
            val client = MobileRpcClient(PairingCode.Route("127.0.0.1", server.localPort), { null })
            try {
                client.connect()
                assertTrue(runCatching { client.workspaces() }.isFailure)
            } finally { client.close() }
        }
    }
}
