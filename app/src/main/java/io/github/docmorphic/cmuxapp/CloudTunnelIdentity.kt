package io.github.docmorphic.cmuxapp

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Cloud keys are separate from account tokens, push keys and the Iroh registry ID. */
internal class CloudWireGuardKey private constructor(private val bytes: ByteArray) {
    val publicKey: String get() = Base64.getEncoder().encodeToString(X25519PrivateKeyParameters(bytes).generatePublicKey().encoded)
    fun privateKeyBase64(): String = Base64.getEncoder().encodeToString(bytes)
    override fun toString() = "CloudWireGuardKey(redacted)"
    companion object {
        fun generate() = CloudWireGuardKey(X25519PrivateKeyParameters(SecureRandom()).encoded)
        fun restore(value: String): CloudWireGuardKey {
            val decoded = try { Base64.getDecoder().decode(value.trim()) } catch (_: IllegalArgumentException) { null }
            require(decoded?.size == 32) { "Invalid Cloud WireGuard key" }
            return CloudWireGuardKey(decoded)
        }
    }
}

/** One installation fingerprint. Browser enables mint their own key but reuse this fingerprint. */
internal class CloudTunnelIdentity(val fingerprint: String, val terminalKey: CloudWireGuardKey) {
    init { require(fingerprint.matches(Regex("android-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "Invalid Cloud identity" } }
    override fun toString() = "CloudTunnelIdentity(redacted)"
    internal fun encode(): ByteArray = JSONObject().put("version", 1).put("fingerprint", fingerprint)
        .put("privateKey", terminalKey.privateKeyBase64()).toString().toByteArray(Charsets.UTF_8)
    companion object {
        fun generate() = CloudTunnelIdentity("android-${UUID.randomUUID()}", CloudWireGuardKey.generate())
        internal fun decode(bytes: ByteArray): CloudTunnelIdentity = try {
            require(bytes.size in 1..4096)
            val value = JSONObject(bytes.toString(Charsets.UTF_8))
            require(value.opt("version") == 1)
            CloudTunnelIdentity(value.getString("fingerprint"), CloudWireGuardKey.restore(value.getString("privateKey")))
        } catch (_: Exception) { throw IllegalStateException("Saved Cloud identity is unreadable") }
    }
}

internal interface CloudIdentityCipher {
    fun encrypt(bytes: ByteArray): ByteArray
    fun decrypt(bytes: ByteArray): ByteArray
}

/** Root must be app-private/no-backup. No unreadable state is ever treated as a fresh install. */
internal class CloudTunnelIdentityStore(private val root: File, private val cipher: CloudIdentityCipher,
    private val replace: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); Unit
    }) {
    private val file = File(root, "identity.enc")
    fun stored(): CloudTunnelIdentity? = locked { read() }
    fun resolve(): CloudTunnelIdentity = locked {
        read() ?: CloudTunnelIdentity.generate().also { identity ->
            val plaintext = identity.encode()
            val sealed = try { cipher.encrypt(plaintext) } finally { plaintext.fill(0) }
            require(sealed.size in 1..8192) { "Invalid encrypted Cloud identity" }
            val temporary = Files.createTempFile(root.toPath(), "identity-", ".tmp")
            try {
                FileOutputStream(temporary.toFile()).use { it.write(sealed); it.fd.sync() }
                replace(temporary, file.toPath())
            } finally { Files.deleteIfExists(temporary) }
        }
    }
    private fun read(): CloudTunnelIdentity? {
        val sealed = try {
            Files.newInputStream(file.toPath()).use { input ->
                // Bound even a concurrently replaced or corrupt file before allocation.
                val buffer = ByteArray(8193)
                var count = 0
                while (count < buffer.size) {
                    val n = input.read(buffer, count, buffer.size - count)
                    if (n < 0) break
                    count += n
                }
                require(count in 1..8192) { "Invalid encrypted Cloud identity" }
                buffer.copyOf(count)
            }
        } catch (_: NoSuchFileException) { return null }
        val plaintext = cipher.decrypt(sealed)
        return try { CloudTunnelIdentity.decode(plaintext) } finally { plaintext.fill(0) }
    }
    private fun <T> locked(block: () -> T): T = synchronized(monitor) {
        check(root.isDirectory || root.mkdirs()) { "Cloud identity storage is unavailable" }
        // Serializes both controller instances and Android processes sharing the app sandbox.
        RandomAccessFile(File(root, "identity.lock"), "rw").use { handle ->
            handle.channel.lock().use { block() }
        }
    }
    companion object { private val monitor = Any() }
}
