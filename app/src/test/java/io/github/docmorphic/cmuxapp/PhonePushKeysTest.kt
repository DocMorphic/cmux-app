package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PhonePushKeysTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
        PairingCodeParser.computer(IrohV2Computer("record", "a".repeat(64), "directory-device", "stable", "Mac", emptyList()), team),
        "directory-device", "Mac", "stable"), team)
    private fun state() = JSONObject().put("task_session", team.login).put("refresh_token", "fixture")
        .put("pairings", JSONArray().put(NativePairingRecords.encode(mac)))
    private fun status() = JSONObject().put("mac_device_id", mac.deviceId).put("mac_instance_tag", "stable")
        .put("mac_client_namespace", "mac:com.cmuxapp.dev").put("capabilities", JSONArray().put("phone_push.keys.exchange.v1"))
    private val remote = PhonePushIdentity("mac-installation", "mac-key", ByteArray(32) { (it + 32).toByte() })
    private fun response() = JSONObject().put("version", 1).put("hpke_envelope_version", 2)
        .put("account_id", "user").put("mac_device_id", "physical-device").put("mac_instance_tag", "stable")
        .put("mac_build_id", "com.CmuxApp.dev").put("descriptor", remote.descriptor().wire())
    private fun peer(local: PhonePushIdentity) = PhonePushPeer(PhonePushTuple("user", null, "android.fixture",
        local.installationID, "physical-device", "stable", "com.CmuxApp.dev"), remote.descriptor())

    @Test fun keyIdentityPersistsAndPeerIsBoundToOwningLoginTeamAndComputer() {
        val state = state(); val keys = PhonePushKeyState(state); val identity = keys.identity(team.login)
        assertEquals(32, identity.privateKey.size)
        assertEquals(identity.descriptor(), PhonePushKeyState(JSONObject(state.toString())).identity(team.login).descriptor())
        keys.pin(team, mac.origin, peer(identity))
        assertEquals(peer(identity), keys.peer(team, mac.origin))
        assertNull(keys.peer(team.copy(teamId = "other"), mac.origin))
        state.put("task_session", "replacement"); keys.prune()
        assertFalse(state.has(PhonePushKeyState.KEY))
        assertNotEquals(identity.keyID, keys.identity("replacement").keyID)
        assertNull(keys.peer(team.copy(login = "replacement"), mac.origin))
    }

    @Test fun forgottenOwnerAndConflictingAliasesRetireKeysAndSafeRepairPreservesAssociation() {
        val state = state(); val keys = PhonePushKeyState(state); val identity = keys.identity(team.login)
        keys.pin(team, mac.origin, peer(identity))
        val repaired = mac.copy(stableOrigin = "b".repeat(64), previousOrigins = setOf(mac.origin))
        state.put("pairings", JSONArray().put(NativePairingRecords.encode(repaired))); keys.prune()
        assertEquals(peer(identity), keys.peer(team, repaired.origin))
        val ambiguous = repaired.copy(stableOrigin = "c".repeat(64), previousOrigins = setOf(repaired.origin))
        state.put("pairings", JSONArray(listOf(repaired, ambiguous).map(NativePairingRecords::encode))); keys.prune()
        assertNull(keys.peer(team, repaired.origin))
        state.put("pairings", JSONArray()); keys.prune()
        state.put("pairings", JSONArray().put(NativePairingRecords.encode(mac)))
        assertNull(keys.peer(team, mac.origin))
        assertEquals(identity.keyID, keys.identity(team.login).keyID)
    }

    @Test fun descriptorsRejectMalformedVersionsAlgorithmsAndLowOrderKeys() {
        assertEquals(remote.descriptor(), PhonePushDescriptor.parse(remote.descriptor().wire()))
        for ((key, value) in listOf("version" to 2, "version" to "1", "algorithm" to "other",
            "installationID" to " ", "publicKey" to "!".repeat(44),
            "publicKey" to Base64.getEncoder().encodeToString(ByteArray(32)),
            "publicKey" to Base64.getEncoder().encodeToString(ByteArray(32).apply { this[0] = 1 }))) {
            assertTrue("descriptor field $key", runCatching { PhonePushDescriptor.parse(remote.descriptor().wire().put(key, value)) }.isFailure)
        }
    }

    private suspend fun withPeer(reply: (JSONObject) -> JSONObject, action: suspend (MobileRpcClient, MutableList<JSONObject>) -> Unit) {
        val responses = Channel<ByteArray>(Channel.UNLIMITED)
        val requests = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val transport = object : MobileRpcTransport {
            override suspend fun connect() {}
            override suspend fun read() = responses.receiveCatching().getOrNull()
            override fun close() { responses.close() }
            override suspend fun write(bytes: ByteArray) {
                val request = JSONObject(bytes.copyOfRange(4, bytes.size).toString(Charsets.UTF_8)); requests += request
                responses.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id"))
                    .put("ok", true).put("result", reply(request)).toString().toByteArray()))
            }
        }
        val client = MobileRpcClient(transport, { "fixture" })
        try { client.connect(); action(client, requests) } finally { client.close() }
    }

    @Test fun authenticatedRpcPinsPhysicalIdentityWithoutConfusingDirectoryIdentityOrClientId() = runBlocking {
        val state = state(); val keys = PhonePushKeyState(state); val identity = keys.identity(team.login)
        withPeer({ response() }) { client, requests ->
            exchangePhonePushKeys(client, status(), mac, team, "android.fixture", { true }, { identity },
                { keys.pin(team, mac.origin, it) })
            val request = requests.single()
            assertEquals("phone_push.keys.exchange", request.getString("method"))
            val params = request.getJSONObject("params")
            assertEquals(1, params.getInt("version")); assertEquals(2, params.getInt("hpke_envelope_version"))
            assertEquals("android.fixture", params.getString("ios_build_id"))
            assertNotEquals(identity.installationID, params.getString("client_id"))
            assertEquals(identity.descriptor(), PhonePushDescriptor.parse(params.getJSONObject("descriptor")))
            assertFalse(params.toString().contains(Base64.getEncoder().encodeToString(identity.privateKey)))
            assertEquals("physical-device", keys.peer(team, mac.origin)!!.tuple.macDeviceID)
            assertEquals("mac-installation", keys.peer(team, mac.origin)!!.descriptor.installationID)
        }
    }

    @Test fun mismatchedAccountTeamBuildInstanceAndProtocolAreNeverPinnedAndRetriesAreBounded() = runBlocking {
        for ((field, value) in listOf("account_id" to "other", "team_id" to "other", "mac_instance_tag" to "other",
            "mac_build_id" to "other", "hpke_envelope_version" to 1)) {
            val pauses = mutableListOf<Long>()
            withPeer({ response().put(field, value) }) { client, requests ->
                exchangePhonePushKeys(client, status(), mac, team, "android.fixture", { true }, { remote },
                    { error("must not pin contradictory $field") }, { pauses += it })
                assertEquals(3, requests.size); assertEquals(listOf(1000L, 2000L), pauses)
            }
        }
    }

    @Test fun unsupportedUnadmittedRevokedAndCancelledWorkersCannotInstallKeys() = runBlocking {
        withPeer({ response() }) { client, requests ->
            exchangePhonePushKeys(client, status().put("capabilities", JSONArray()), mac, team, "android.fixture",
                { true }, { error("must not create keys") }, { error("must not pin") })
            exchangePhonePushKeys(client, status(), mac, team, "android.fixture",
                { false }, { error("must not create keys") }, { error("must not pin") })
            assertTrue(requests.isEmpty())
        }
        var allowed = true
        withPeer({ allowed = false; response() }) { client, requests ->
            exchangePhonePushKeys(client, status(), mac, team, "android.fixture", { allowed }, { remote }, { error("revoked") })
            assertEquals(1, requests.size)
        }
        withPeer({ response().put("account_id", "wrong") }) { client, requests ->
            val retry = CompletableDeferred<Unit>()
            val task = launch { exchangePhonePushKeys(client, status(), mac, team, "android.fixture", { true }, { remote },
                { error("must not pin") }, { retry.complete(Unit); awaitCancellation() }) }
            retry.await(); task.cancelAndJoin(); assertTrue(task.isCancelled); assertEquals(1, requests.size)
        }
    }
}
