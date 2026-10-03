package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SshHostStoreTest {
    private var disk: String? = null
    private var failWrite = false
    private val store = SshHostStore({ disk }, { if (failWrite) throw IOException("fixture disk full"); disk = it })
    private val host = SshHostRecord(name = "Lab", endpoint = SshEndpoint("lab.example", username = "tester"),
        keyId = UUID.randomUUID(), createdAtMillis = 100)
    // Generated fixture public keys, cross-checked with OpenSSH ssh-keygen -lf -E sha256.
    private val keyA = SshHostKey.parse("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIIQeI+xqWXRHyJwKWHbnuVPSUX18fsratMLcB9lIZMeU")
    private val keyB = SshHostKey.parse("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDtMCc1VAT++vF+e9R8luZF057BuiJ8ilpXKnKrMY6IL")
    private fun restore() = SshHostStore({ disk }, { disk = it })
    private fun trust(key: SshHostKey = keyA) = assertTrue(store.confirmHostKey(store.dialPlan(host.id), host.id,
        store.trustSnapshot(host.endpoint), key))
    private inline fun refuses(block: () -> Unit) {
        try { block(); fail("Operation should have failed") } catch (_: IllegalArgumentException) { }
    }

    @Test fun trimsDraftIpv6AndUsesOpenSshIdentityWithoutUsername() {
        val endpoint = SshEndpoint.fromDraft(" [FD00::B] ", " 2200 ", " tester ")
        assertEquals(SshEndpoint("FD00::B", 2200, "tester"), endpoint)
        assertEquals("[fd00::b]:2200", endpoint.hostKeyIdentity)
        assertEquals("server.example", SshEndpoint("SERVER.example", username = "other").hostKeyIdentity)
        for (bad in listOf("", "has space", "bad\u0000host")) refuses { SshEndpoint.fromDraft(bad, "22", "tester") }
        for (bad in listOf("0", "65536", "22.5", "abc")) refuses { SshEndpoint.fromDraft("host", bad, "tester") }
        refuses { SshEndpoint.fromDraft("host", "22", " ") }
    }

    @Test fun canonicalPublicKeyAndFingerprintMatchOpenSsh() {
        assertEquals(keyA, SshHostKey.parse("  ${keyA.openSsh} generated fixture comment"))
        assertEquals("SHA256:dQuCu6Jo2kGz+DS1UQVWFTJbvPHydjyct1QqqjeUnwI", keyA.sha256Fingerprint)
        assertEquals("SHA256:f3WA5lya+aXWIbhomFql+zFy4PsbnhXPRZrehVdvwqg", keyB.sha256Fingerprint)
        refuses { SshHostKey.parse(keyA.openSsh.replace("ssh-ed25519 ", "ssh-rsa ")) }
        for (bad in listOf("ssh-ed25519 %%%", "ssh-ed25519 AAAA", "${keyA.openSsh}\nnext")) {
            refuses { SshHostKey.parse(bad) }
        }
    }

    @Test fun restoresHostJumpKeyIdlePauseLastUsedAndTrustTogether() {
        val jump = host.copy(id = UUID.randomUUID(), name = "Jump", createdAtMillis = 1)
        store.upsert(jump)
        store.upsert(host.copy(jumpHostId = jump.id, idleClose = SshIdleClosePolicy.SEVEN_DAYS, autoConnectPaused = true))
        store.markUsed(host.id)
        trust()
        val restored = restore()
        assertEquals(store.state.value, restored.state.value)
        assertEquals(listOf(jump.id, host.id), restored.dialPlan(host.id).hops.map { it.hostId })
        assertFalse(restored.mayAutoConnect(restored.dialPlan(host.id)))
        assertEquals(SshHostTrustVerdict.TRUSTED, restored.trustSnapshot(host.endpoint).verdict(keyA))
        assertFalse(restored.isCurrent(store.dialPlan(host.id)))
        assertFalse(checkNotNull(disk).contains("privateKey"))
    }

    @Test fun ignoresLegacyModeAndDefaultsMissingOrUnknownIdlePolicy() {
        store.upsert(host)
        val original = checkNotNull(disk)
        for (idle in listOf(null, "future-policy")) {
            val json = JSONObject(original)
            val row = json.getJSONArray("hosts").getJSONObject(0)
            row.put("persistence", "obsolete-unrecognized-mode")
            if (idle == null) row.remove("idleClose") else row.put("idleClose", idle)
            row.remove("autoConnectPaused")
            disk = json.toString()
            val restored = restore().state.value.host(host.id)!!
            assertEquals(SshIdleClosePolicy.ONE_DAY, restored.idleClose)
            assertFalse(restored.autoConnectPaused)
            assertEquals(host.endpoint, restored.endpoint)
        }
    }

    @Test fun malformedOrUnreadableTrustDataCannotBecomeAnEmptyStore() {
        store.upsert(host); trust()
        val saved = checkNotNull(disk)
        val invalid = listOf("{", JSONObject(saved).put("version", 2).toString(),
            JSONObject(saved).put("pinnedKeys", JSONObject().put("lab.example", "invalid")).toString(),
            JSONObject(saved).apply { getJSONArray("hosts").put(getJSONArray("hosts").getJSONObject(0)) }.toString())
        for (text in invalid) {
            disk = text
            assertThrows(IOException::class.java) { restore() }
            assertEquals(text, disk)
        }
        assertThrows(IOException::class.java) { SshHostStore({ throw IOException("read failed") }, { fail("Unexpected write") }) }
    }

    @Test fun rejectsDanglingAndCyclicJumpRoutesWithoutWriting() {
        store.upsert(host)
        val saved = disk
        refuses { store.upsert(host.copy(jumpHostId = UUID.randomUUID())) }
        refuses { store.upsert(host.copy(jumpHostId = host.id)) }
        assertEquals(saved, disk)
        val child = host.copy(id = UUID.randomUUID(), jumpHostId = host.id)
        store.upsert(child)
        val before = store.state.value
        refuses { store.upsert(host.copy(jumpHostId = child.id)) }
        assertEquals(before, store.state.value)
    }

    @Test fun labelAndIdleEditsDoNotRetireRouteButAddressRoundTripDoes() {
        store.upsert(host)
        val plan = store.dialPlan(host.id)
        store.upsert(host.copy(name = "Renamed", idleClose = SshIdleClosePolicy.NEVER))
        assertTrue(store.isCurrent(plan))
        store.upsert(host.copy(endpoint = host.endpoint.copy(host = "other.example")))
        store.upsert(host)
        assertFalse(store.isCurrent(plan))
        assertFalse(store.setAutoConnectPaused(plan, host.id, true))
        assertFalse(store.state.value.host(host.id)!!.autoConnectPaused)
    }

    @Test fun editingJumpInvalidatesDependentsWithoutRetiringOtherHosts() {
        store.upsert(host)
        val child = host.copy(id = UUID.randomUUID(), jumpHostId = host.id)
        val other = host.copy(id = UUID.randomUUID(), name = "Other")
        store.upsert(child); store.upsert(other)
        val dependent = store.dialPlan(child.id)
        val independent = store.dialPlan(other.id)
        store.upsert(host.copy(keyId = UUID.randomUUID()))
        assertFalse(store.isCurrent(dependent))
        assertTrue(store.isCurrent(independent))
    }

    @Test fun deletingJumpClearsLinksAndLastUsedButKeepsEndpointTrust() {
        store.upsert(host); store.markUsed(host.id); trust()
        val child = host.copy(id = UUID.randomUUID(), jumpHostId = host.id)
        store.upsert(child)
        val before = store.dialPlan(child.id)
        store.delete(host.id)
        assertNull(store.state.value.lastUsedHostId)
        assertNull(store.state.value.host(child.id)!!.jumpHostId)
        assertEquals(keyA, store.trustSnapshot(host.endpoint).pinned)
        assertFalse(store.isCurrent(before))
        store.upsert(host)
        assertFalse(store.isCurrent(before))
        assertEquals(store.state.value, restore().state.value)
    }

    @Test fun keyDeletionClearsEveryReferenceAndRetiresAffectedPlans() {
        store.upsert(host)
        val sibling = host.copy(id = UUID.randomUUID())
        store.upsert(sibling)
        val before = store.dialPlan(host.id)
        store.removeKeyReferences(host.keyId!!)
        assertTrue(store.state.value.hosts.all { it.keyId == null })
        assertFalse(store.isCurrent(before))
        assertEquals(store.state.value, restore().state.value)
    }

    @Test fun declinePausesJumpAndChildAcrossRestoreUntilExplicitResume() {
        store.upsert(host)
        val child = host.copy(id = UUID.randomUUID(), jumpHostId = host.id)
        store.upsert(child)
        val plan = store.dialPlan(child.id)
        assertTrue(store.setAutoConnectPaused(plan, host.id, true))
        assertFalse(store.mayAutoConnect(store.dialPlan(child.id)))
        val restored = restore()
        val current = restored.dialPlan(child.id)
        assertFalse(restored.mayAutoConnect(current))
        assertTrue(restored.setAutoConnectPaused(current, host.id, false))
        assertTrue(restored.mayAutoConnect(restored.dialPlan(child.id)))
    }

    @Test fun identityQuestionCannotApproveAnEditedRouteOrAnotherEndpoint() {
        store.upsert(host)
        val plan = store.dialPlan(host.id)
        val question = store.trustSnapshot(host.endpoint)
        store.upsert(host.copy(endpoint = host.endpoint.copy(port = 2222)))
        assertFalse(store.confirmHostKey(plan, host.id, question, keyA))
        assertFalse(store.confirmHostKey(store.dialPlan(host.id), host.id, question, keyA))
        assertTrue(store.state.value.pinnedKeys.isEmpty())
    }

    @Test fun trustQuestionsCompareAndSetAndCannotReviveAfterPinRoundTrip() {
        store.upsert(host)
        val plan = store.dialPlan(host.id)
        val first = store.trustSnapshot(host.endpoint)
        assertEquals(SshHostTrustVerdict.UNKNOWN, first.verdict(keyA))
        trust()
        assertFalse(store.confirmHostKey(plan, host.id, first, keyB))
        val old = store.trustSnapshot(host.endpoint)
        assertEquals(SshHostTrustVerdict.CHANGED, old.verdict(keyB))
        trust(keyB); trust(keyA)
        assertFalse(store.confirmHostKey(plan, host.id, old, keyB))
        assertFalse(store.forgetHostKey(old))
        assertEquals(keyA, store.trustSnapshot(host.endpoint).pinned)
        assertTrue(store.forgetHostKey(store.trustSnapshot(host.endpoint)))
        assertNull(restore().trustSnapshot(host.endpoint).pinned)
    }

    @Test fun hostAndPinWriteFailurePreserveBytesStateAndExistingTokens() {
        store.upsert(host)
        val plan = store.dialPlan(host.id)
        val question = store.trustSnapshot(host.endpoint)
        val before = store.state.value
        val saved = disk
        failWrite = true
        assertThrows(IOException::class.java) { store.upsert(host.copy(endpoint = host.endpoint.copy(port = 2200))) }
        assertThrows(IOException::class.java) { store.confirmHostKey(plan, host.id, question, keyA) }
        assertEquals(saved, disk)
        assertEquals(before, store.state.value)
        assertTrue(store.isCurrent(plan))
        assertEquals(question, store.trustSnapshot(host.endpoint))
        failWrite = false
        assertTrue(store.confirmHostKey(plan, host.id, question, keyA))
    }

    @Test fun publishedSnapshotsAlreadyHaveTheirFinalRouteRevision() = runBlocking {
        val plans = mutableListOf<SshDialPlan>()
        val observer = launch(Dispatchers.Unconfined) {
            store.state.collect { if (it.host(host.id) != null) plans += store.dialPlan(host.id) }
        }
        store.upsert(host)
        assertTrue(store.isCurrent(plans.last()))
        store.upsert(host.copy(endpoint = host.endpoint.copy(port = 2200)))
        assertTrue(store.isCurrent(plans.last()))
        assertFalse(store.isCurrent(plans.first()))
        observer.cancelAndJoin()
    }

    @Test fun simultaneousSavesDoNotLoseRecordsOrPublishMutableCollections() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val work = (0 until 24).map { index -> pool.submit {
                store.upsert(host.copy(id = UUID.randomUUID(), name = "Host $index"))
            } }
            work.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertEquals(24, store.state.value.hosts.size)
        assertEquals(store.state.value, restore().state.value)
        assertThrows(UnsupportedOperationException::class.java) {
            (store.state.value.hosts as MutableList).clear()
        }
    }
}
