package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NativeForegroundAgentFeedTest {
    private class Wire : MobileRpcTransport {
        val requests = CopyOnWriteArrayList<JSONObject>()
        private val replies = Channel<ByteArray>(Channel.UNLIMITED)
        var connects = 0
        var revision = 1
        var resolved = false
        var readGate: CompletableDeferred<Unit>? = null
        var actionGate: CompletableDeferred<Unit>? = null
        var denied = false
        override suspend fun connect() { connects++ }
        override suspend fun read() = replies.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            val request = JSONObject(String(bytes.copyOfRange(4, bytes.size), Charsets.UTF_8)); requests += request
            val method = request.getString("method")
            if (method == "feed.list") readGate?.await()
            if (method == "feed.permission.reply") { resolved = true; revision++; actionGate?.await() }
            val result = if (method == "feed.list") JSONObject().put("revision", revision).put("items", JSONArray().put(JSONObject()
                .put("id", "event").put("workstream_id", "turn").put("source", "claude").put("kind", "permissionRequest")
                .put("status", if (resolved) "resolved" else "pending").put("request_id", "request")
                .put("created_at", 1791374400.0).put("updated_at", 1791374400.0).put("workspace_id", "workspace").put("surface_id", "surface")))
                else JSONObject()
            val response = JSONObject().put("id", request.getString("id")).put("ok", !denied)
            if (denied) response.put("error", JSONObject().put("code", "forbidden").put("message", "Fixture denial"))
            else response.put("result", result)
            replies.send(MobileFrameCodec.encode(response.toString().toByteArray()))
        }
        override fun close() { replies.close(); readGate?.cancel(); actionGate?.cancel() }
        fun count(method: String) = requests.count { it.optString("method") == method }
    }
    private suspend fun awaitState(predicate: () -> Boolean) = withTimeout(3000) { while (!predicate()) delay(5) }

    @Test fun pauseResumeRequiresFreshReadKeepsSessionAndDoesNotDialOrReplay() = runBlocking<Unit> {
        val wire = Wire(); val client = MobileRpcClient(wire, { "fixture" }); client.connect()
        val owner = NativeForegroundAgentFeed(this, client, { true }, {})
        try {
            owner.setActive(true); awaitState { owner.readySession() != null && wire.count("mobile.events.subscribe") == 1 }
            val session = checkNotNull(owner.readySession()); val item = session.state.value.snapshot!!.items.single()
            owner.setActive(false); assertNull(owner.readySession())
            assertTrue(runCatching { session.decide(item, AgentFeedDecision("permission", "once")) }.isFailure)
            val gate = CompletableDeferred<Unit>(); wire.readGate = gate
            owner.setActive(true); awaitState { wire.count("feed.list") >= 2 }
            assertNull(owner.readySession())
            assertFalse(session.decide(item, AgentFeedDecision("permission", "once")))
            assertEquals(0, wire.count("feed.permission.reply"))
            wire.readGate = null; gate.complete(Unit); awaitState { owner.readySession() != null }
            assertSame(session, owner.readySession())
            assertTrue(session.decide(item, AgentFeedDecision("permission", "once")))
            assertEquals(1, wire.count("feed.permission.reply")); assertEquals(1, wire.connects)
            owner.close(); assertFalse(client.isClosed)
        } finally { owner.close(); client.close() }
    }

    @Test fun proofLossDuringActionTokenLookupSendsNoDecisionBytes() = runBlocking<Unit> {
        val wire = Wire(); var armed = false; var permitted = true
        val lookup = CompletableDeferred<Unit>(); val token = CompletableDeferred<String>()
        val client = MobileRpcClient(wire, { if (armed) { lookup.complete(Unit); token.await() } else "fixture" }); client.connect()
        val owner = NativeForegroundAgentFeed(this, client, { permitted }, {})
        try {
            owner.setActive(true); awaitState { owner.readySession() != null && wire.count("mobile.events.subscribe") == 1 }
            val session = checkNotNull(owner.readySession()); val item = session.state.value.snapshot!!.items.single()
            armed = true
            val result = async { runCatching { session.decide(item, AgentFeedDecision("permission", "once")) } }
            withTimeout(3000) { lookup.await() }; permitted = false; token.complete("fixture")
            assertTrue(result.await().isFailure); assertEquals(0, wire.count("feed.permission.reply")); assertNull(owner.readySession())
        } finally { token.cancel(); owner.close(); client.close() }
    }

    @Test fun backgroundDuringWrittenDecisionRetainsUncertaintyAndNeverResendsOnResume() = runBlocking<Unit> {
        val wire = Wire(); val client = MobileRpcClient(wire, { "fixture" }); client.connect()
        val owner = NativeForegroundAgentFeed(this, client, { true }, {})
        try {
            owner.setActive(true); awaitState { owner.readySession() != null }
            val session = checkNotNull(owner.readySession()); val item = session.state.value.snapshot!!.items.single()
            val gate = CompletableDeferred<Unit>(); wire.actionGate = gate
            val result = async { runCatching { session.decide(item, AgentFeedDecision("permission", "once")) } }
            awaitState { wire.count("feed.permission.reply") == 1 }; owner.setActive(false)
            wire.actionGate = null; gate.complete(Unit); assertTrue(result.await().isFailure)
            assertEquals(AgentFeedDelivery.UNCONFIRMED, session.state.value.failures[item.id]?.delivery)
            owner.setActive(true); awaitState { owner.readySession() != null }
            assertEquals(AgentFeedStatus.RESOLVED, session.state.value.snapshot!!.items.single().status)
            assertEquals(1, wire.count("feed.permission.reply")); assertEquals(1, wire.connects)
        } finally { owner.close(); client.close() }
    }

    @Test fun deniedFeedRetiresRootWithoutAcceptingSnapshotOrRetryingIt() = runBlocking<Unit> {
        val wire = Wire().also { it.denied = true }; val client = MobileRpcClient(wire, { "fixture" }); client.connect()
        val owner = NativeForegroundAgentFeed(this, client, { true }, {})
        try {
            owner.setActive(true); awaitState { client.isClosed }
            assertNull(owner.readySession()); assertNull(owner.session.state.value.snapshot)
            assertEquals(1, wire.count("feed.list"))
        } finally { owner.close(); client.close() }
    }

    @Test fun readMarkerOwnerSurvivesMembershipRefreshButSeparatesAccountsAndTeams() {
        val team = NativeTeamScope("login", "user", "team", 1)
        assertEquals(nativeAgentFeedReadOwner("login", team), nativeAgentFeedReadOwner("login", team.copy(generation = 5)))
        assertNotEquals(nativeAgentFeedLegacyReadOwner("login", team), nativeAgentFeedLegacyReadOwner("login", team.copy(generation = 5)))
        for (next in listOf(team.copy(login = "other"), team.copy(userId = "other"), team.copy(teamId = "other")))
            assertNotEquals(nativeAgentFeedReadOwner("login", team), nativeAgentFeedReadOwner(next.login, next))
    }
}
