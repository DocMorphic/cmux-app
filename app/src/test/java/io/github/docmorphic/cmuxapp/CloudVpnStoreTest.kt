package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class CloudVpnStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = CloudVpnOwner("user", "team")
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = object : CloudIdentityCipher {
        override fun encrypt(bytes: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").let {
            it.init(Cipher.ENCRYPT_MODE, key); it.iv + it.doFinal(bytes)
        }
        override fun decrypt(bytes: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").let {
            it.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))); it.doFinal(bytes, 12, bytes.size - 12)
        }
    }
    private val config = """
        [Interface]
        PrivateKey = private-key-fixture
        Address = 10.0.0.2/32
        [Peer]
        PublicKey = public-key-fixture
        AllowedIPs = 10.0.0.0/8
        Endpoint = vpn.example.test:51820
    """.trimIndent()
    @Test fun interruptedEnrollmentSurvivesReopenAndBlocksReplacementUntilConfirmedCleanup() {
        val root = temporary.newFolder(); val store = CloudVpnStore(root, cipher)
        val first = store.begin(owner, "fingerprint")
        val reopened = CloudVpnStore(root, cipher)
        assertEquals(listOf(first), reopened.load().pending)
        assertThrows(IllegalStateException::class.java) { reopened.begin(owner, "fingerprint") }
        reopened.acknowledge(first)
        val next = reopened.begin(owner, "fingerprint")
        reopened.acknowledge(first)
        assertEquals(listOf(next), reopened.load().pending)
        assertNotEquals(first.attempt, next.attempt)
    }
    @Test fun encryptedProfileRetainsCleanupIdentityAndRejectsStaleRemoval() {
        val root = temporary.newFolder(); val store = CloudVpnStore(root, cipher)
        val first = store.begin(owner, "fingerprint")
        store.install(first, config)
        val reopened = CloudVpnStore(root, cipher)
        assertEquals(config, reopened.load().profile!!.configuration)
        assertEquals(first, reopened.load().profile!!.enrollment)
        val ciphertext = root.resolve("state.enc").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(ciphertext.contains("private-key-fixture")); assertFalse(ciphertext.contains("fingerprint"))
        assertThrows(IllegalStateException::class.java) { reopened.acknowledge(first) }
        reopened.retireProfile("old callback")
        assertNotNull(reopened.load().profile)
        reopened.retireProfile(first.attempt)
        assertNull(reopened.load().profile); assertEquals(listOf(first), reopened.load().pending)
        reopened.acknowledge(first)
        val next = reopened.begin(owner, "fingerprint"); reopened.install(next, config)
        reopened.retireProfile(first.attempt)
        assertEquals(next, reopened.load().profile!!.enrollment)
        assertFalse(reopened.load().profile.toString().contains("private-key-fixture"))
    }
    @Test fun failedAtomicWritesPreserveOldProfileAndCleanupEntries() {
        val root = temporary.newFolder(); val store = CloudVpnStore(root, cipher)
        val first = store.begin(owner, "fingerprint"); store.install(first, config)
        val fail = CloudVpnStore(root, cipher, replace = { _, _ -> throw IOException("Disk full") })
        assertThrows(IOException::class.java) { fail.retireProfile(first.attempt) }
        assertEquals(first, store.load().profile!!.enrollment)
        store.retireProfile(first.attempt)
        assertThrows(IOException::class.java) { fail.acknowledge(first) }
        assertEquals(listOf(first), store.load().pending)
        assertEquals(setOf("state.enc", "state.lock"), root.list()!!.toSet())
    }
    @Test fun capacityAndOwnerIsolationNeverEvictUnresolvedPeers() {
        val store = CloudVpnStore(temporary.newFolder(), cipher, capacity = 2)
        val first = store.begin(owner, "same fingerprint")
        val second = store.begin(owner.copy(team = "another"), "same fingerprint")
        assertThrows(IllegalStateException::class.java) { store.begin(owner.copy(user = "other"), "f") }
        store.acknowledge(first.copy(owner = owner.copy(user = "other")))
        assertEquals(listOf(first, second), store.load().pending)
        store.acknowledge(first)
        assertEquals(listOf(second), store.load().pending)
    }
    @Test fun corruptCiphertextAndUnsafeProfileCannotProduceFreshEnrollment() {
        val root = temporary.newFolder(); val store = CloudVpnStore(root, cipher)
        val entry = store.begin(owner, "fingerprint")
        assertThrows(IllegalArgumentException::class.java) { store.install(entry, config.replace("10.0.0.0/8", "0.0.0.0/0")) }
        assertNull(store.load().profile)
        val file = root.resolve("state.enc"); val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        assertTrue(runCatching { store.load() }.isFailure)
        assertTrue(runCatching { store.begin(owner, "new") }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun disconnectIntentSurvivesProcessReloadAndCannotBeOverwrittenByLateInstall() {
        val root = temporary.newFolder(); val store = CloudVpnStore(root, cipher)
        val entry = store.begin(owner, "fingerprint"); store.install(entry, config)
        store.requestStop()
        val restored = CloudVpnStore(root, cipher)
        assertFalse(restored.load().profile!!.requested)
        assertEquals(config, restored.load().profile!!.configuration)
        assertEquals(listOf(entry), restored.load().pending)
        assertThrows(IllegalStateException::class.java) { restored.install(entry, config) }
        restored.requestStop(); assertFalse(restored.load().profile!!.requested)
        restored.retireProfile(entry.attempt); restored.acknowledge(entry)
        val replacement = restored.begin(owner, "fingerprint"); restored.install(replacement, config)
        assertTrue(restored.load().profile!!.requested)
    }

    @Test fun existingVersionOneProfilesWithoutTheIntentFieldRemainReadable() {
        val root = temporary.newFolder(); val store = CloudVpnStore(root, cipher)
        val entry = store.begin(owner, "fingerprint"); store.install(entry, config)
        val file = root.resolve("state.enc")
        val value = org.json.JSONObject(cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8))
        value.getJSONObject("profile").remove("requested")
        file.writeBytes(cipher.encrypt(value.toString().toByteArray(Charsets.UTF_8)))
        assertTrue(CloudVpnStore(root, cipher).load().profile!!.requested)
        store.requestStop()
        assertFalse(CloudVpnStore(root, cipher).load().profile!!.requested)
    }
}
