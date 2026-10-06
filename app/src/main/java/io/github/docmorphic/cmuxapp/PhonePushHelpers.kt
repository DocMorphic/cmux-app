package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class PhonePushHelperBinding(
    val peer: PhonePushPeer, val macPeer: PhonePushPeer, val macEpoch: String,
    val project: PhoneFcmProject, val epoch: String
)

/** Separate explicitly enrolled helper senders. Never overwrites the native Mac exchange pin. */
internal class PhonePushHelperState(private val state: JSONObject) {
    private fun rows() = state.optJSONArray(KEY)?.let { array ->
        (0 until minOf(array.length(), 128)).mapNotNull(array::optJSONObject)
    }.orEmpty()
    private fun team(row: JSONObject) = NativeTeamScope(row.getString("login"), row.getString("user"), row.getString("team"), 0)
    private fun decode(row: JSONObject) = PhonePushHelperBinding(
        PhonePushPeer.parse(row.getJSONObject("peer")), PhonePushPeer.parse(row.getJSONObject("mac_peer")),
        row.getString("mac_epoch"), PhoneFcmProject.parse(row.getJSONObject("project")), row.getString("epoch")
    )
    private fun admitted(row: JSONObject): Pair<String, PhonePushHelperBinding>? = runCatching {
        val team = team(row); val keys = PhonePushKeyState(state)
        val origin = keys.canonicalOrigin(team, row.getString("origin")) ?: return@runCatching null
        val binding = decode(row)
        if (keys.peer(team, origin) != binding.macPeer || keys.peerEpoch(team, origin) != binding.macEpoch ||
            binding.peer.tuple != binding.macPeer.tuple || binding.epoch.isBlank() || binding.epoch.length > 128 ||
            binding.macEpoch.isBlank() || !distinct(binding.peer.descriptor, binding.macPeer.descriptor)) return@runCatching null
        origin to binding
    }.getOrNull()
    fun prune() {
        val valid = rows().mapNotNull { row -> admitted(row)?.let { JSONObject(row.toString()).put("origin", it.first) } }
        // Alias collisions require re-enrollment instead of choosing an arbitrary helper.
        save(valid.groupBy { it.getString("origin") }.values.filter { it.size == 1 }.map { it.single() })
    }
    private fun save(rows: List<JSONObject>) {
        if (rows.isEmpty()) state.remove(KEY) else state.put(KEY, JSONArray(rows))
    }
    fun binding(team: NativeTeamScope, origin: String): PhonePushHelperBinding? {
        prune()
        val canonical = PhonePushKeyState(state).canonicalOrigin(team, origin) ?: return null
        return rows().singleOrNull { it.getString("origin") == canonical && this.team(it).let { owner ->
            owner.login == team.login && owner.userId == team.userId && owner.teamId == team.teamId
        } }?.let(::decode)
    }
    /** Caller completes authenticated helper proof/explicit confirmation first, retaining this exact native pin. */
    fun pin(team: NativeTeamScope, origin: String, expectedMac: PhonePushPeer, expectedMacEpoch: String,
        helper: PhonePushDescriptor, project: PhoneFcmProject): PhonePushHelperBinding {
        val keys = PhonePushKeyState(state)
        val canonical = checkNotNull(keys.canonicalOrigin(team, origin)) { "Push computer is no longer admitted" }
        check(keys.peer(team, canonical) == expectedMac && keys.peerEpoch(team, canonical) == expectedMacEpoch) {
            "Computer identity changed during helper enrollment"
        }
        PhonePushDescriptor.parse(helper.wire())
        require(distinct(helper, expectedMac.descriptor)) { "Helper must have an independent sender identity" }
        val peer = PhonePushPeer(expectedMac.tuple, helper)
        val previous = binding(team, canonical)
        val epoch = previous?.takeIf { it.peer == peer && it.macPeer == expectedMac &&
            it.macEpoch == expectedMacEpoch && it.project == project }?.epoch ?: UUID.randomUUID().toString()
        val retained = rows().filterNot { it.getString("origin") == canonical }
        check(retained.size < 128) { "Too many push helpers" }
        save(retained + JSONObject().put("origin", canonical).put("login", team.login).put("user", team.userId).put("team", team.teamId)
            .put("peer", peer.wire()).put("mac_peer", expectedMac.wire()).put("mac_epoch", expectedMacEpoch)
            .put("project", project.json()).put("epoch", epoch))
        return checkNotNull(binding(team, canonical))
    }
    fun forget(team: NativeTeamScope, origin: String) {
        val canonical = PhonePushKeyState(state).canonicalOrigin(team, origin) ?: return
        prune(); save(rows().filterNot { it.getString("origin") == canonical && this.team(it).let { owner ->
            owner.login == team.login && owner.userId == team.userId && owner.teamId == team.teamId
        } })
    }
    fun retainForToken(owner: PhoneFcmTokenGrant?) {
        if (owner == null) { clear(); return }
        prune()
        save(rows().filter { row -> row.getString("login") == owner.login &&
            PhoneFcmProject.parse(row.getJSONObject("project")) == owner.project })
    }
    fun clear() { state.remove(KEY) }
    companion object {
        const val KEY = "phone_push_helpers"
        private fun distinct(a: PhonePushDescriptor, b: PhonePushDescriptor) =
            a.keyID != b.keyID && a.installationID != b.installationID && a.publicKey != b.publicKey
    }
}
