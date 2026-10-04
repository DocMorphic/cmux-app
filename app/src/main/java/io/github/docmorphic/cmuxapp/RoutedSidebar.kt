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
    val canRead: Boolean = false, val notificationContext: NativeFeedRowContext = NativeFeedRowContext(),
    val mutations: Set<RoutedSidebarMutationKind> = emptySet(),
    val canCustomize: Boolean = false, val createKey: String? = null,
    val sshKind: SshWorkspaceKind? = null, val selected: Boolean = false,
) {
    fun workspace() = NativeWorkspace(key, title, emptyList(), null, unread, activity, null, pinned,
        emptyList(), null, preview, color, subtitle, count, previewAt = previewAt)
}
internal sealed interface RoutedSidebarNotification {
    data class Read(val key: String, val read: Boolean) : RoutedSidebarNotification
    data class ReadAll(val key: String) : RoutedSidebarNotification
    data object Refresh : RoutedSidebarNotification
}
internal data class RoutedSidebarReadAll(val key: String, val computer: String)
internal enum class RoutedSidebarActionKind { SETTINGS, COMPUTERS, NEW_TASK }
internal data class RoutedSidebarAction(val key: String, val kind: RoutedSidebarActionKind)
internal data class RoutedSidebarComputer(val key: String, val name: String, val build: String? = null)
internal data class RoutedSidebarQuery(val notifications: Boolean = false,
    val workspaceQuery: String = "", val notificationQuery: String = "", val computer: String? = null,
    val workspaceUnread: Boolean = false, val notificationUnread: Boolean = false,
    val machines: Set<String> = emptySet(), val expanded: Set<String> = emptySet(),
    val groupExpansion: Map<String, Boolean> = emptyMap()) {
    val text get() = if (notifications) notificationQuery else workspaceQuery
    val unread get() = if (notifications) notificationUnread else workspaceUnread
    fun withText(value: String) = if (notifications) copy(notificationQuery = NativeSearchText.boundQuery(value))
        else copy(workspaceQuery = NativeSearchText.boundQuery(value))
    fun withUnread(value: Boolean) = if (notifications) copy(notificationUnread = value) else copy(workspaceUnread = value)
}
internal sealed interface RoutedSidebarSort {
    data class Mode(val mode: NativeWorkspaceSortMode) : RoutedSidebarSort
    data class Order(val keys: List<String>) : RoutedSidebarSort
}
internal data class RoutedSidebarSnapshot(val computers: List<RoutedSidebarComputer>, val rows: List<RoutedSidebarRow>,
    val unread: Int = 0, val loading: Boolean = false, val status: String? = null,
    val filterMachines: List<RoutedSidebarComputer> = emptyList(), val selectedMachines: Set<String> = emptySet(),
    val sortMode: NativeWorkspaceSortMode? = null, val actions: List<RoutedSidebarAction> = emptyList(), val readAll: RoutedSidebarReadAll? = null,
    val canRefresh: Boolean = false, val expanded: Set<String> = emptySet(), val editorTicket: String? = null,
    val creation: List<RoutedSidebarCreateComputer> = emptyList(), val createGroup: String? = null,
    val wrapTitles: Boolean = false, val previewLines: Int = 2) {
    init { require(previewLines in 1..2) }
}
internal data class RoutedSidebarPage(val revision: String, val snapshot: RoutedSidebarSnapshot,
    val offset: Int, val next: Int?, val total: Int)

/** Captured account/team owner, validated again before reading or resolving navigation. */
internal interface RoutedSidebarHost {
    val owner: Any
    fun current(): Boolean
    fun initialQuery(): RoutedSidebarQuery = RoutedSidebarQuery()
    fun adopt(query: RoutedSidebarQuery) {}
    fun withSelection(selection: NativeSidebarSelection?): RoutedSidebarHost = this
    fun read(query: RoutedSidebarQuery): RoutedSidebarSnapshot?
    fun resolve(key: String): (() -> Unit)?
    fun groupMenu(key: String, revision: String?, offset: Int): RoutedSidebarGroupPage { error("Group moves are unavailable") }
    fun customization(key: String): RoutedSidebarCustomizationEditor { error("Workspace customization is unavailable") }
    suspend fun mutate(command: RoutedSidebarMutation, canSend: () -> Boolean) { error("Workspace actions are unavailable") }
    fun sort(command: RoutedSidebarSort) { error("Sidebar sorting is unavailable") }
    suspend fun notifications(command: RoutedSidebarNotification, query: RoutedSidebarQuery, canSend: () -> Boolean) {
        error("Notification actions are unavailable")
    }
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
    private var readable = emptySet<String>()
    private var mutableRows = emptySet<String>()
    private var editable = emptySet<String>()
    private var selection: Pair<String, String>? = null
    fun begin(value: RoutedSidebarSnapshot): RoutedSidebarPage {
        require(value.rows.size <= RoutedSidebarWire.MAX_ROWS && value.computers.size <= 256)
        require(value.rows.map { it.key }.distinct().size == value.rows.size)
        require(value.computers.map { it.key }.distinct().size == value.computers.size)
        require(value.actions.size <= RoutedSidebarActionKind.entries.size)
        require(value.actions.map { it.kind }.distinct().size == value.actions.size)
        require((value.rows.map { it.key } + value.actions.map { it.key }).distinct().size == value.rows.size + value.actions.size)
        RoutedSidebarCreationWire.decode(RoutedSidebarCreationWire.encode(value.creation))
        val destinations = value.creation.flatMap { it.options }.map { it.key } + value.rows.mapNotNull { it.createKey }
        require(destinations.distinct().size == destinations.size)
        require(destinations.none { key -> value.rows.any { it.key == key } || value.actions.any { it.key == key } })
        revision = UUID.randomUUID().toString(); snapshot = value
        issued = emptySet(); readable = emptySet(); mutableRows = emptySet(); editable = emptySet()
        return page(checkNotNull(revision), 0)
    }
    fun page(expectedRevision: String, offset: Int): RoutedSidebarPage {
        check(expectedRevision == revision) { "Sidebar updated. Refresh its list." }
        val value = checkNotNull(snapshot)
        require(offset in 0..value.rows.size)
        val rows = mutableListOf<RoutedSidebarRow>()
        var bytes = RoutedSidebarWire.page(RoutedSidebarPage(expectedRevision, value.copy(rows = emptyList()),
            offset, null, value.rows.size)).toByteArray(Charsets.UTF_8).size + 64
        check(bytes <= RoutedSidebarWire.MAX_BYTES) { "Sidebar computer menu is too large. Select a computer and retry." }
        for (row in value.rows.drop(offset).take(100)) {
            val size = RoutedSidebarWire.row(row).toString().toByteArray(Charsets.UTF_8).size + 1
            if (bytes + size > RoutedSidebarWire.MAX_BYTES) break
            rows += row; bytes += size
        }
        check(rows.isNotEmpty() || offset == value.rows.size) { "Sidebar row is too large." }
        mutableRows = mutableRows + rows.filter { it.mutations.isNotEmpty() }.map { it.key }
        editable = editable + rows.filter { it.canCustomize }.map { it.key }
        readable = readable + rows.filter { it.kind == "notification" && it.canRead }.map { it.key }
        issued = issued + rows.filter { it.canOpen }.map { it.key } + value.actions.map { it.key } +
            rows.mapNotNull { it.createKey } + value.creation.flatMap { it.options }.filter { it.unavailableReason == null }.map { it.key }
        return RoutedSidebarPage(expectedRevision, value.copy(rows = rows), offset,
            (offset + rows.size).takeIf { it < value.rows.size }, value.rows.size)
    }
    fun permitsMutation(command: RoutedSidebarMutation): Boolean = if (command.kind == RoutedSidebarMutationKind.CREATE_GROUP)
        snapshot?.createGroup == command.key
        else command.key in mutableRows && snapshot?.rows?.any { it.key == command.key && command.kind in it.mutations } == true
    fun permitsGroupMenu(key: String) = key in mutableRows && snapshot?.rows?.any {
        it.key == key && RoutedSidebarMutationKind.MOVE_TO_GROUP in it.mutations
    } == true
    fun permitsCustomization(key: String) = key in editable && snapshot?.rows?.any { it.key == key && it.canCustomize } == true
    fun permitsSort(command: RoutedSidebarSort): Boolean {
        val value = snapshot ?: return false
        if (value.sortMode == null) return false
        return when (command) {
            is RoutedSidebarSort.Mode -> true
            is RoutedSidebarSort.Order -> command.keys.distinct().size == command.keys.size &&
                command.keys.toSet() == value.computers.map { it.key }.toSet()
        }
    }
    fun permitsNotification(command: RoutedSidebarNotification): Boolean {
        val value = snapshot ?: return false
        return when (command) {
            is RoutedSidebarNotification.Read -> command.key in readable && value.rows.any {
                it.key == command.key && it.kind == "notification" && it.canRead
            }
            is RoutedSidebarNotification.ReadAll -> value.readAll?.key == command.key
            RoutedSidebarNotification.Refresh -> value.canRefresh
        }
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
    fun clear() { revision = null; snapshot = null; issued = emptySet(); readable = emptySet(); mutableRows = emptySet(); editable = emptySet(); selection = null }
}

internal object RoutedSidebarWire {
    const val MAX_BYTES = 192 * 1024
    const val MAX_ROWS = 100_000
    private fun String.bounded(limit: Int) = NativeSearchText.prefix(this, limit)
    private fun JSONObject.optional(key: String, limit: Int): String? = optString(key).takeUnless { isNull(key) || it.isEmpty() }?.bounded(limit)
    private fun JSONObject.finite(key: String) = optDouble(key).takeIf { it.isFinite() }
    private fun token(value: String) = value.also { require(it.length in 1..128 && it.none(Char::isISOControl)) }
    private fun keys(value: JSONArray, limit: Int = 256): List<String> {
        require(value.length() <= limit)
        return (0 until value.length()).map { token(value.getString(it)) }
    }
    private fun checked(value: String) = value.also {
        require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Sidebar state is too large. Collapse some groups and retry." }
    }
    fun mutation(value: RoutedSidebarMutation): String {
        value.validate()
        return checked(JSONObject().put("key", value.key).put("kind", value.kind.name).put("title", value.title)
            .put("menu", value.menuRevision).put("destination", value.destination).toString())
    }
    fun mutation(value: String): RoutedSidebarMutation {
        val json = JSONObject(checked(value))
        return RoutedSidebarMutation(token(json.getString("key")), RoutedSidebarMutationKind.valueOf(json.getString("kind")),
            if (json.isNull("title")) null else json.getString("title"),
            if (json.isNull("menu")) null else token(json.getString("menu")),
            if (json.isNull("destination")) null else token(json.getString("destination"))).also { it.validate() }
    }
    private fun mutations(value: JSONArray): Set<RoutedSidebarMutationKind> {
        require(value.length() <= RoutedSidebarMutationKind.entries.size)
        return (0 until value.length()).map { RoutedSidebarMutationKind.valueOf(value.getString(it)) }.toSet()
    }
    fun notification(value: RoutedSidebarNotification): String = checked(when (value) {
        is RoutedSidebarNotification.Read -> JSONObject().put("kind", "read").put("key", token(value.key)).put("read", value.read)
        is RoutedSidebarNotification.ReadAll -> JSONObject().put("kind", "read_all").put("key", token(value.key))
        RoutedSidebarNotification.Refresh -> JSONObject().put("kind", "refresh")
    }.toString())
    fun notification(value: String): RoutedSidebarNotification {
        val json = JSONObject(checked(value))
        return when (json.getString("kind")) {
            "read" -> RoutedSidebarNotification.Read(token(json.getString("key")), json.getBoolean("read"))
            "read_all" -> RoutedSidebarNotification.ReadAll(token(json.getString("key")))
            "refresh" -> RoutedSidebarNotification.Refresh
            else -> error("Unknown notification action")
        }
    }
    fun sort(value: RoutedSidebarSort): String = checked(when (value) {
        is RoutedSidebarSort.Mode -> JSONObject().put("mode", value.mode.raw)
        is RoutedSidebarSort.Order -> JSONObject().put("order", JSONArray(value.keys))
    }.toString())
    fun sort(value: String): RoutedSidebarSort {
        val json = JSONObject(checked(value))
        require(json.has("mode") != json.has("order"))
        return if (json.has("mode")) RoutedSidebarSort.Mode(checkNotNull(NativeWorkspaceSortMode.entries.singleOrNull { it.raw == json.getString("mode") }))
        else RoutedSidebarSort.Order(keys(json.getJSONArray("order")).also { require(it.distinct().size == it.size) })
    }
    fun query(value: RoutedSidebarQuery) = checked(JSONObject().put("notifications", value.notifications)
        .put("workspace_query", NativeSearchText.boundQuery(value.workspaceQuery))
        .put("notification_query", NativeSearchText.boundQuery(value.notificationQuery)).put("computer", value.computer)
        .put("workspace_unread", value.workspaceUnread).put("notification_unread", value.notificationUnread)
        .put("machines", JSONArray(value.machines.sorted())).put("expanded", JSONArray(value.expanded.sorted()))
        .put("groups", JSONObject(value.groupExpansion)).toString())
    fun query(value: String): RoutedSidebarQuery {
        val json = JSONObject(checked(value)); val expanded = json.optJSONArray("expanded") ?: JSONArray()
        val groups = json.optJSONObject("groups") ?: JSONObject()
        require(groups.length() <= 10_000)
        return RoutedSidebarQuery(json.optBoolean("notifications"), NativeSearchText.boundQuery(json.optString("workspace_query")),
            NativeSearchText.boundQuery(json.optString("notification_query")),
            if (json.isNull("computer")) null else token(json.getString("computer")),
            json.optBoolean("workspace_unread"), json.optBoolean("notification_unread"),
            keys(json.optJSONArray("machines") ?: JSONArray()).toSet(), keys(expanded, 10_000).toSet(),
            groups.keys().asSequence().associate { token(it) to groups.getBoolean(it) })
    }
    private fun computers(value: List<RoutedSidebarComputer>) = JSONArray().also { array -> value.forEach {
        array.put(JSONObject().put("key", token(it.key)).put("name", it.name.bounded(64)).put("build", it.build?.bounded(32)))
    } }
    private fun computers(value: JSONArray): List<RoutedSidebarComputer> {
        require(value.length() <= 256)
        return (0 until value.length()).map { value.getJSONObject(it).let { item ->
            RoutedSidebarComputer(token(item.getString("key")), item.getString("name").bounded(64), item.optional("build", 32))
        } }.also { require(it.map { row -> row.key }.distinct().size == it.size) }
    }
    fun page(value: RoutedSidebarPage): String = JSONObject().put("revision", value.revision).put("offset", value.offset)
        .put("next", value.next ?: JSONObject.NULL).put("total", value.total)
        .put("unread", value.snapshot.unread).put("loading", value.snapshot.loading).put("status", value.snapshot.status?.bounded(2048))
        .put("read_all", value.snapshot.readAll?.let { JSONObject().put("key", token(it.key)).put("computer", it.computer.bounded(64)) })
        .put("can_refresh", value.snapshot.canRefresh)
        .put("wrap_titles", value.snapshot.wrapTitles).put("preview_lines", value.snapshot.previewLines)
        .put("editor", value.snapshot.editorTicket)
        .put("creation", RoutedSidebarCreationWire.encode(value.snapshot.creation))
        .put("create_group", value.snapshot.createGroup?.let(::token))
        .put("expanded", JSONArray(value.snapshot.expanded.sorted()))
        .put("actions", JSONArray().also { array -> value.snapshot.actions.forEach {
            array.put(JSONObject().put("key", token(it.key)).put("kind", it.kind.name))
        } })
        .put("sort_mode", value.snapshot.sortMode?.raw).put("machines", JSONArray(value.snapshot.filterMachines.map { it.key }))
        .put("selected_machines", JSONArray(value.snapshot.selectedMachines.sorted()))
        .put("computers", computers(value.snapshot.computers)).put("rows", JSONArray().also { array -> value.snapshot.rows.forEach {
            array.put(row(it))
        } }).toString()
    fun row(value: RoutedSidebarRow) = JSONObject().put("key", token(value.key)).put("kind", value.kind).put("title", value.title.bounded(1024))
                .put("subtitle", value.subtitle?.bounded(2048)).put("preview", value.preview?.bounded(4096)).put("computer", value.computer?.bounded(512))
                .put("unread", value.unread).put("count", value.count).put("pinned", value.pinned).put("color", value.color?.bounded(32))
                .put("activity", value.activity?.takeIf(Double::isFinite)).put("previewAt", value.previewAt?.takeIf(Double::isFinite))
                .put("mutations", JSONArray(value.mutations.map { it.name }.sorted()))
                .put("customize", value.canCustomize).put("create", value.createKey?.let(::token))
                .put("ssh_kind", value.sshKind?.name).put("selected", value.selected)
                .put("can_read", value.canRead).put("nested", value.notificationContext.nested)
                .put("hide_headline", value.notificationContext.hideHeadline).put("hide_source", value.notificationContext.hideSource)
                .put("hide_computer", value.notificationContext.hideComputer)
                .put("icon", value.iconSymbol?.bounded(128)).put("availability", value.availability.name).put("depth", value.depth).put("expanded", value.expanded).put("open", value.canOpen)
    fun page(value: String): RoutedSidebarPage {
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val json = JSONObject(value); val computerRows = json.getJSONArray("computers"); val rows = json.getJSONArray("rows")
        require(computerRows.length() <= 256 && rows.length() <= 100)
        val computerValues = computers(computerRows)
        val actionRows = json.optJSONArray("actions") ?: JSONArray()
        require(actionRows.length() <= RoutedSidebarActionKind.entries.size)
        val actions = (0 until actionRows.length()).map { actionRows.getJSONObject(it).let { item ->
            RoutedSidebarAction(token(item.getString("key")), RoutedSidebarActionKind.valueOf(item.getString("kind")))
        } }
        require(actions.map { it.kind }.distinct().size == actions.size && actions.map { it.key }.distinct().size == actions.size)
        require((0 until rows.length()).none { index -> actions.any { it.key == rows.getJSONObject(index).getString("key") } })
        val machineKeys = keys(json.optJSONArray("machines") ?: JSONArray()).toSet()
        val selectedMachines = keys(json.optJSONArray("selected_machines") ?: JSONArray()).toSet()
        require(computerValues.map { it.key }.containsAll(machineKeys) && machineKeys.containsAll(selectedMachines))
        val total = json.getInt("total"); val offset = json.getInt("offset")
        require(total in 0..MAX_ROWS && offset in 0..total && offset + rows.length() <= total)
        val next = if (json.isNull("next")) null else json.getInt("next").also { require(it == offset + rows.length() && it > offset && it < total) }
        require(next != null || offset + rows.length() == total)
        return RoutedSidebarPage(token(json.getString("revision")), RoutedSidebarSnapshot(
            computerValues, (0 until rows.length()).map { rows.getJSONObject(it).let { item ->
                val kind = item.getString("kind").also { require(it in setOf("workspace", "group", "footer", "notification", "heading", "updates")) }
                RoutedSidebarRow(token(item.getString("key")), kind, item.getString("title").bounded(1024),
                    item.optional("subtitle", 2048), item.optional("preview", 4096), item.optional("computer", 512),
                    item.optBoolean("unread"), if (item.isNull("count")) null else item.getLong("count").coerceAtLeast(0),
                    item.optBoolean("pinned"), item.optional("color", 32), item.finite("activity"), item.finite("previewAt"),
                    NativeFeedAvailability.valueOf(item.getString("availability")), item.optInt("depth").coerceIn(0, 1),
                    item.optBoolean("expanded"), item.optBoolean("open"), item.optional("icon", 128), item.optBoolean("can_read"),
                    NativeFeedRowContext(item.optBoolean("nested"), item.optBoolean("hide_headline"), item.optBoolean("hide_source"), item.optBoolean("hide_computer")), mutations(item.optJSONArray("mutations") ?: JSONArray()), item.optBoolean("customize"), if (item.isNull("create")) null else token(item.getString("create")),
                    if (item.isNull("ssh_kind")) null else SshWorkspaceKind.valueOf(item.getString("ssh_kind")), item.optBoolean("selected"))
            } }, json.optInt("unread").coerceAtLeast(0), json.optBoolean("loading"), json.optional("status", 2048),
                computerValues.filter { it.key in machineKeys }, selectedMachines,
                if (json.isNull("sort_mode")) null else NativeWorkspaceSortMode.entries.single { it.raw == json.getString("sort_mode") }, actions,
                json.optJSONObject("read_all")?.let { RoutedSidebarReadAll(token(it.getString("key")), it.getString("computer").bounded(64)) },
                json.optBoolean("can_refresh"), keys(json.optJSONArray("expanded") ?: JSONArray(), 2000).toSet(),
                if (json.isNull("editor")) null else token(json.getString("editor")),
                RoutedSidebarCreationWire.decode(json.optJSONArray("creation") ?: JSONArray()),
                if (json.isNull("create_group")) null else token(json.getString("create_group")),
                json.optBoolean("wrap_titles"), if (json.has("preview_lines")) json.getInt("preview_lines") else 2), offset, next, total)
    }
}
