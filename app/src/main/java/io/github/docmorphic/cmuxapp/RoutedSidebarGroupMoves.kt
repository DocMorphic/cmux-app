package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

/** A display-only, order-bound group menu. IDs are salted host-issued keys. */
internal data class RoutedSidebarGroupChoice(val key: String, val name: String, val icon: String?,
    val current: Boolean, val enabled: Boolean)
internal data class RoutedSidebarGroupPage(val revision: String, val offset: Int, val next: Int?, val total: Int,
    val choices: List<RoutedSidebarGroupChoice>, val canRemove: Boolean) {
    fun menu() = NativeWorkspaceGroupMoveMenu(choices.map {
        NativeWorkspaceGroupMoveMenu.Entry(NativeGroup(it.key, it.name, false, false, iconSymbol = it.icon), it.current, it.enabled)
    }, canRemove)
}
internal object RoutedSidebarGroupWire {
    private fun token(value: String) = value.also { require(it.length in 1..128 && it.none(Char::isISOControl)) }
    fun encode(page: RoutedSidebarGroupPage): String = JSONObject().put("revision", token(page.revision))
        .put("offset", page.offset).put("next", page.next ?: JSONObject.NULL).put("total", page.total)
        .put("remove", page.canRemove).put("choices", JSONArray().also { array -> page.choices.forEach {
            array.put(JSONObject().put("key", token(it.key)).put("name", NativeSearchText.prefix(it.name, 1024))
                .put("icon", it.icon?.let { symbol -> NativeSearchText.prefix(symbol, 128) })
                .put("current", it.current).put("enabled", it.enabled))
        } }).toString().also { require(it.toByteArray(Charsets.UTF_8).size <= RoutedSidebarWire.MAX_BYTES) }
    fun decode(value: String): RoutedSidebarGroupPage {
        require(value.toByteArray(Charsets.UTF_8).size <= RoutedSidebarWire.MAX_BYTES)
        val json = JSONObject(value)
        val array = json.getJSONArray("choices")
        require(array.length() <= 100)
        val page = RoutedSidebarGroupPage(token(json.getString("revision")), json.getInt("offset"),
            if (json.isNull("next")) null else json.getInt("next"), json.getInt("total"),
            (0 until array.length()).map { index -> array.getJSONObject(index).let {
                RoutedSidebarGroupChoice(token(it.getString("key")), NativeSearchText.prefix(it.getString("name"), 1024),
                    if (it.isNull("icon")) null else NativeSearchText.prefix(it.getString("icon"), 128),
                    it.getBoolean("current"), it.getBoolean("enabled"))
            } }, json.getBoolean("remove"))
        require(page.total in 0..RoutedSidebarWire.MAX_ROWS && page.offset in 0..page.total)
        val end = page.offset + page.choices.size
        require(end <= page.total && (page.next == null && end == page.total || page.next == end && end < page.total && end > page.offset))
        require(page.choices.map { it.key }.distinct().size == page.choices.size)
        require(page.choices.none { it.current && it.enabled })
        return page
    }
    fun page(revision: String, choices: List<RoutedSidebarGroupChoice>, canRemove: Boolean, offset: Int): RoutedSidebarGroupPage {
        require(choices.size <= RoutedSidebarWire.MAX_ROWS && offset in 0..choices.size)
        var count = minOf(100, choices.size - offset)
        while (true) {
            val page = RoutedSidebarGroupPage(revision, offset, (offset + count).takeIf { it < choices.size },
                choices.size, choices.subList(offset, offset + count), canRemove)
            if (runCatching { encode(page) }.isSuccess) return page
            check(count > 1) { "Group menu entry is too large." }
            count /= 2
        }
    }
}

/** Destination authority is issued only for pages sent to this browser presentation. */
internal class RoutedSidebarGroupExchange {
    private var workspace: String? = null
    private var revision: String? = null
    private var next: Int? = null
    private var choices = emptySet<String>()
    private var remove = false
    fun issue(key: String, page: RoutedSidebarGroupPage) {
        if (page.offset == 0) { workspace = key; revision = page.revision; choices = emptySet(); remove = page.canRemove }
        else check(key == workspace && page.revision == revision && page.offset == next && page.canRemove == remove) {
            "Group menu changed. Reopen Move to Group."
        }
        choices = choices + page.choices.filter { it.enabled }.map { it.key }
        next = page.next
    }
    fun permits(command: RoutedSidebarMutation) = command.kind == RoutedSidebarMutationKind.MOVE_TO_GROUP &&
        command.key == workspace && command.menuRevision == revision &&
        (if (command.destination == null) remove else command.destination in choices)
}
