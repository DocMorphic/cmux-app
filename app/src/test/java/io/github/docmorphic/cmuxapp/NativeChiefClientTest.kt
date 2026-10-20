package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NativeChiefClientTest {
    private val reference = javaClass.getResourceAsStream("/parity/chief-wire.json")!!.bufferedReader().use { MobileJson.objectValue(it.readText()) }
    private val records = reference.getJSONArray("requests").let { rows -> (0 until rows.length()).map(rows::getJSONObject) }
    private inner class Wire : MobileRpcTransport {
        val requests = CopyOnWriteArrayList<JSONObject>()
        val observed = Channel<JSONObject>(Channel.UNLIMITED)
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        var hold = false
        override suspend fun connect() {}
        override suspend fun read() = incoming.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            val request = MobileJson.objectValue(MobileFrameDecoder().feed(bytes).single().toString(Charsets.UTF_8), true)
            requests += request; observed.send(request)
            if (!hold || !request.getString("method").startsWith("chief.")) reply(request)
        }
        suspend fun reply(request: JSONObject) {
            val method = request.getString("method")
            val result = if (method == "mobile.workspace.list") JSONObject().put("workspaces", org.json.JSONArray()) else {
                val expected = records.single { JSONObject(it.getString("json")).getString("method") == method }
                MobileJson.objectValue(expected.getString("response"), true)
            }
            incoming.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", true)
                .put("result", result).toString().toByteArray()))
        }
        override fun close() { incoming.close() }
    }

    @Test fun nineRequestsMatchTheActualSwiftClientAndKeepSelectionTicketsOffChief() = runBlocking<Unit> {
        val wire = Wire()
        val ticket = MobileAttachTicketContext("only-workspace", "only-terminal", "fixture-selection-token", Long.MAX_VALUE)
        MobileRpcClient(wire, { "fixture-account-token" }, ticket).use { rpc ->
            rpc.connect()
            val client = NativeChiefClient(rpc) { true }
            assertFalse(client.status().started)
            assertTrue(client.conversations("owner-cursor", true).conversations.isEmpty())
            assertEquals("chief-main", client.read("chief-main", ULong.MAX_VALUE, 500, 9uL, "transcript-cursor", true).conversation)
            assertEquals(ULong.MAX_VALUE, client.markRead("chief-main", ULong.MAX_VALUE).upToSeq)
            val sent = client.send("Hello", "chief-main", "user_local", false, "client-id")
            assertEquals("sent", sent.messageId); assertEquals("client-id", sent.clientMessageId); assertTrue(sent.queued)
            assertTrue(client.stop("chief-main").stopped)
            assertTrue(client.approve("chief-main", "r-1", "allow").decided)
            assertEquals(listOf("US"), client.answer("chief-main", "q-1", listOf("US"), "Other").answer)
            assertTrue(client.open("window", "workspace").opened)
            assertEquals(records.size, wire.requests.size)
            records.zip(wire.requests).forEach { (record, actual) ->
                val expected = MobileJson.objectValue(record.getString("json"), true)
                assertEquals(expected.getString("method"), actual.getString("method"))
                assertEquals(expected.getString("method"), jsonValue(expected.getJSONObject("params")), jsonValue(actual.getJSONObject("params")))
                assertEquals("fixture-account-token", actual.getJSONObject("auth").getString("stack_access_token"))
                assertFalse(actual.getJSONObject("auth").has("attach_token"))
                assertFalse(MobileControlResendPolicy.allows(actual.getString("method"), actual.getJSONObject("params")))
            }
        }
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { jsonValue(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        is Number -> value.toString().toBigDecimal().stripTrailingZeros()
        else -> value
    }

    @Test fun retirementDuringCredentialLookupPreventsTheMutationFromReachingTheWire() = runBlocking<Unit> {
        val wire = Wire(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var current = true
        MobileRpcClient(wire, { entered.complete(Unit); release.await(); "fixture-account-token" }).use { rpc ->
            rpc.connect(); val client = NativeChiefClient(rpc) { current }
            val pending = async { runCatching { client.send("private draft", "chief-main", clientMessageId = "one-send") } }
            withTimeout(3000) { entered.await() }; current = false; release.complete(Unit)
            assertTrue(withTimeout(3000) { pending.await() }.isFailure)
            assertTrue(wire.requests.isEmpty()); assertFalse(rpc.isClosed)
        }
    }

    @Test fun aLateReadCannotPublishAfterItsMacOrAccountHasRetired() = runBlocking<Unit> {
        val wire = Wire().apply { hold = true }; var current = true
        MobileRpcClient(wire, { "fixture" }).use { rpc ->
            rpc.connect(); val client = NativeChiefClient(rpc) { current }
            val pending = async { runCatching { client.read("chief-main", waitForChange = true) } }
            val request = withTimeout(3000) { wire.observed.receive() }
            current = false; wire.reply(request)
            assertTrue(withTimeout(3000) { pending.await() }.isFailure)
            assertFalse(rpc.isClosed)
        }
    }

    @Test fun cancellingOneLongPollPreservesTheSharedConnectionAndIgnoresItsLateReply() = runBlocking<Unit> {
        val wire = Wire().apply { hold = true }
        MobileRpcClient(wire, { "fixture" }).use { rpc ->
            rpc.connect(); val client = NativeChiefClient(rpc) { true }
            val first = async { client.read("chief-main", waitForChange = true) }
            val old = withTimeout(3000) { wire.observed.receive() }
            first.cancelAndJoin(); wire.reply(old)
            assertTrue(first.isCancelled); assertFalse(rpc.isClosed)
            assertEquals(0, rpc.workspaces().getJSONArray("workspaces").length())
            val second = async { client.read("chief-main", waitForChange = true) }
            val next = withTimeout(3000) {
                var request = wire.observed.receive()
                while (request.getString("method") != "chief.read") request = wire.observed.receive()
                request
            }
            assertNotEquals(old.getString("id"), next.getString("id"))
            wire.reply(next); assertEquals("chief-main", withTimeout(3000) { second.await() }.conversation)
        }
    }

    @Test fun alreadyRetiredAccessRejectsReadsAndMutationsWithoutOpeningAnotherConnection() = runBlocking<Unit> {
        val wire = Wire()
        MobileRpcClient(wire, { "fixture" }).use { rpc ->
            rpc.connect(); val client = NativeChiefClient(rpc) { false }
            assertTrue(runCatching { client.conversations() }.isFailure)
            assertTrue(runCatching { client.approve("chief-main", "r-1", "allow") }.isFailure)
            assertTrue(wire.requests.isEmpty())
        }
    }
}
