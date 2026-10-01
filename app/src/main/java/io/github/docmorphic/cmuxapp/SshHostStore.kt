package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Collections
import java.util.UUID

internal data class SshHostState(
    val hosts: List<SshHostRecord> = emptyList(),
    val pinnedKeys: Map<String, SshHostKey> = emptyMap(),
    val lastUsedHostId: UUID? = null,
) {
    fun host(id: UUID) = hosts.firstOrNull { it.id == id }
}

/** One phone-local metadata store. All mutations publish only after durable write.
 * Corrupt/unreadable data throws: it must not silently discard existing trust. */
internal class SshHostStore(private val read: () -> String?, private val write: (String) -> Unit) {
    private val storeId = UUID.randomUUID()
    private var revision = 0L
    private val hostRevisions = mutableMapOf<UUID, Long>()
    private val trustRevisions = mutableMapOf<String, Long>()
    private data class TrustApproval(val question: SshTrustSnapshot, val key: SshHostKey, val revision: Long)
    private val trustApprovals = mutableMapOf<String, TrustApproval>()
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    @Synchronized fun upsert(host: SshHostRecord) {
        val before = mutable.value
        val old = before.host(host.id)
        val next = before.copy(hosts = (before.hosts.filterNot { it.id == host.id } + host)
            .sortedWith(compareBy<SshHostRecord> { it.createdAtMillis }.thenBy { it.id.toString() }))
        val changed = old == null || !old.connectsLike(host) || old.autoConnectPaused != host.autoConnectPaused
        commit(next, changedHosts = if (changed) listOf(host.id) else emptyList())
    }

    @Synchronized fun delete(id: UUID) {
        val before = mutable.value
        if (before.host(id) == null) return
        val changed = before.hosts.filter { it.jumpHostId == id }.map { it.id } + id
        commit(before.copy(
            hosts = before.hosts.filterNot { it.id == id }.map {
                if (it.jumpHostId == id) it.copy(jumpHostId = null) else it
            }, lastUsedHostId = before.lastUsedHostId?.takeUnless { it == id }), changedHosts = changed)
        // Pins describe endpoints, not host rows; another saved login may use one.
    }

    @Synchronized fun removeKeyReferences(keyId: UUID) {
        val before = mutable.value
        val changed = before.hosts.filter { it.keyId == keyId }.map { it.id }
        if (changed.isEmpty()) return
        commit(before.copy(hosts = before.hosts.map {
            if (it.keyId == keyId) it.copy(keyId = null) else it
        }), changedHosts = changed)
    }

    @Synchronized fun markUsed(id: UUID) {
        val before = mutable.value
        if (before.host(id) != null && before.lastUsedHostId != id) commit(before.copy(lastUsedHostId = id))
    }

    @Synchronized fun dialPlan(id: UUID): SshDialPlan {
        val hops = mutableListOf<SshDialHop>()
        val hosts = mutable.value.hosts.associateBy { it.id }
        var current: UUID? = id
        val seen = mutableSetOf<UUID>()
        while (current != null) {
            check(seen.add(current)) { "SSH jump hosts contain a cycle" }
            val host = checkNotNull(hosts[current]) { "SSH computer is no longer saved" }
            hops += SshDialHop(host.id, host.endpoint, host.keyId, hostRevisions[host.id] ?: 0)
            current = host.jumpHostId
        }
        return SshDialPlan(storeId, Collections.unmodifiableList(hops.asReversed().toList()))
    }

    @Synchronized fun isCurrent(plan: SshDialPlan): Boolean = plan.storeId == storeId &&
        plan.hops.lastOrNull()?.let { runCatching { dialPlan(it.hostId) == plan }.getOrDefault(false) } == true

    @Synchronized fun mayAutoConnect(plan: SshDialPlan): Boolean = isCurrent(plan) &&
        plan.hops.all { mutable.value.host(it.hostId)?.autoConnectPaused == false }

    /** Stale disconnect/prompt callbacks cannot pause an edited or replaced route. */
    @Synchronized fun setAutoConnectPaused(plan: SshDialPlan, hostId: UUID, paused: Boolean): Boolean {
        if (!isCurrent(plan) || plan.hops.none { it.hostId == hostId }) return false
        val host = checkNotNull(mutable.value.host(hostId))
        if (host.autoConnectPaused != paused) upsert(host.copy(autoConnectPaused = paused))
        return true
    }

    @Synchronized fun trustSnapshot(endpoint: SshEndpoint) = SshTrustSnapshot(
        storeId, endpoint.hostKeyIdentity, mutable.value.pinnedKeys[endpoint.hostKeyIdentity],
        trustRevisions[endpoint.hostKeyIdentity] ?: 0,
    )

    /** Commit only the exact identity question the current route asked. */
    @Synchronized fun confirmHostKey(plan: SshDialPlan, hostId: UUID,
        question: SshTrustSnapshot, presented: SshHostKey): Boolean {
        if (!isCurrent(plan)) return false
        val hop = plan.hops.firstOrNull { it.hostId == hostId } ?: return false
        val current = trustSnapshot(hop.endpoint)
        if (question != current) {
            // Coalesced prompt waiters may commit the same decision. It is
            // reusable only until the very next pin mutation, including A→B→A.
            val approval = trustApprovals[hop.endpoint.hostKeyIdentity]
            return approval?.question == question && approval.key == presented &&
                approval.revision == current.revision && current.pinned == presented
        }
        if (question.pinned == presented) return true
        commit(mutable.value.copy(pinnedKeys = mutable.value.pinnedKeys + (question.identity to presented)),
            changedTrust = question.identity, approvedQuestion = question)
        return true
    }

    @Synchronized fun forgetHostKey(question: SshTrustSnapshot): Boolean {
        if (question.storeId != storeId || mutable.value.pinnedKeys[question.identity] != question.pinned ||
            (trustRevisions[question.identity] ?: 0) != question.revision) return false
        if (question.pinned == null) return true
        commit(mutable.value.copy(pinnedKeys = mutable.value.pinnedKeys - question.identity), changedTrust = question.identity)
        return true
    }

    private fun commit(next: SshHostState, changedHosts: List<UUID> = emptyList(), changedTrust: String? = null,
        approvedQuestion: SshTrustSnapshot? = null) {
        validate(next)
        val encoded = encode(next)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        write(encoded)
        changedHosts.forEach { hostRevisions[it] = ++revision }
        if (changedTrust != null) {
            trustRevisions[changedTrust] = ++revision
            trustApprovals.remove(changedTrust)
            if (approvedQuestion != null) trustApprovals[changedTrust] = TrustApproval(
                approvedQuestion, checkNotNull(next.pinnedKeys[changedTrust]), revision)
        }
        mutable.value = freeze(next)
    }

    private fun load(): SshHostState = try {
        val text = read()
        if (text == null) SshHostState() else {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val json = JSONObject(text)
            require(json.get("version") == 1) { "Unsupported SSH metadata version" }
            val rows = json.getJSONArray("hosts")
            val hosts = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val endpoint = row.getJSONObject("endpoint")
                SshHostRecord(
                    id = uuid(row.get("id") as String), name = row.get("name") as String,
                    endpoint = SshEndpoint(endpoint.get("host") as String,
                        (endpoint.get("port") as Int), endpoint.get("username") as String),
                    keyId = optionalUuid(row, "keyId"), jumpHostId = optionalUuid(row, "jumpHostId"),
                    // The upstream legacy persistence mode is deliberately ignored.
                    idleClose = runCatching { SshIdleClosePolicy.valueOf(row.get("idleClose") as String) }
                        .getOrDefault(SshIdleClosePolicy.ONE_DAY),
                    createdAtMillis = if (!row.has("createdAtMillis")) 0 else integer(row.get("createdAtMillis")),
                    autoConnectPaused = if (!row.has("autoConnectPaused")) false else row.get("autoConnectPaused") as Boolean,
                )
            }
            val keys = json.getJSONObject("pinnedKeys")
            val pins = keys.keys().asSequence().associateWith { SshHostKey.parse(keys.get(it) as String) }
            freeze(SshHostState(hosts, pins, optionalUuid(json, "lastUsedHostId")).also(::validate))
        }
    } catch (failure: Exception) {
        throw IOException("Could not read saved SSH computers and server identities", failure)
    }

    private fun validate(value: SshHostState) {
        val hosts = value.hosts.associateBy { it.id }
        require(hosts.size == value.hosts.size) { "Duplicate SSH computer" }
        require(value.lastUsedHostId == null || value.lastUsedHostId in hosts)
        val complete = mutableSetOf<UUID>()
        value.hosts.forEach { host ->
            val seen = mutableSetOf<UUID>()
            var jump: UUID? = host.id
            while (jump != null && jump !in complete) {
                require(seen.add(jump)) { "SSH jump hosts contain a cycle" }
                val next = requireNotNull(hosts[jump]) { "SSH jump host is missing" }
                jump = next.jumpHostId
            }
            complete += seen
        }
        value.pinnedKeys.keys.forEach { require(it.isNotBlank() && it.none { c -> c.isWhitespace() || c.isISOControl() }) }
    }

    private fun encode(value: SshHostState): String = JSONObject().put("version", 1)
        .put("hosts", JSONArray().apply { value.hosts.forEach { host -> put(JSONObject()
            .put("id", host.id.toString()).put("name", host.name)
            .put("endpoint", JSONObject().put("host", host.endpoint.host).put("port", host.endpoint.port)
                .put("username", host.endpoint.username))
            .put("keyId", host.keyId?.toString() ?: JSONObject.NULL)
            .put("jumpHostId", host.jumpHostId?.toString() ?: JSONObject.NULL)
            .put("idleClose", host.idleClose.name).put("createdAtMillis", host.createdAtMillis)
            .put("autoConnectPaused", host.autoConnectPaused)) } })
        .put("pinnedKeys", JSONObject().apply { value.pinnedKeys.forEach { (identity, key) -> put(identity, key.openSsh) } })
        .put("lastUsedHostId", value.lastUsedHostId?.toString() ?: JSONObject.NULL).toString()

    private fun freeze(value: SshHostState) = value.copy(
        hosts = Collections.unmodifiableList(value.hosts.toList()),
        pinnedKeys = Collections.unmodifiableMap(value.pinnedKeys.toMap()))

    private fun optionalUuid(json: JSONObject, key: String): UUID? =
        if (!json.has(key) || json.isNull(key)) null else uuid(json.get(key) as String)

    private fun uuid(text: String): UUID = UUID.fromString(text).also { require(it.toString().equals(text, ignoreCase = true)) }
    private fun integer(value: Any): Long = when (value) { is Int -> value.toLong(); is Long -> value; else -> error("Expected integer") }

    companion object { const val MAX_BYTES = 4 * 1024 * 1024 }
}
