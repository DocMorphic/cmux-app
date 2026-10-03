package io.github.docmorphic.cmuxapp.sshspike

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchUnknownHostKeyException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** Engine experiment, not the production SSH client or a hardware-security claim. */
@RunWith(AndroidJUnit4::class)
class SshEngineTest {
    private val fixture by lazy {
        JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("fixture.json").bufferedReader().use { it.readText() })
    }
    private val port get() = fixture.getInt("port")

    private fun client(): JSch = JSch().also {
        // Use the library's lightweight BC implementations on Android. Do not
        // replace Android's process-global BC/Keystore providers.
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
    }

    private fun session(client: JSch, endpointPort: Int = port, knownKey: String? = fixture.getString("hostKey")): Session {
        if (knownKey != null) client.setKnownHosts(
            "[127.0.0.1]:$endpointPort $knownKey\n".byteInputStream())
        return client.getSession(fixture.getString("username"), "127.0.0.1", endpointPort).apply {
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", "publickey")
            timeout = 8_000
        }
    }

    private fun readUntil(input: InputStream, expected: String): String {
        val end = System.nanoTime() + 10_000_000_000L
        val bytes = ByteArrayOutputStream()
        while (System.nanoTime() < end) {
            while (input.available() > 0) bytes.write(input.read())
            val text = bytes.toString("UTF-8")
            if (text.contains(expected)) return text
            Thread.sleep(20)
        }
        fail("Expected fixture response '$expected', received '${bytes.toString("UTF-8")}'")
        return ""
    }

    private fun probe(session: Session) {
        val channel = session.openChannel("exec") as ChannelExec
        try {
            channel.setCommand("probe")
            val input = channel.inputStream
            channel.connect(8_000)
            assertEquals(fixture.getString("nonce") + "\n", readUntil(input, "\n"))
        } finally { channel.disconnect() }
    }

    @Test fun keystoreSignatureAuthenticatesAndPtyResizeTravelsOverSsh() {
        KeystoreIdentity().use { identity ->
            assertNull("Private key must not export", identity.privateEncoding())
            val client = client().apply { addIdentity(identity, null) }
            val session = session(client)
            try {
                session.connect(10_000)
                assertTrue(identity.signatures > 0)
                probe(session)
                val shell = session.openChannel("shell") as ChannelShell
                try {
                    shell.setPtyType("xterm-256color", 80, 24, 0, 0)
                    val input = shell.inputStream
                    val output = shell.outputStream
                    shell.connect(8_000)
                    readUntil(input, "SIZE 80 24")
                    shell.setPtySize(101, 43, 0, 0)
                    readUntil(input, "SIZE 101 43")
                    output.write("unicode λ terminal\n".toByteArray())
                    output.flush()
                    readUntil(input, "ECHO unicode λ terminal")
                } finally { shell.disconnect() }
            } finally { session.disconnect() }
        }
    }

    @Test fun unknownAndChangedHostKeysFailBeforeSigning() {
        KeystoreIdentity().use { identity ->
            for (knownKey in listOf(null, fixture.getString("changedHostKey"))) {
                val client = client().apply { addIdentity(identity, null) }
                val session = session(client, knownKey = knownKey)
                try {
                    try { session.connect(10_000); fail("Untrusted server connected") }
                    catch (error: JSchException) {
                        assertFalse(session.isConnected)
                        if (knownKey == null) assertTrue(error is JSchUnknownHostKeyException)
                        else assertTrue("Expected changed-key refusal, got $error", error is JSchChangedHostKeyException)
                    }
                    assertEquals("Host refusal must precede client signing", 0, identity.signatures)
                } finally { session.disconnect() }
            }
            // The same signer/endpoint succeeds after explicitly installing the
            // correct pin. Network or crypto failures cannot satisfy this test.
            val accepted = session(client().apply { addIdentity(identity, null) })
            try { accepted.connect(10_000); probe(accepted); assertTrue(identity.signatures > 0) }
            finally { accepted.disconnect() }
        }
    }

    @Test fun encryptedOpenSshEd25519AndEcdsaAuthenticateAndRejectWrongPassphrase() {
        val keys = fixture.getJSONArray("imports")
        for (i in 0 until keys.length()) {
            val item = keys.getJSONObject(i)
            val encoded = item.getString("privateKey").toByteArray()
            try {
                val wrong = client()
                try {
                    wrong.addIdentity("wrong", encoded.copyOf(), null, "wrong-passphrase".toByteArray())
                    fail("Encrypted key accepted wrong passphrase")
                } catch (error: JSchException) {
                    assertEquals("Incorrect passphrase provided.", error.message)
                }
                finally { wrong.removeAllIdentity() }
                val client = client()
                val passphrase = item.getString("passphrase").toByteArray()
                try {
                    client.addIdentity(item.getString("algorithm"), encoded.copyOf(), null, passphrase)
                    val session = session(client)
                    try { session.connect(10_000); probe(session) }
                    finally { session.disconnect() }
                } finally { passphrase.fill(0); client.removeAllIdentity() }
            } finally { encoded.fill(0) }
        }
    }

    @Test fun sftpRoundTripRenameAndNonemptyDirectoryRefusal() {
        KeystoreIdentity().use { identity ->
            val session = session(client().apply { addIdentity(identity, null) })
            try {
                session.connect(10_000)
                val sftp = session.openChannel("sftp") as ChannelSftp
                val directory = "/test-${UUID.randomUUID()}"
                try {
                    sftp.connect(8_000)
                    sftp.mkdir(directory)
                    val bytes = ByteArray(180_000) { (it % 251).toByte() }
                    sftp.put(bytes.inputStream(), "$directory/λ.bin")
                    assertArrayEquals(bytes, sftp.get("$directory/λ.bin").use { it.readBytes() })
                    sftp.rename("$directory/λ.bin", "$directory/renamed.bin")
                    try { sftp.rmdir(directory); fail("Removed nonempty directory") }
                    catch (_: SftpException) { assertTrue(sftp.stat("$directory/renamed.bin").size > 0) }
                    sftp.rm("$directory/renamed.bin")
                    sftp.rmdir(directory)
                } finally { sftp.disconnect() }
            } finally { session.disconnect() }
        }
    }

    @Test fun twoDirectTcpipHopsAuthenticateAndParentDisconnectClosesDescendants() {
        KeystoreIdentity().use { identity ->
            fun newSession(endpointPort: Int) = session(
                client().apply { addIdentity(identity, null) }, endpointPort)
            val first = newSession(port)
            var second: Session? = null
            var third: Session? = null
            try {
                first.connect(10_000)
                second = newSession(first.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", port))
                second.connect(10_000)
                third = newSession(second.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", port))
                third.connect(10_000)
                probe(third)
                val pending = third.openChannel("exec") as ChannelExec
                pending.setCommand("wait")
                pending.connect(8_000)
                first.disconnect()
                val end = System.nanoTime() + 5_000_000_000L
                while ((second.isConnected || third.isConnected || pending.isConnected) && System.nanoTime() < end) {
                    Thread.sleep(20)
                }
                assertFalse(second.isConnected)
                assertFalse(third.isConnected)
                assertFalse(pending.isConnected)
            } finally { third?.disconnect(); second?.disconnect(); first.disconnect() }
        }
    }
}

/** Test-only Identity adapter exercises opaque Android Keystore signing. */
private class KeystoreIdentity : Identity, AutoCloseable {
    private val alias = "cmux-ssh-spike-${UUID.randomUUID()}"
    private val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
        initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256).build())
        generateKeyPair()
    }
    var signatures = 0
        private set
    private fun string(value: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).apply { writeInt(value.size); write(value) }
    }.toByteArray()
    private fun string(value: String) = string(value.toByteArray(Charsets.US_ASCII))
    private fun coordinate(value: java.math.BigInteger) = value.toByteArray().let { bytes ->
        if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size)
        else ByteArray(32 - bytes.size) + bytes
    }
    override fun getPublicKeyBlob(): ByteArray {
        val publicKey = pair.public as ECPublicKey
        val point = byteArrayOf(4) + coordinate(publicKey.w.affineX) + coordinate(publicKey.w.affineY)
        return string(algName) + string("nistp256") + string(point)
    }
    override fun getSignature(data: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(pair.private)
        signature.update(data)
        val der = ASN1Sequence.getInstance(signature.sign())
        val r = ASN1Integer.getInstance(der.getObjectAt(0)).positiveValue.toByteArray()
        val s = ASN1Integer.getInstance(der.getObjectAt(1)).positiveValue.toByteArray()
        signatures++
        return string(algName) + string(string(r) + string(s))
    }
    fun privateEncoding(): ByteArray? = pair.private.encoded
    override fun getAlgName() = "ecdsa-sha2-nistp256"
    override fun getName() = alias
    override fun isEncrypted() = false
    override fun setPassphrase(passphrase: ByteArray?) = true
    override fun clear() { /* Session cleanup does not delete a persistent key. */ }
    override fun close() { keys.deleteEntry(alias) }
}
