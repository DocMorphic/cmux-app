/* Ported from CmuxTerminalClientModel and CmuxMobileCloudBridge at c2715faa.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Ownership remains identifiable while a connection is absent. Never a Mac RPC ID. */
internal data class CloudAddress(val machineId: String, val component: String? = null) {
    init { require(machineId.isNotEmpty() && '\u001d' !in machineId); require(component == null || component.isNotEmpty()) }
    val identifier get() = "cmux-cloud\u001d$machineId" + (component?.let { "\u001d$it" } ?: "")
    val host get() = CloudAddress(machineId)
    companion object {
        fun parse(value: String): CloudAddress? {
            if (!value.startsWith("cmux-cloud\u001d")) return null
            val body = value.removePrefix("cmux-cloud\u001d")
            val split = body.indexOf('\u001d')
            val machine = if (split < 0) body else body.take(split)
            val component = if (split < 0) null else body.substring(split + 1)
            if (machine.isEmpty() || component?.isEmpty() == true) return null
            return CloudAddress(machine, component)
        }
    }
}

internal data class CloudWorkspaceSummary(val id: String, val name: String? = null, val root: String? = null) {
    val preferredName get() = name?.takeIf(String::isNotEmpty) ?: root?.split('/')?.lastOrNull(String::isNotEmpty) ?: id
}
internal data class CloudTerminalSummary(val id: String, val name: String? = null, val workspaceId: String? = null,
    val title: String? = null, val directory: String? = null) {
    val descriptiveName: String? get() = listOf(name, title).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
        ?: directory?.trim()?.takeIf(String::isNotEmpty)?.let { path ->
            val parts = path.split('/').filter(String::isNotEmpty)
            val depth = when {
                parts.firstOrNull() == "root" -> 1
                parts.size >= 2 && parts.first() in setOf("home", "Users") -> 2
                else -> 0
            }
            if (depth == 0) path else parts.drop(depth).takeIf { it.isNotEmpty() }?.joinToString("/", "~/")
        }
}
internal data class CloudWorkspaceCatalog(val workspaces: List<CloudWorkspaceSummary>, val terminals: List<CloudTerminalSummary>)

/** The C ABI returns operation results directly, not Mac/mobile JSON-RPC envelopes. */
internal object CloudWorkspaceDecoding {
    private fun json(bytes: ByteArray): Any {
        require(bytes.size <= 16 * 1024 * 1024) { "Cloud catalog is too large" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val parser = JSONTokener(text)
        val value = parser.nextValue()
        require(parser.nextClean() == '\u0000') { "Trailing Cloud catalog data" }
        return value
    }
    private fun JSONObject.text(key: String): String? {
        val value = opt(key)
        require(value == null || value == JSONObject.NULL || value is String) { "Invalid Cloud catalog $key" }
        return value as? String
    }
    private fun JSONObject.id(key: String = "id") = requireNotNull(text(key)?.takeIf(String::isNotEmpty)) { "Missing Cloud catalog $key" }
    private fun JSONObject.index(): Int {
        val value = opt("index")
        if (value == null || value == JSONObject.NULL) return 0
        require(value is Number && value.toDouble() == value.toInt().toDouble()) { "Invalid Cloud catalog index" }
        return value.toInt()
    }
    private fun JSONArray.objects(): List<JSONObject> {
        require(length() <= 65536) { "Too many Cloud catalog records" }
        return (0 until length()).map { getJSONObject(it) }
    }
    private fun JSONObject.records(key: String, optional: Boolean = false): List<JSONObject> {
        if (optional && isNull(key)) return emptyList()
        return getJSONArray(key).objects()
    }
    private fun <T> List<T>.unique(id: (T) -> String): List<T> {
        require(map(id).distinct().size == size) { "Duplicate Cloud catalog identity" }; return this
    }
    private fun List<JSONObject>.firstById(): Map<String, JSONObject> = associateByFirst { it.id() }
    private fun <T, K> List<T>.associateByFirst(key: (T) -> K): Map<K, T> = buildMap {
        for (value in this@associateByFirst) { val id = key(value); if (id !in this) put(id, value) }
    }
    fun workspaces(bytes: ByteArray): List<CloudWorkspaceSummary> {
        val value = json(bytes)
        val rows = if (value is JSONObject) value.records("workspaces") else (value as JSONArray).objects()
        return rows.map { CloudWorkspaceSummary(it.id(), it.text("name"), it.text("root")) }.unique { it.id }
    }
    fun terminals(bytes: ByteArray): List<CloudTerminalSummary> = (json(bytes) as JSONArray).objects().map {
        CloudTerminalSummary(it.id(), it.text("name"), it.text("workspace_id"), it.text("title"), it.text("cwd"))
    }.unique { it.id }
    fun snapshot(bytes: ByteArray): CloudWorkspaceCatalog {
        val value = json(bytes) as JSONObject
        val workspaces = value.records("workspaces").map { CloudWorkspaceSummary(it.id(), it.text("name")) }.unique { it.id }
        val order = workspaces.withIndex().associate { it.value.id to it.index }
        val screens = value.records("screens", true).firstById()
        val paneRows = value.records("panes", true)
        val panes = paneRows.firstById()
        val paneOrder = paneRows.withIndex().toList().associateByFirst { it.value.id() }.mapValues { it.value.index }
        val tabs = value.records("tabs", true).firstById()
        data class Placed(val terminal: CloudTerminalSummary, val sort: List<Int>, val offset: Int)
        val placed = value.records("terminals").mapIndexed { offset, row ->
            val ids = row.opt("tab_ids")
            require(ids == null || ids == JSONObject.NULL || ids is JSONArray) { "Invalid Cloud catalog tab_ids" }
            val tabIds = (ids as? JSONArray)?.let { array -> (0 until array.length()).map { array.get(it).also { id ->
                require(id is String) { "Invalid Cloud tab identity" }
            } as String } }
            val tab = (row.text("tab_id") ?: tabIds?.firstOrNull())?.let(tabs::get)
            val pane = tab?.id("pane_id")?.let(panes::get)
            val screen = pane?.id("screen_id")?.let(screens::get)
            val workspace = screen?.id("workspace_id")?.takeIf { it in order }
            val sort = if (workspace == null) listOf(Int.MAX_VALUE) else
                listOf(order.getValue(workspace), checkNotNull(screen).index(), paneOrder.getValue(checkNotNull(pane).id()), checkNotNull(tab).index())
            Placed(CloudTerminalSummary(row.id(), if (workspace == null) null else tab?.text("name")?.takeIf(String::isNotEmpty),
                workspace, row.text("title"), row.text("cwd")), sort, offset)
        }.unique { it.terminal.id }
        val terminals = placed.sortedWith { a, b ->
            var comparison = 0
            for (i in 0 until minOf(a.sort.size, b.sort.size)) {
                comparison = a.sort[i].compareTo(b.sort[i]); if (comparison != 0) break
            }
            if (comparison != 0) comparison else a.offset.compareTo(b.offset)
        }.map { it.terminal }
        return CloudWorkspaceCatalog(workspaces, terminals)
    }
    fun created(bytes: ByteArray, terminal: Boolean): String = (json(bytes) as JSONObject).getJSONObject("value")
        .id(if (terminal) "terminal_id" else "workspace_id")
}

internal data class CloudWorkspaceRow(val machine: CloudMachine, val remoteId: String, val workspace: NativeWorkspace) {
    val key get() = workspace.id
}
internal fun projectCloudWorkspaces(machine: CloudMachine, catalog: CloudWorkspaceCatalog): List<CloudWorkspaceRow> {
    val terminals = catalog.terminals.groupBy { it.workspaceId }
    fun row(id: String, name: String, root: String?, items: List<CloudTerminalSummary>) = CloudWorkspaceRow(machine, id,
        NativeWorkspace(CloudAddress(machine.id, id).identifier, name, items.mapIndexed { index, terminal ->
            NativeTerminal(CloudAddress(machine.id, terminal.id).identifier, terminal.descriptiveName ?: "Terminal ${index + 1}", terminal.directory)
        }, root, false, null, null, false, emptyList(), null, null, null))
    return catalog.workspaces.map { row(it.id, it.preferredName, it.root, terminals[it.id].orEmpty()) } +
        catalog.terminals.filter { it.workspaceId.isNullOrEmpty() }.takeIf { it.isNotEmpty() }?.let {
            listOf(row("unassigned", machine.preferredName, null, it))
        }.orEmpty()
}
