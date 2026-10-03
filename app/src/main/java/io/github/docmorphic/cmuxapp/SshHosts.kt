package io.github.docmorphic.cmuxapp

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal data class SshEndpoint(val host: String, val port: Int = 22, val username: String) {
    init {
        require(host.isNotEmpty() && host == host.trim() && host.none { it.isWhitespace() || it.isISOControl() })
        require(!host.startsWith('[') && !host.endsWith(']'))
        require(port in 1..65535)
        require(username.isNotEmpty() && username == username.trim() && username.none { it.isISOControl() })
    }
    val hostKeyIdentity: String get() = host.lowercase(Locale.ROOT).let {
        if (port == 22) it else "[$it]:$port"
    }
    companion object {
        fun fromDraft(host: String, port: String, username: String): SshEndpoint {
            val trimmed = host.trim().let {
                if (it.startsWith('[') && it.endsWith(']')) it.substring(1, it.length - 1) else it
            }
            return SshEndpoint(trimmed, port.trim().toInt(), username.trim())
        }
    }
}

internal enum class SshIdleClosePolicy(val seconds: Long?) {
    ONE_HOUR(3600), ONE_DAY(86400), SEVEN_DAYS(604800), NEVER(null)
}

internal data class SshHostRecord(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val endpoint: SshEndpoint,
    val keyId: UUID? = null,
    val jumpHostId: UUID? = null,
    val idleClose: SshIdleClosePolicy = SshIdleClosePolicy.ONE_DAY,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val autoConnectPaused: Boolean = false,
) {
    init { require(name.isNotBlank() && name.none { it.isISOControl() }); require(createdAtMillis >= 0) }
    fun connectsLike(other: SshHostRecord) = id == other.id && endpoint == other.endpoint &&
        keyId == other.keyId && jumpHostId == other.jumpHostId
}

/** Canonical public material only. Private key bytes never belong in this store. */
internal class SshHostKey private constructor(val openSsh: String) {
    override fun equals(other: Any?) = other is SshHostKey && openSsh == other.openSsh
    override fun hashCode() = openSsh.hashCode()
    val algorithm: String get() = openSsh.substringBefore(' ')
    val sha256Fingerprint: String get() = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(openSsh.substringAfter(' '))))
    companion object {
        fun parse(line: String): SshHostKey {
            require(line.length <= 32768 && line.none { it == '\r' || it == '\n' || it == '\u0000' })
            val parts = line.trim().split(Regex("[ \\t]+"), limit = 3)
            require(parts.size >= 2 && parts[0].matches(Regex("[A-Za-z0-9][A-Za-z0-9@._+-]*")))
            val blob = Base64.getDecoder().decode(parts[1])
            require(blob.size >= 5)
            val size = ByteBuffer.wrap(blob, 0, 4).int
            require(size in 1..(blob.size - 4))
            require(blob.copyOfRange(4, 4 + size).contentEquals(parts[0].toByteArray(Charsets.US_ASCII)))
            require(blob.size > size + 4) { "Missing host public key material" }
            return SshHostKey(parts[0] + " " + Base64.getEncoder().encodeToString(blob))
        }
    }
}

internal enum class SshHostTrustVerdict { UNKNOWN, TRUSTED, CHANGED }
internal data class SshTrustSnapshot internal constructor(
    val storeId: UUID, val identity: String, val pinned: SshHostKey?, val revision: Long,
) {
    fun verdict(presented: SshHostKey) = when {
        pinned == null -> SshHostTrustVerdict.UNKNOWN
        pinned == presented -> SshHostTrustVerdict.TRUSTED
        else -> SshHostTrustVerdict.CHANGED
    }
}

internal data class SshDialHop internal constructor(
    val hostId: UUID, val endpoint: SshEndpoint, val keyId: UUID?, val revision: Long,
)
/** Ordered from outer jump to target; revisions prevent A→B→A from reviving a dial. */
internal data class SshDialPlan internal constructor(val storeId: UUID, val hops: List<SshDialHop>)
