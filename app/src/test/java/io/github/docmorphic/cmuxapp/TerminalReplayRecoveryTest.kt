package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TerminalReplayRecoveryTest {
    @Test fun replayCarriesTheSameClientGenerationAndNaturalGridAsTheViewportReport() = runBlocking {
        val answers = Channel<ByteArray>(Channel.UNLIMITED)
        val requests = mutableListOf<JSONObject>()
        var pin: JSONObject? = null
        val wire = object : MobileRpcTransport {
            override suspend fun connect() = Unit
            override suspend fun read() = answers.receiveCatching().getOrNull()
            override suspend fun write(bytes: ByteArray) {
                val request = JSONObject(String(bytes.copyOfRange(4, bytes.size)))
                requests += request
                val params = request.getJSONObject("params")
                val method = request.getString("method")
                if (method == "mobile.terminal.viewport") pin = params
                val accepted = method != "mobile.terminal.replay" || (params.has("viewport_generation") &&
                    params.getLong("viewport_generation") == pin!!.getLong("viewport_generation") &&
                    params.getString("client_id") == pin!!.getString("client_id"))
                val response = JSONObject().put("id", request.getString("id")).put("ok", accepted)
                if (accepted) response.put("result", JSONObject().put("columns", 40).put("rows", 10))
                else response.put("error", JSONObject().put("code", "viewport_transition").put("message", "Terminal viewport is still resizing"))
                answers.send(MobileFrameCodec.encode(response.toString().toByteArray()))
            }
            override fun close() { answers.close() }
        }
        MobileRpcClient(wire, { "fixture-token" }).use { owner ->
            owner.connect()
            owner.lease {}.use { client ->
                client.reportViewport("workspace", "surface", TerminalViewport(80, 24), 77)
                // The effective host grid is 40x10, but the replay must retain
                // this phone's original 80x24 report and its generation.
                client.replay("workspace", "surface", 80, 24, 77)
                client.clearViewport("workspace", "surface", 78)
                client.reportViewport("workspace", "surface", TerminalViewport(80, 12), 79)
                client.replay("workspace", "surface", 80, 12, 79)
            }
            assertEquals(5, requests.size)
            for (index in listOf(1, 4)) {
                val report = requests[index - 1].getJSONObject("params")
                val replay = requests[index].getJSONObject("params")
                for (key in listOf("workspace_id", "surface_id", "client_id", "viewport_generation", "viewport_columns", "viewport_rows")) {
                    assertEquals(key, report.get(key), replay.get(key))
                }
            }
        }
    }

    @Test fun fullGridWakesTransitionRecoveryWithoutWaitingForWatchdog() = runBlocking {
        val recovery = TerminalReplayRecovery(30_000)
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val result = async {
            recovery.replay {
                if (++calls == 1) {
                    started.complete(Unit)
                    throw MobileRpcException("viewport_transition", "resizing")
                }
                "settled"
            }
        }
        started.await()
        recovery.onFullGrid()
        assertEquals("settled", withTimeout(1000) { result.await() })
        assertEquals(2, calls)
    }

    @Test fun watchdogRetriesOnlyTwiceThenSurfacesTheActualFailure() = runBlocking {
        val recovery = TerminalReplayRecovery(1)
        val expected = MobileRpcException("viewport_transition", "resizing")
        var calls = 0
        val failure = runCatching { recovery.replay { calls++; throw expected } }.exceptionOrNull()
        assertSame(expected, failure)
        assertEquals(3, calls)
    }

    @Test fun authenticationAndTransportFailuresAreNotRetried() = runBlocking {
        for (expected in listOf(MobileRpcException("unauthorized", "denied"), java.io.IOException("closed"))) {
            var calls = 0
            val failure = runCatching { TerminalReplayRecovery(1).replay { calls++; throw expected } }.exceptionOrNull()
            assertSame(expected, failure)
            assertEquals(1, calls)
        }
    }

    @Test fun cancellingAReplacedViewportStopsItsPendingRecovery() = runBlocking {
        val recovery = TerminalReplayRecovery(30_000)
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val job = launch {
            recovery.replay { calls++; started.complete(Unit); throw MobileRpcException("viewport_transition", "resizing") }
        }
        started.await()
        job.cancelAndJoin()
        recovery.onFullGrid()
        assertEquals(1, calls)
    }

    @Test fun successfulReplayDoesNotWaitOrRequestAnotherSnapshot() = runBlocking {
        var calls = 0
        assertEquals("ready", TerminalReplayRecovery().replay { calls++; "ready" })
        assertEquals(1, calls)
    }
}
