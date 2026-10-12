package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeForegroundReplyAccessTest {
    private val owner = NativeTeamScope("login", "user", "original", 7)
    private val mac = NativeCredentialStore.PairedMac("route", "directory-device", "Original", "stable", "user", "original", "origin")
    private val peer = PhonePushPeer(PhonePushTuple("user", null, "fixture", "phone", "physical-device", "stable", "fixture.mac"),
        PhonePushIdentity.generate().descriptor())
    private val target = PhoneReplyDirectTarget(owner.copy(generation = 0), "origin", "epoch", peer, "workspace", "surface", false)
    private val workspace = NativeWorkspace("workspace", "Original", listOf(NativeTerminal("surface", "Shell")),
        null, false, null, null, false, emptyList(), null, null, null)

    @Test fun actionOwnerMatchesLiveAccountTeamWithoutRequiringItsHistoricalGenerationOrDirectoryPushIdEquality() {
        assertTrue(nativePhoneReplyOwnerMatches(target, owner))
        assertTrue(nativePhoneReplyOwnerMatches(target.copy(peer = peer.copy(tuple = peer.tuple.copy(teamID = "original"))), owner))
        assertFalse(nativePhoneReplyOwnerMatches(target, null))
        for (changed in listOf(owner.copy(login = "other"), owner.copy(userId = "other"), owner.copy(teamId = "picker")))
            assertFalse(nativePhoneReplyOwnerMatches(target, changed))
        assertFalse(nativePhoneReplyOwnerMatches(target.copy(peer = peer.copy(tuple = peer.tuple.copy(accountID = "other"))), owner))
        assertFalse(nativePhoneReplyOwnerMatches(target.copy(peer = peer.copy(tuple = peer.tuple.copy(teamID = "other"))), owner))
    }

    @Test fun retainedOwnerUsesOneExistingWireAndPreservesLiteralTextAndReturn() = runBlocking<Unit> {
        val wire = PoolTestTransport(); val client = MobileRpcClient(wire, { "fixture" })
        try {
            client.connect()
            fun admitted() = nativeForegroundMacSettingsCurrent("login", owner, mac, client, client, mac, true,
                mac.code, mac.code, listOf(mac), { false }, { false }, { _, _ -> owner }) && nativePhoneReplyOwnerMatches(target, owner)
            val attempt = checkNotNull(nativeForegroundReplyAttempt(target, mac, client, { listOf(workspace) }, ::admitted))
            val result = async { attempt.send(" literal λ 中\n") { true } }
            val request = withTimeout(3000) { wire.sent.receive() }; val params = request.getJSONObject("params")
            assertEquals("terminal.paste", request.getString("method"))
            assertEquals("workspace", params.getString("workspace_id")); assertEquals("surface", params.getString("surface_id"))
            assertEquals(" literal λ 中\n", params.getString("text")); assertEquals("return", params.getString("submit_key"))
            wire.incoming.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", true)
                .put("result", JSONObject().put("submitted", true)).toString().toByteArray()))
            assertEquals(PhoneReplyDirectResult.DELIVERED, result.await()); assertEquals(1, wire.connects.get())
            assertEquals(PhoneReplyDirectResult.UNKNOWN, attempt.send("duplicate") { true })
            assertTrue(wire.sent.tryReceive().isFailure)
        } finally { client.close() }
    }

    @Test fun topologyAuthorityAndOriginChangesCannotChooseAnotherTerminalOrStartAWrite() = runBlocking<Unit> {
        val wire = PoolTestTransport(); val client = MobileRpcClient(wire, { "fixture" })
        try {
            client.connect(); var current = true; var rows = listOf(workspace)
            assertNull(nativeForegroundReplyAttempt(target.copy(origin = "other"), mac, client, { rows }, { true }))
            assertNull(nativeForegroundReplyAttempt(target, mac, client, { emptyList() }, { true }))
            val first = checkNotNull(nativeForegroundReplyAttempt(target, mac, client, { rows }, { current }))
            rows = listOf(workspace.copy(id = "moved"))
            assertEquals(PhoneReplyDirectResult.UNAVAILABLE, first.send("must not write") { true })
            val movedTarget = target.copy(retarget = true)
            val second = checkNotNull(nativeForegroundReplyAttempt(movedTarget, mac, client, { rows }, { current }))
            current = false
            assertEquals(PhoneReplyDirectResult.UNAVAILABLE, second.send("must not write") { true })
            assertTrue(wire.sent.tryReceive().isFailure)
        } finally { client.close() }
    }

    @Test fun authorityLossDuringTokenLookupSendsNoPasteBytes() = runBlocking<Unit> {
        val wire = PoolTestTransport(); val lookup = CompletableDeferred<Unit>(); val token = CompletableDeferred<String>()
        val client = MobileRpcClient(wire, { lookup.complete(Unit); token.await() })
        try {
            client.connect(); var current = true
            val attempt = checkNotNull(nativeForegroundReplyAttempt(target, mac, client, { listOf(workspace) }, { current }))
            val result = async { attempt.send("must not write") { true } }
            withTimeout(3000) { lookup.await() }; current = false; token.complete("fixture")
            assertEquals(PhoneReplyDirectResult.UNKNOWN, result.await()); assertTrue(wire.sent.tryReceive().isFailure)
        } finally { token.cancel(); client.close() }
    }

    @Test fun authorizationRejectionRetiresTheWireAndDoesNotReplay() = runBlocking<Unit> {
        val wire = PoolTestTransport(); val client = MobileRpcClient(wire, { "fixture" })
        try {
            client.connect()
            val attempt = checkNotNull(nativeForegroundReplyAttempt(target, mac, client, { listOf(workspace) }, { true }))
            val result = async { attempt.send("reply") { true } }; val request = withTimeout(3000) { wire.sent.receive() }
            wire.incoming.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", false)
                .put("error", JSONObject().put("code", "forbidden").put("message", "Fixture denial")).toString().toByteArray()))
            assertEquals(PhoneReplyDirectResult.UNKNOWN, result.await()); assertTrue(client.isClosed)
            assertTrue(wire.sent.tryReceive().isFailure); assertEquals(1, wire.connects.get())
        } finally { client.close() }
    }
}
