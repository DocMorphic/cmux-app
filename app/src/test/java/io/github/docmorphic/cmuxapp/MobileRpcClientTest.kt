package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MobileRpcClientTest {
    @Test fun structuredRpcErrorRetainsCodeAndDoesNotCloseTheConnection() = runBlocking {
        ServerSocket(0).use { server ->
            val finish = CountDownLatch(1)
            val peer = Thread {
                server.accept().use { socket ->
                    repeat(2) { index ->
                        val input = socket.getInputStream()
                        val size = input.readNBytes(4).fold(0) { n, byte -> (n shl 8) or (byte.toInt() and 0xff) }
                        val request = JSONObject(String(input.readNBytes(size), Charsets.UTF_8))
                        val response = JSONObject().put("id", request.getString("id")).put("ok", index == 1)
                        if (index == 0) response.put("error", JSONObject().put("code", "unsupported_method")
                            .put("message", "Model discovery unavailable"))
                        else response.put("result", JSONObject().put("workspaces", org.json.JSONArray()))
                        socket.getOutputStream().write(MobileFrameCodec.encode(response.toString().toByteArray()))
                        socket.getOutputStream().flush()
                    }
                    finish.await(5, TimeUnit.SECONDS)
                }
            }.apply { start() }
            val client = MobileRpcClient(PairingCode.Route("127.0.0.1", server.localPort), { "test-token" })
            try {
                client.connect()
                val failure = runCatching { client.request("mobile.task.models.list") }.exceptionOrNull()
                assertTrue(failure is MobileRpcException)
                assertEquals("unsupported_method", (failure as MobileRpcException).code)
                assertEquals("Model discovery unavailable", failure.message)
                assertEquals(0, client.workspaces().getJSONArray("workspaces").length())
            } finally { finish.countDown(); client.close(); peer.join(3_000) }
        }
    }

    @Test fun groupAndMoveMutationsUseOfficialMethodAndScope() = runBlocking {
        ServerSocket(0).use { server ->
            val observed = mutableListOf<JSONObject>()
            val finishPeer = CountDownLatch(1)
            val peer = Thread {
                server.accept().use { socket ->
                    repeat(3) {
                        val input = socket.getInputStream()
                        val header = input.readNBytes(4)
                        val length = header.fold(0) { n, byte -> (n shl 8) or (byte.toInt() and 0xff) }
                        val request = JSONObject(String(input.readNBytes(length), Charsets.UTF_8))
                        observed += request
                        val response = JSONObject().put("id", request.getString("id"))
                            .put("ok", true).put("result", JSONObject())
                        socket.getOutputStream().write(MobileFrameCodec.encode(response.toString().toByteArray()))
                        socket.getOutputStream().flush()
                    }
                    finishPeer.await(5, TimeUnit.SECONDS)
                }
            }
            peer.start()
            val client = MobileRpcClient(PairingCode.Route("127.0.0.1", server.localPort), { "token" })
            try {
                client.connect()
                client.groupAction("group-1", "rename", "  Work  ")
                client.moveWorkspace("workspace-1", "window-1", "group-1", "workspace-2")
                client.moveWorkspace("anchor", "window-1", null, "workspace-3", true)
                peer.join(3_000)
                assertEquals(3, observed.size)
                assertEquals("workspace.group.action", observed[0].getString("method"))
                assertEquals("Work", observed[0].getJSONObject("params").getString("title"))
                assertEquals("workspace.move", observed[1].getString("method"))
                assertEquals("group-1", observed[1].getJSONObject("params").getString("group_id"))
                assertEquals("window-1", observed[1].getJSONObject("params").getString("window_id"))
                assertEquals("workspace-2", observed[1].getJSONObject("params").getString("before_workspace_id"))
                assertTrue(!observed[1].getJSONObject("params").has("move_group"))
                assertTrue(observed[2].getJSONObject("params").getBoolean("move_group"))
                assertTrue(!observed[2].getJSONObject("params").has("group_id"))
                assertEquals("workspace-3", observed[2].getJSONObject("params").getString("before_workspace_id"))
                assertEquals("token", observed[1].getJSONObject("auth").getString("stack_access_token"))
            } finally {
                finishPeer.countDown()
                client.close()
                peer.join(3_000)
            }
        }
    }

    @Test fun sendsAuthenticatedFramedRequestAndMatchesResponseById() = runBlocking {
        ServerSocket(0).use { server ->
            val observed = AtomicReference<JSONObject>()
            val responseObserved = CountDownLatch(1)
            val finishPeer = CountDownLatch(1)
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
                    socket.getOutputStream().flush()
                    responseObserved.countDown()
                    finishPeer.await(5, TimeUnit.SECONDS)
                }
            }
            peer.start()
            val client = MobileRpcClient(PairingCode.Route("127.0.0.1", server.localPort), { "test-token" })
            try {
                client.connect()
                assertEquals(0, client.workspaces().getJSONArray("workspaces").length())
                assertTrue(responseObserved.await(3, TimeUnit.SECONDS))
                assertEquals("mobile.workspace.list", observed.get().getString("method"))
                assertEquals("test-token", observed.get().getJSONObject("auth").getString("stack_access_token"))
            } finally {
                finishPeer.countDown()
                peer.join(3_000)
                client.close()
            }
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
