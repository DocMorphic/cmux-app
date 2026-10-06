package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

/** Only display values and opaque destinations cross into the browser process. */
internal data class RoutedSidebarCreateOption(val key: String, val kind: SshWorkspaceKind? = null,
    val unavailableReason: String? = null)
internal data class RoutedSidebarCreateComputer(val key: String, val name: String, val build: String? = null,
    val availability: NativeFeedAvailability = NativeFeedAvailability.OFFLINE,
    val options: List<RoutedSidebarCreateOption>) {
    val enabled get() = options.any { it.unavailableReason == null }
}
internal data class NativeSidebarCreation(val busy: Boolean = false, val ssh: List<NativeSshCreateTarget> = emptyList(),
    val foregroundMac: NativeCredentialStore.PairedMac? = null, val cloud: List<CloudWorkspaceSnapshot> = emptyList())

internal object RoutedSidebarCreationWire {
    private fun token(value: String) = value.also { require(it.length in 1..128 && it.none(Char::isISOControl)) }
    fun encode(values: List<RoutedSidebarCreateComputer>) = JSONArray().also { array ->
        require(values.size <= 256)
        values.forEach { value -> array.put(JSONObject().put("key", token(value.key))
            .put("name", NativeSearchText.prefix(value.name, 64)).put("build", value.build?.let { NativeSearchText.prefix(it, 64) })
            .put("availability", value.availability.name).put("options", JSONArray().also { options ->
                require(value.options.size in 1..3)
                value.options.forEach { option -> options.put(JSONObject().put("key", token(option.key))
                    .put("kind", option.kind?.name).put("reason", option.unavailableReason?.let { NativeSearchText.prefix(it, 256) })) }
            })) }
    }
    fun decode(array: JSONArray): List<RoutedSidebarCreateComputer> {
        require(array.length() <= 256)
        return (0 until array.length()).map { index -> array.getJSONObject(index).let { value ->
            val options = value.getJSONArray("options")
            require(options.length() in 1..3)
            RoutedSidebarCreateComputer(token(value.getString("key")), NativeSearchText.prefix(value.getString("name"), 64),
                if (value.isNull("build")) null else NativeSearchText.prefix(value.getString("build"), 64),
                NativeFeedAvailability.valueOf(value.getString("availability")),
                (0 until options.length()).map { options.getJSONObject(it).let { option ->
                    RoutedSidebarCreateOption(token(option.getString("key")),
                        if (option.isNull("kind")) null else SshWorkspaceKind.valueOf(option.getString("kind")),
                        if (option.isNull("reason")) null else NativeSearchText.prefix(option.getString("reason"), 256))
                } }.also { rows ->
                    require(rows.map { it.kind }.distinct().size == rows.size)
                    require(rows.size == 1 || rows.all { it.kind != null })
                })
        } }.also { values ->
            require(values.map { it.key }.distinct().size == values.size)
            val keys = values.flatMap { it.options }.map { it.key }
            require(keys.distinct().size == keys.size)
        }
    }
}
