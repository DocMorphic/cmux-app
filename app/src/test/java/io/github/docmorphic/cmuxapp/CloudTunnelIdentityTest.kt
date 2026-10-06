package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CloudTunnelIdentityTest {
    @get:Rule val temp = TemporaryFolder()
    private val key = SecretKeySpec(ByteArray(32) { 17 }, "AES")
    private val cipher = object : CloudIdentityCipher {
        override fun encrypt(bytes: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key); iv + doFinal(bytes)
        }
        override fun decrypt(bytes: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes, 12, bytes.size - 12)
        }
    }
    @Test fun derivesWireGuardPublicKeyFromRfc7748PrivateKey() {
        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val privateKey = Base64.getEncoder().encodeToString(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        val restored = CloudWireGuardKey.restore(privateKey)
        assertEquals(Base64.getEncoder().encodeToString(hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")), restored.publicKey)
        assertEquals(privateKey, restored.privateKeyBase64())
        assertFalse(restored.toString().contains(privateKey))
        for (value in listOf("secret-invalid-key", "", Base64.getEncoder().encodeToString(ByteArray(31)))) {
            val failure = runCatching { CloudWireGuardKey.restore(value) }.exceptionOrNull()!!
            assertEquals("Invalid Cloud WireGuard key", failure.message)
        }
    }
    @Test fun persistsEncryptedIdentityBeforeReturningAndReopensAcrossControllers() {
        val root = temp.newFolder()
        val store = CloudTunnelIdentityStore(root, cipher)
        assertNull(store.stored())
        assertFalse(File(root, "identity.enc").exists())
        val first = store.resolve()
        val reopened = CloudTunnelIdentityStore(root, cipher).resolve()
        assertEquals(first.fingerprint, reopened.fingerprint)
        assertEquals(first.terminalKey.publicKey, reopened.terminalKey.publicKey)
        val disk = File(root, "identity.enc").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(disk.contains(first.fingerprint)); assertFalse(disk.contains(first.terminalKey.privateKeyBase64()))
        assertFalse(first.toString().contains(first.terminalKey.privateKeyBase64()))
        assertNotEquals(first.terminalKey.publicKey, CloudWireGuardKey.generate().publicKey)
        assertNotEquals(first.fingerprint, CloudTunnelIdentityStore(temp.newFolder(), cipher).resolve().fingerprint)
    }
    @Test fun simultaneousResolversPublishOneInstallation() {
        val root = temp.newFolder()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val results = (1..12).map { pool.submit<CloudTunnelIdentity> { CloudTunnelIdentityStore(root, cipher).resolve() } }.map { it.get() }
            assertEquals(1, results.map { it.fingerprint }.toSet().size)
            assertEquals(1, results.map { it.terminalKey.publicKey }.toSet().size)
        } finally { pool.shutdownNow() }
    }
    @Test fun corruptionAndUnavailableKeyNeverReplaceAnEnrolledIdentity() {
        val root = temp.newFolder()
        CloudTunnelIdentityStore(root, cipher).resolve()
        val file = File(root, "identity.enc")
        val bytes = file.readBytes().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        assertTrue(runCatching { CloudTunnelIdentityStore(root, cipher).resolve() }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
        val locked = object : CloudIdentityCipher {
            override fun encrypt(bytes: ByteArray): ByteArray = error("locked")
            override fun decrypt(bytes: ByteArray): ByteArray = error("locked")
        }
        assertTrue(runCatching { CloudTunnelIdentityStore(root, locked).resolve() }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
        file.writeBytes(ByteArray(8193))
        assertTrue(runCatching { CloudTunnelIdentityStore(root, cipher).stored() }.isFailure)
        file.writeBytes(cipher.encrypt("{\"privateKey\":\"secret-test-value\"}".toByteArray()))
        val failure = runCatching { CloudTunnelIdentityStore(root, cipher).resolve() }.exceptionOrNull()!!
        assertEquals("Saved Cloud identity is unreadable", failure.message)
        assertNull(failure.cause)
    }
    @Test fun failedPersistenceDoesNotPublishOrLeavePlaintextTemporaryFiles() {
        val root = temp.newFolder()
        val store = CloudTunnelIdentityStore(root, cipher, replace = { _, _ -> error("disk full") })
        assertTrue(runCatching { store.resolve() }.isFailure)
        assertNull(store.stored())
        assertEquals(listOf("identity.lock"), root.listFiles()!!.map { it.name })
    }
}
