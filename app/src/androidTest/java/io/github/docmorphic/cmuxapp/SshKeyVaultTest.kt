package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Uses isolated files/aliases only; does not access accounts or saved SSH users. */
class SshKeyVaultTest {
    private lateinit var root: File
    private lateinit var vault: SshKeyVault
    private var deviceUnlocked = true
    private var writeFails = false
    private var referencesFail = false
    private val removed = mutableListOf<UUID>()
    private val challenge = "cmux generated fixture authentication challenge".toByteArray()
    private val keys get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val prefix get() = "cmux.ssh.v1." + MessageDigest.getInstance("SHA-256")
        .digest(root.canonicalPath.toByteArray()).take(12).joinToString("") { "%02x".format(it) } + "."
    private val metadata get() = File(root, "keys-v1.json")

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.noBackupFilesDir, "ssh-vault-test-${UUID.randomUUID()}")
        vault = reopen()
    }
    private fun reopen() = SshKeyVault(root, { deviceUnlocked }, {
        if (referencesFail) throw IOException("fixture host-reference failure")
        removed += it
    }, { if (writeFails) throw IOException("fixture write failure") })
    @After fun cleanup() {
        keys.aliases().toList().filter { it.startsWith(prefix) }.forEach(keys::deleteEntry)
        root.deleteRecursively()
    }
    private fun verify(key: SshHostKey, signature: ByteArray) {
        if (key.algorithm == "ssh-ed25519") {
            // JSch 2.28.0's public-only KeyPair.load passes the full text line as
            // the Ed25519 point. Verify the SSH wire fields directly instead.
            fun DataInputStream.field(): ByteArray {
                val size = readInt(); require(size in 1..32768)
                return ByteArray(size).also(::readFully)
            }
            val public = DataInputStream(Base64.getDecoder().decode(key.openSsh.substringAfter(' ')).inputStream())
            assertEquals("ssh-ed25519", String(public.field()))
            val point = public.field(); assertEquals(32, point.size); assertEquals(0, public.available())
            val signed = DataInputStream(signature.inputStream())
            assertEquals("ssh-ed25519", String(signed.field()))
            val raw = signed.field(); assertEquals(64, raw.size); assertEquals(0, signed.available())
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(point, 0))
            verifier.update(challenge, 0, challenge.size)
            assertTrue(verifier.verifySignature(raw))
            return
        }
        val public = KeyPair.load(JSch(), null, key.openSsh.toByteArray())
        try {
            val verifier = checkNotNull(public.verifier)
            verifier.update(challenge)
            assertTrue("SSH signature must verify against the public key", verifier.verify(signature))
        } finally { public.dispose() }
    }
    private fun imported(type: Int = KeyPair.ED25519, passphrase: ByteArray? = "fixture-passphrase".toByteArray()): Pair<ByteArray, ByteArray?> {
        // Initialize the same explicit Android algorithm mapping as production.
        SshKeyCrypto.encodeSecret(byteArrayOf(1), null)
        val key = KeyPair.genKeyPair(JSch(), type, if (type == KeyPair.ECDSA) 256 else 2048)
        try {
            val out = ByteArrayOutputStream()
            key.writeOpenSSHv1PrivateKey(out, passphrase)
            return out.toByteArray() to passphrase
        } finally { key.dispose() }
    }

    @Test fun generatedKeyReloadsRenamesSignsOnceAndNeverExports() {
        val record = vault.generate("Laptop key")
        assertNull(keys.getKey(prefix + record.id, null).encoded)
        val restored = reopen()
        assertEquals(listOf(record), restored.state.value)
        restored.rename(record.id, "New label")
        val prepared = restored.prepareSignature(record.id)
        verify(record.publicKey, prepared.sign(challenge))
        assertThrows(IllegalStateException::class.java) { prepared.sign(challenge) }
        assertEquals("New label", reopen().state.value.single().label)
        assertFalse(metadata.readText().contains("PRIVATE KEY"))
    }

    @Test fun encryptedOpenSshImportsReloadAndSignWithoutPlaintextOnDisk() {
        for (type in listOf(KeyPair.ED25519, KeyPair.ECDSA)) {
            val (privateKey, passphrase) = imported(type)
            try {
                val record = vault.import("Imported $type", privateKey, passphrase)
                val text = metadata.readText()
                assertFalse(text.contains("BEGIN OPENSSH PRIVATE KEY"))
                assertFalse(text.contains(String(passphrase!!)))
                assertFalse(text.contains(Base64.getEncoder().encodeToString(privateKey)))
                val restored = reopen()
                restored.rename(record.id, "Renamed")
                restored.openImportedKey(record.id).use { verify(record.publicKey, it.sign(challenge)) }
                vault = restored
            } finally { privateKey.fill(0); passphrase?.fill(0) }
        }
    }

    @Test fun wrongPassphraseAndRsaImportLeaveNoRecordsOrAliases() {
        val (privateKey, passphrase) = imported()
        try {
            assertThrows(IllegalStateException::class.java) { vault.import("Wrong", privateKey, "incorrect".toByteArray()) }
            assertTrue(vault.state.value.isEmpty())
            assertFalse(keys.aliases().toList().any { it.startsWith(prefix) })
        } finally { privateKey.fill(0); passphrase?.fill(0) }
        val (rsa, _) = imported(KeyPair.RSA, null)
        try { assertThrows(IllegalArgumentException::class.java) { vault.import("Unsupported", rsa, null) } }
        finally { rsa.fill(0) }
        assertTrue(vault.state.value.isEmpty())
        assertFalse(metadata.exists())
    }

    @Test fun modifiedCiphertextAndPublicIdentityCannotDecrypt() {
        val (privateKey, passphrase) = imported()
        val record = try { vault.import("Original", privateKey, passphrase) }
        finally { privateKey.fill(0); passphrase?.fill(0) }
        val original = metadata.readText()
        val corrupted = JSONObject(original)
        val row = corrupted.getJSONArray("keys").getJSONObject(0)
        val bytes = Base64.getDecoder().decode(row.getString("sealed"))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        row.put("sealed", Base64.getEncoder().encodeToString(bytes))
        metadata.writeText(corrupted.toString())
        assertThrows(AEADBadTagException::class.java) { reopen().openImportedKey(record.id) }
        metadata.writeText(original)
        val another = vault.generate("Another")
        val changed = JSONObject(metadata.readText())
        changed.getJSONArray("keys").getJSONObject(0).put("publicKey", another.publicKey.openSsh)
        metadata.writeText(changed.toString())
        assertThrows(AEADBadTagException::class.java) { reopen().openImportedKey(record.id) }
    }

    @Test fun deletionInvalidatesPreparedAndLeasedSignersAndRestoredCiphertext() {
        val generated = vault.generate("Generated")
        val prepared = vault.prepareSignature(generated.id)
        val (privateKey, passphrase) = imported()
        val record = try { vault.import("Imported", privateKey, passphrase) }
        finally { privateKey.fill(0); passphrase?.fill(0) }
        val saved = metadata.readText()
        val lease = vault.openImportedKey(record.id)
        vault.delete(generated.id); vault.delete(record.id)
        assertThrows(IllegalStateException::class.java) { prepared.sign(challenge) }
        assertThrows(IllegalStateException::class.java) { lease.sign(challenge) }
        lease.close()
        assertTrue(vault.state.value.isEmpty())
        assertTrue(removed.containsAll(listOf(record.id, generated.id)))
        assertFalse(keys.containsAlias(prefix + record.id))
        metadata.writeText(saved)
        val restored = reopen()
        assertThrows(IllegalStateException::class.java) { restored.openImportedKey(record.id) }
        assertFalse(keys.containsAlias(prefix + record.id))
    }

    @Test fun creationFailureRollsBackAndDeletionTombstoneSurvivesCleanupFailure() {
        writeFails = true
        assertThrows(IOException::class.java) { vault.generate("Cannot save") }
        assertTrue(vault.state.value.isEmpty())
        assertFalse(keys.aliases().toList().any { it.startsWith(prefix) })
        writeFails = false
        val record = vault.generate("Delete me")
        val prepared = vault.prepareSignature(record.id)
        referencesFail = true
        assertThrows(IOException::class.java) { vault.delete(record.id) }
        assertTrue(vault.state.value.isEmpty())
        assertThrows(IllegalStateException::class.java) { prepared.sign(challenge) }
        assertTrue(JSONObject(metadata.readText()).getJSONArray("keys").getJSONObject(0).getBoolean("deleted"))
        referencesFail = false
        assertTrue(reopen().state.value.isEmpty())
        assertFalse(keys.containsAlias(prefix + record.id))
    }

    @Test fun lockedDeviceAndClosedLeaseRefuseUse() {
        val record = vault.generate("Generated")
        val prepared = vault.prepareSignature(record.id)
        val (privateKey, passphrase) = imported()
        val imported = try { vault.import("Imported", privateKey, passphrase) }
        finally { privateKey.fill(0); passphrase?.fill(0) }
        val lease = vault.openImportedKey(imported.id)
        deviceUnlocked = false
        assertThrows(IllegalStateException::class.java) { vault.generate("Locked") }
        assertThrows(IllegalStateException::class.java) { vault.prepareSignature(record.id) }
        assertThrows(IllegalStateException::class.java) { prepared.sign(challenge) }
        assertThrows(IllegalStateException::class.java) { lease.sign(challenge) }
        deviceUnlocked = true
        lease.close()
        assertThrows(IllegalStateException::class.java) { lease.sign(challenge) }
        verify(record.publicKey, vault.prepareSignature(record.id).sign(challenge))
    }

    @Test fun corruptMetadataDoesNotEraseKeystoreMaterial() {
        val record = vault.generate("Retain on corruption")
        metadata.writeText("{broken")
        assertThrows(IOException::class.java) { reopen() }
        assertTrue(keys.containsAlias(prefix + record.id))
        assertEquals("{broken", metadata.readText())
    }
}
