package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.staticCompositionLocalOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Display-only data. Pairings, SSH records, tokens, filesystem authorities and RPC clients never cross IPC. */
internal data class RoutedSidebarRow(
    val key: String, val kind: String, val title: String,
    val subtitle: String? = null, val preview: String? = null, val computer: String? = null,
    val unread: Boolean = false, val count: Long? = null, val pinned: Boolean = false,
    val color: String? = null, val activity: Double? = null, val previewAt: Double? = null,
    val availability: NativeFeedAvailability = NativeFeedAvailability.CONNECTED,
    val depth: Int = 0, val expanded: Boolean = false, val canOpen: Boolean = true, val iconSymbol: String? = null,
) {
    fun workspace() = NativeWorkspace(key, title, emptyList(), null, unread, activity, null, pinned,
        emptyList(), null, preview, color, subtitle, count, previewAt = previewAt)
}
internal data class RoutedSidebarComputer(val key: String, val name: String, val build: String? = null)
internal data class RoutedSidebarQuery(val notifications: Boolean = false, val text: String = "",
    val computer: String? = null, val unread: Boolean = false, val expanded: Set<String> = emptySet(),
    val groupExpansion: Map<String, Boolean> = emptyMap())
internal data class RoutedSidebarSnapshot(val computers: List<RoutedSidebarComputer>, val rows: List<RoutedSidebarRow>,
    val unread: Int = 0, val loading: Boolean = false, val status: String? = null)
internal data class RoutedSidebarPage(val revision: String, val snapshot: RoutedSidebarSnapshot,
    val offset: Int, val next: Int?, val total: Int)

/** Captured account/team owner, validated again before reading or resolving navigation. */
internal interface RoutedSidebarHost {
    val owner: Any
    fun current(): Boolean
    fun initialQuery(): RoutedSidebarQuery = RoutedSidebarQuery()
    fun adopt(query: RoutedSidebarQuery) {}
    fun read(query: RoutedSidebarQuery): RoutedSidebarSnapshot?
    fun resolve(key: String): (() -> Unit)?
    fun retain(): RoutedSidebarLease
}
internal class RoutedSidebarLease(private val setActive: (Boolean) -> Unit, private val release: () -> Unit) : AutoCloseable {
    private var closed = false
    fun active(value: Boolean) { if (!closed) setActive(value) }
    override fun close() { if (!closed) { closed = true; setActive(false); release() } }
}
internal val LocalRoutedSidebarHost = staticCompositionLocalOf<RoutedSidebarHost?> { null }

/** Per-presentation paging and one-use navigation tickets; never a source of navigation authority. */
internal class RoutedSidebarExchange {
    private var revision: String? = null
    private var snapshot: RoutedSidebarSnapshot? = null
    private var issued = emptySet<String>()
    private var selection: Pair<String, String>? = null
    fun begin(value: RoutedSidebarSnapshot): RoutedSidebarPage {
        require(value.rows.size <= RoutedSidebarWire.MAX_ROWS && value.computers.size <= 256)
        require(value.rows.map { it.key }.distinct().size == value.rows.size)
        require(value.computers.map { it.key }.distinct().size == value.computers.size)
        revision = UUID.randomUUID().toString(); snapshot = value
        issued = emptySet()
        return page(checkNotNull(revision), 0)
    }
    fun page(expectedRevision: String, offset: Int): RoutedSidebarPage {
        check(expectedRevision == revision) { "Sidebar updated. Refresh its list." }
        val value = checkNotNull(snapshot)
        require(offset in 0..value.rows.size)
        val rows = mutableListOf<RoutedSidebarRow>()
        var bytes = RoutedSidebarWire.page(RoutedSidebarPage(expectedRevision, value.copy(rows = emptyList()),
            offset, null, value.rows.size)).toByteArray(Charsets.UTF_8).size + 64
        for (row in value.rows.drop(offset).take(100)) {
            val size = RoutedSidebarWire.row(row).toString().toByteArray(Charsets.UTF_8).size + 1
            if (bytes + size > RoutedSidebarWire.MAX_BYTES) break
            rows += row; bytes += size
        }
        check(rows.isNotEmpty() || offset == value.rows.size) { "Sidebar row is too large." }
        issued = issued + rows.filter { it.canOpen }.map { it.key }
        return RoutedSidebarPage(expectedRevision, value.copy(rows = rows), offset,
            (offset + rows.size).takeIf { it < value.rows.size }, value.rows.size)
    }
    fun prepare(key: String, canOpen: (String) -> Boolean): String {
        check(key in issued && canOpen(key)) { "This destination changed. Refresh the sidebar." }
        return UUID.randomUUID().toString().also { selection = it to key }
    }
    fun consume(ticket: String?): String? {
        val pending = selection ?: return null
        if (pending.first != ticket) return null
        selection = null
        return pending.second
    }
    fun clear() { revision = null; snapshot = null; issued = emptySet(); selection = null }
}

internal object RoutedSidebarWire {
    const val MAX_BYTES = 192 * 1024
    const val MAX_ROWS = 100_000
    private fun String.bounded(limit: Int) = NativeSearchText.prefix(this, limit)
    private fun JSONObject.optional(key: String, limit: Int): String? = optString(key).takeUnless { isNull(key) || it.isEmpty() }?.bounded(limit)
    private fun JSONObject.finite(key: String) = optDouble(key).takeIf { it.isFinite() }
    private fun token(value: String) = value.also { require(it.length in 1..128 && it.none(Char::isISOControl)) }
    fun query(value: RoutedSidebarQuery) = JSONObject().put("notifications", value.notifications)
        .put("text", NativeSearchText.boundQuery(value.text)).put("computer", value.computer)
        .put("unread", value.unread).put("expanded", JSONArray(value.expanded.sorted()))
        .put("groups", JSONObject(value.groupExpansion)).toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Too many expanded sidebar groups. Collapse some groups and retry." }
        }
    fun query(value: String): RoutedSidebarQuery {
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val json = JSONObject(value); val expanded = json.optJSONArray("expanded") ?: JSONArray()
        val groups = json.optJSONObject("groups") ?: JSONObject()
        require(expanded.length() <= 10_000 && groups.length() <= 10_000)
        return RoutedSidebarQuery(json.optBoolean("notifications"), NativeSearchText.boundQuery(json.optString("text")),
            if (json.isNull("computer")) null else token(json.getString("computer")), json.optBoolean("unread"),
            (0 until expanded.length()).map { token(expanded.getString(it)) }.toSet(),
            groups.keys().asSequence().associate { token(it) to groups.getBoolean(it) })
    }
    fun page(value: RoutedSidebarPage): String = JSONObject().put("revision", value.revision).put("offset", value.offset)
        .put("next", value.next ?: JSONObject.NULL).put("total", value.total)
        .put("unread", value.snapshot.unread).put("loading", value.snapshot.loading).put("status", value.snapshot.status?.bounded(2048))
        .put("computers", JSONArray().also { array -> value.snapshot.computers.forEach {
            array.put(JSONObject().put("key", token(it.key)).put("name", it.name.bounded(64)).put("build", it.build?.bounded(32)))
        } }).put("rows", JSONArray().also { array -> value.snapshot.rows.forEach {
            array.put(row(it))
        } }).toString()
    fun row(value: RoutedSidebarRow) = JSONObject().put("key", token(value.key)).put("kind", value.kind).put("title", value.title.bounded(1024))
                .put("subtitle", value.subtitle?.bounded(2048)).put("preview", value.preview?.bounded(4096)).put("computer", value.computer?.bounded(512))
                .put("unread", value.unread).put("count", value.count).put("pinned", value.pinned).put("color", value.color?.bounded(32))
                .put("activity", value.activity?.takeIf(Double::isFinite)).put("previewAt", value.previewAt?.takeIf(Double::isFinite))
                .put("icon", value.iconSymbol?.bounded(128)).put("availability", value.availability.name).put("depth", value.depth).put("expanded", value.expanded).put("open", value.canOpen)
    fun page(value: String): RoutedSidebarPage {
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val json = JSONObject(value); val computers = json.getJSONArray("computers"); val rows = json.getJSONArray("rows")
        require(computers.length() <= 256 && rows.length() <= 100)
        val total = json.getInt("total"); val offset = json.getInt("offset")
        require(total in 0..MAX_ROWS && offset in 0..total && offset + rows.length() <= total)
        val next = if (json.isNull("next")) null else json.getInt("next").also { require(it == offset + rows.length() && it > offset && it < total) }
        require(next != null || offset + rows.length() == total)
        return RoutedSidebarPage(token(json.getString("revision")), RoutedSidebarSnapshot(
            (0 until computers.length()).map { computers.getJSONObject(it).let { item ->
                RoutedSidebarComputer(token(item.getString("key")), item.getString("name").bounded(512), item.optional("build", 128))
            } }, (0 until rows.length()).map { rows.getJSONObject(it).let { item ->
                val kind = item.getString("kind").also { require(it in setOf("workspace", "group", "footer", "notification", "heading", "updates")) }
                RoutedSidebarRow(token(item.getString("key")), kind, item.getString("title").bounded(1024),
                    item.optional("subtitle", 2048), item.optional("preview", 4096), item.optional("computer", 512),
                    item.optBoolean("unread"), if (item.isNull("count")) null else item.getLong("count").coerceAtLeast(0),
                    item.optBoolean("pinned"), item.optional("color", 32), item.finite("activity"), item.finite("previewAt"),
                    NativeFeedAvailability.valueOf(item.getString("availability")), item.optInt("depth").coerceIn(0, 1),
                    item.optBoolean("expanded"), item.optBoolean("open"), item.optional("icon", 128))
            } }, json.optInt("unread").coerceAtLeast(0), json.optBoolean("loading"), json.optional("status", 2048)), offset, next, total)
    }
}
