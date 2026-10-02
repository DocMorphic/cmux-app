package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Shared sizing wire contract from cmux 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc. */
internal data class SharedTerminalGrid(val columns: Int, val rows: Int) {
    init { require(columns > 0 && rows > 0) }
    fun wire() = JSONObject().put("cols", columns).put("rows", rows)
    companion object {
        fun decode(value: JSONObject) = SharedTerminalGrid(value.getInt("cols"), value.getInt("rows"))
    }
}

internal enum class TerminalSizeMode(val wire: String, val title: String) {
    SMALLEST("smallest", "Fit everyone"), LATEST("latest", "Latest activity"),
    LARGEST("largest", "Largest viewport"), PRIORITY("priority", "Priority"), FIXED("fixed", "Fixed size")
}

internal data class TerminalSizePolicy(
    val mode: TerminalSizeMode,
    val priority: List<String> = emptyList(),
    val fixed: SharedTerminalGrid? = null
) {
    fun wire() = JSONObject().put("mode", mode.wire).put("priority", org.json.JSONArray(priority))
        .put("fixed", fixed?.wire() ?: JSONObject.NULL)
    companion object {
        fun decode(value: JSONObject): TerminalSizePolicy {
            val mode = TerminalSizeMode.entries.single { it.wire == value.getString("mode") }
            val priority = value.getJSONArray("priority").let { array ->
                require(array.length() <= 1024)
                (0 until array.length()).map { array.getString(it).also { key -> require(key.isNotBlank() && key.length <= 1024) } }
            }
            val fixed = value.optJSONObject("fixed")?.let(SharedTerminalGrid::decode)
            require(mode != TerminalSizeMode.FIXED || fixed != null)
            return TerminalSizePolicy(mode, priority, fixed)
        }
    }
}

internal data class TerminalSizeParticipant(
    val id: String, val userId: String?, val displayName: String, val deviceKind: String,
    val deviceName: String?, val via: String?, val viewport: SharedTerminalGrid?,
    val countsOverride: Boolean?, val counts: Boolean, val priorityKey: String
)

internal data class TerminalSizeState(
    val generation: Long, val grid: SharedTerminalGrid, val reason: String,
    val owners: List<String>, val policy: TerminalSizePolicy, val participants: List<TerminalSizeParticipant>
) {
    companion object {
        fun decode(value: JSONObject): TerminalSizeState {
            val generation = value.getLong("generation").also { require(it >= 0) }
            val array = value.getJSONArray("participants")
            require(array.length() <= 1024)
            val participants = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                TerminalSizeParticipant(item.text("id"), item.optionalText("user_id"), item.text("display_name", allowBlank = true),
                    item.text("device_kind"), item.optionalText("device_name"), item.optionalText("via"),
                    item.optJSONObject("viewport")?.let(SharedTerminalGrid::decode),
                    if (!item.has("counts_override") || item.isNull("counts_override")) null else item.getBoolean("counts_override"),
                    item.getBoolean("counts"), item.text("priority_key"))
            }
            require(participants.map { it.id }.distinct().size == participants.size)
            val owners = value.getJSONArray("owners").let { ids ->
                require(ids.length() <= 1024)
                (0 until ids.length()).map { ids.getString(it) }
            }
            require(owners.distinct().size == owners.size && owners.all { owner -> participants.any { it.id == owner } })
            return TerminalSizeState(generation, SharedTerminalGrid.decode(value), value.text("reason"), owners,
                TerminalSizePolicy.decode(value.getJSONObject("policy")), participants)
        }
    }
}

internal data class TerminalDetach(val reason: String, val at: String?, val byName: String?, val byDevice: String?) {
    val reconnectsAutomatically get() = reason == "network"
    companion object {
        fun decode(value: JSONObject): TerminalDetach {
            val actor = value.optJSONObject("by")
            return TerminalDetach(value.text("reason"), value.optionalText("at"),
                actor?.optionalText("display_name"), actor?.optionalText("device_name"))
        }
    }
}

internal enum class TerminalSizingEffect { NONE, RECONNECT, REASSERT_VIEWPORT }

/** Per owner/terminal, retained across connection replacement; explicit detach never auto-reattaches. */
internal class TerminalSizingSurface {
    var state: TerminalSizeState? = null; private set
    var selfParticipantId: String? = null; private set
    var detached: TerminalDetach? = null; private set
    var reconnecting = false; private set
    var viewportRevision = 0L; private set
    val allowsTraffic get() = detached == null

    fun apply(next: TerminalSizeState, selfId: String?, rendered: SharedTerminalGrid?): TerminalSizingEffect {
        val sameParticipant = selfId == null || selfId == selfParticipantId
        if (sameParticipant && state?.let { next.generation < it.generation } == true) return TerminalSizingEffect.NONE
        val previous = state?.grid
        state = next
        if (selfId != null) selfParticipantId = selfId
        reconnecting = false
        if (allowsTraffic && previous != next.grid && rendered != null && rendered != next.grid) {
            viewportRevision++
            return TerminalSizingEffect.REASSERT_VIEWPORT
        }
        return TerminalSizingEffect.NONE
    }

    fun detach(event: TerminalDetach): TerminalSizingEffect {
        if (event.reconnectsAutomatically) {
            if (!allowsTraffic) return TerminalSizingEffect.NONE
            reconnecting = true
            return TerminalSizingEffect.RECONNECT
        }
        detached = event
        reconnecting = false
        return TerminalSizingEffect.NONE
    }

    fun connectionEnded() { state = null; selfParticipantId = null }
    fun recoveredFromNetwork() { reconnecting = false }
    fun reattached(next: TerminalSizeState?, selfId: String?) {
        detached = null; reconnecting = false
        if (next != null) state = next
        if (selfId != null) selfParticipantId = selfId
        viewportRevision++
    }
}

private fun JSONObject.text(name: String, allowBlank: Boolean = false): String = getString(name).also {
    require((allowBlank || it.isNotBlank()) && it.length <= 4096)
}
private fun JSONObject.optionalText(name: String): String? =
    if (!has(name) || isNull(name)) null else text(name, allowBlank = true)
