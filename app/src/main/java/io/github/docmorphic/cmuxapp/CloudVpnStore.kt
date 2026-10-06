package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class CloudVpnOwner(val user: String, val team: String?) {
    init { require(user.isNotBlank() && user.length <= 4096 && (team == null || team.isNotBlank() && team.length <= 4096)) }
    override fun toString() = "CloudVpnOwner(redacted)"
}
internal data class CloudVpnRevocation(val attempt: String, val owner: CloudVpnOwner, val fingerprint: String) {
    init { require(UUID.fromString(attempt).toString() == attempt && fingerprint.isNotBlank() && fingerprint.length <= 4096) }
    override fun toString() = "CloudVpnRevocation(redacted)"
}
internal class CloudVpnProfile(val enrollment: CloudVpnRevocation, val configuration: String,
    val requested: Boolean = true, val session: String? = null) {
    init {
        require(CloudVpnRoutePolicy.permitsConfiguration(configuration)) { "Invalid saved Cloud VPN routes" }
        require(session == null || session.isNotBlank() && session.length <= 4096)
    }
    override fun toString() = "CloudVpnProfile(redacted)"
}
internal class CloudVpnSavedState(val pending: List<CloudVpnRevocation>, val profile: CloudVpnProfile?) {
    override fun toString() = "CloudVpnSavedState(pending=${pending.size}, profile=${profile != null})"
}

/** Encrypted, no-backup profile and write-ahead browser-peer cleanup journal. Call from IO.
 * Pending entries contain identities only; credentials are obtained for their verified owner at cleanup time.
 * The caller must stop a live platform VPN before retiring its saved profile or acknowledging cleanup. */
internal class CloudVpnStore(private val root: File, private val cipher: CloudIdentityCipher,
    private val replace: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); Unit
    }, private val capacity: Int = 4096) {
    init { require(capacity in 1..4096) }
    private val file = File(root, "state.enc")
    fun load(): CloudVpnSavedState = locked { read() }

    /** Persist before POST enrollment; an interrupted/unknown response still leaves a cleanup identity. */
    fun begin(owner: CloudVpnOwner, fingerprint: String): CloudVpnRevocation = locked {
        val state = read()
        check(state.profile == null) { "Stop the saved Cloud VPN before enrolling another peer" }
        check(state.pending.size < capacity) { "Cloud VPN cleanup is required before enrollment" }
        check(state.pending.none { it.owner == owner && it.fingerprint == fingerprint }) { "Clean up the previous Cloud VPN peer before enrollment" }
        val entry = CloudVpnRevocation(UUID.randomUUID().toString(), owner, fingerprint)
        write(CloudVpnSavedState(state.pending + entry, null))
        entry
    }

    /** The pending cleanup record stays durable for as long as this profile might be installed. */
    fun install(entry: CloudVpnRevocation, configuration: String, session: String? = null) = locked {
        val state = read()
        check(entry in state.pending) { "Cloud VPN enrollment was retired" }
        check(state.profile == null || state.profile.enrollment == entry) { "Another Cloud VPN profile is saved" }
        check(state.profile?.requested != false) { "Cloud VPN stop was already requested" }
        write(CloudVpnSavedState(state.pending, CloudVpnProfile(entry, configuration, session = session)))
    }

    /** A stale completion may neither erase a replacement profile nor acknowledge its peer. */
    fun retireProfile(attempt: String) = locked {
        val state = read()
        if (state.profile?.enrollment?.attempt == attempt) write(CloudVpnSavedState(state.pending, null))
    }
    /** Write the user's stop intent before platform shutdown; keep the cleanup identity
     * and configuration until stop succeeds. Recovery must never restart this profile. */
    fun requestStop() = locked {
        val state = read()
        state.profile?.takeIf { it.requested }?.let {
            write(CloudVpnSavedState(state.pending, CloudVpnProfile(it.enrollment, it.configuration, requested = false, session = it.session)))
        }
    }
    fun acknowledge(entry: CloudVpnRevocation) = locked {
        val state = read()
        check(state.profile?.enrollment != entry) { "Stop and retire the Cloud VPN profile before acknowledging cleanup" }
        if (entry in state.pending) write(CloudVpnSavedState(state.pending - entry, state.profile))
    }

    private fun read(): CloudVpnSavedState {
        val sealed = try { Files.newInputStream(file.toPath()).use { input ->
            val bytes = ByteArray(MAX_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val next = input.read(bytes, count, bytes.size - count)
                if (next < 0) break
                count += next
            }
            bytes.copyOf(count).also { bytes.fill(0) }
        } } catch (_: NoSuchFileException) { return CloudVpnSavedState(emptyList(), null) }
        require(sealed.size in 1..MAX_BYTES) { "Invalid saved Cloud VPN state" }
        val plain = cipher.decrypt(sealed)
        try {
            require(plain.size in 1..MAX_BYTES) { "Invalid saved Cloud VPN state" }
            val value = JSONObject(plain.toString(Charsets.UTF_8))
            require(value.getInt("version") == 1 && value.getString("origin") == "https://cmux.com") { "Invalid saved Cloud VPN state" }
            val items = value.getJSONArray("pending")
            require(items.length() <= capacity) { "Invalid saved Cloud VPN state" }
            val pending = (0 until items.length()).map { i ->
                val item = items.getJSONObject(i)
                CloudVpnRevocation(item.getString("attempt"), CloudVpnOwner(item.getString("user"),
                    if (item.isNull("team")) null else item.getString("team")), item.getString("fingerprint"))
            }
            require(pending.map { it.attempt }.distinct().size == pending.size &&
                pending.map { it.owner to it.fingerprint }.distinct().size == pending.size) { "Invalid saved Cloud VPN state" }
            val profile = if (value.isNull("profile")) null else value.getJSONObject("profile").let { item ->
                CloudVpnProfile(checkNotNull(pending.singleOrNull { it.attempt == item.getString("attempt") }) {
                    "Cloud VPN cleanup identity is missing"
                }, item.getString("configuration"), if (item.has("requested")) item.getBoolean("requested") else true,
                    if (item.isNull("session")) null else item.getString("session"))
            }
            return CloudVpnSavedState(pending, profile)
        } finally { plain.fill(0); sealed.fill(0) }
    }
    private fun write(state: CloudVpnSavedState) {
        val value = JSONObject().put("version", 1).put("origin", "https://cmux.com").put("pending", JSONArray(state.pending.map {
            JSONObject().put("attempt", it.attempt).put("user", it.owner.user).put("team", it.owner.team ?: JSONObject.NULL)
                .put("fingerprint", it.fingerprint)
        })).put("profile", state.profile?.let {
            JSONObject().put("attempt", it.enrollment.attempt).put("configuration", it.configuration)
                .put("requested", it.requested).put("session", it.session ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
        val plain = value.toString().toByteArray(Charsets.UTF_8)
        val sealed = try { require(plain.size <= MAX_BYTES); cipher.encrypt(plain) } finally { plain.fill(0) }
        try {
            require(sealed.size in 1..MAX_BYTES) { "Cloud VPN state is too large" }
            val temporary = Files.createTempFile(root.toPath(), "vpn-", ".tmp")
            try {
                FileOutputStream(temporary.toFile()).use { it.write(sealed); it.fd.sync() }
                replace(temporary, file.toPath())
            } finally { Files.deleteIfExists(temporary) }
        } finally { sealed.fill(0) }
    }
    private fun <T> locked(action: () -> T): T = synchronized(lock) {
        check(root.isDirectory || root.mkdirs()) { "Cloud VPN storage is unavailable" }
        RandomAccessFile(File(root, "state.lock"), "rw").use { file -> file.channel.lock().use { action() } }
    }
    companion object { private val lock = Any(); private const val MAX_BYTES = 4 * 1024 * 1024 }
}
