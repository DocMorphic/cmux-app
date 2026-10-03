package io.github.docmorphic.cmuxapp

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.interfaces.ECPublicKey
import java.util.Base64

/** All JSch Android algorithm selection lives here; platform providers stay intact. */
internal object SshKeyCrypto {
    init {
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
    }
    const val MAX_PRIVATE_BYTES = 1024 * 1024
    fun load(openSsh: ByteArray, passphrase: ByteArray?): KeyPair {
        require(openSsh.size in 1..MAX_PRIVATE_BYTES)
        val header = "-----BEGIN OPENSSH PRIVATE KEY-----".toByteArray()
        require(openSsh.size >= header.size && openSsh.copyOfRange(0, header.size).contentEquals(header)) {
            "Use an OpenSSH private key including its BEGIN and END lines"
        }
        val privateCopy = openSsh.copyOf()
        val passCopy = passphrase?.copyOf()
        var key: KeyPair? = null
        try {
            key = KeyPair.load(JSch(), privateCopy, null)
            check(!key.isEncrypted || (passCopy != null && key.decrypt(passCopy))) {
                "The private key passphrase is missing or incorrect"
            }
            require(key.keyType == KeyPair.ED25519 || key.keyType == KeyPair.ECDSA) {
                "Use an Ed25519 or ECDSA OpenSSH key"
            }
            return key
        } catch (failure: Throwable) { key?.dispose(); throw failure }
        finally { privateCopy.fill(0); passCopy?.fill(0) }
    }
    fun publicKey(key: KeyPair): SshHostKey = SshHostKey.parse(
        key.keyTypeString + " " + Base64.getEncoder().encodeToString(key.publicKeyBlob))

    fun publicKey(key: ECPublicKey): SshHostKey {
        fun coordinate(value: java.math.BigInteger) = value.toByteArray().let {
            require(it.size <= 33)
            if (it.size == 33) it.copyOfRange(1, 33) else ByteArray(32 - it.size) + it
        }
        val blob = sshString("ecdsa-sha2-nistp256".toByteArray()) + sshString("nistp256".toByteArray()) +
            sshString(byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY))
        return SshHostKey.parse("ecdsa-sha2-nistp256 " + Base64.getEncoder().encodeToString(blob))
    }
    fun ecdsaSignature(der: ByteArray): ByteArray {
        val sequence = ASN1Sequence.getInstance(der)
        require(sequence.size() == 2)
        val r = ASN1Integer.getInstance(sequence.getObjectAt(0)).positiveValue.toByteArray()
        val s = ASN1Integer.getInstance(sequence.getObjectAt(1)).positiveValue.toByteArray()
        return sshString("ecdsa-sha2-nistp256".toByteArray()) + sshString(sshString(r) + sshString(s))
    }
    private fun sshString(value: ByteArray) = ByteArrayOutputStream().also {
        DataOutputStream(it).apply { writeInt(value.size); write(value) }
    }.toByteArray()

    fun encodeSecret(privateKey: ByteArray, passphrase: ByteArray?): ByteArray {
        require(privateKey.size <= MAX_PRIVATE_BYTES && (passphrase?.size ?: 0) <= 65536)
        // A single owned buffer avoids leaving a second plaintext copy inside
        // ByteArrayOutputStream after its toByteArray() copy is wiped.
        return ByteBuffer.allocate(12 + privateKey.size + (passphrase?.size ?: 0)).apply {
            putInt(1); putInt(privateKey.size); put(privateKey)
            putInt(passphrase?.size ?: -1); if (passphrase != null) put(passphrase)
        }.array()
    }
    fun <T> withDecodedSecret(bytes: ByteArray, use: (ByteArray, ByteArray?) -> T): T {
        var key: ByteArray? = null; var pass: ByteArray? = null
        try {
            val input = DataInputStream(bytes.inputStream())
            require(input.readInt() == 1)
            val keySize = input.readInt(); require(keySize in 1..MAX_PRIVATE_BYTES)
            key = ByteArray(keySize).also(input::readFully)
            val passSize = input.readInt(); require(passSize in -1..65536)
            pass = if (passSize == -1) null else ByteArray(passSize).also(input::readFully)
            require(input.available() == 0)
            return use(key, pass)
        } finally { key?.fill(0); pass?.fill(0) }
    }
}
