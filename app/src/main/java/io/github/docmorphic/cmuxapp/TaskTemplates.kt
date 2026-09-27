package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Editable shipped identities and custom scripts, following MobileTaskTemplate. */
internal data class TaskTemplate(
    val id: String, val name: String, val icon: String = "terminal", val command: String = "",
    val defaultDirectory: String? = null, val builtInKind: TaskCommand.Agent? = null
) {
    val plainShell get() = command.isBlank()
    fun normalized() = copy(name = name.trim().also { require(it.isNotEmpty()) { "Enter a template name" } },
        icon = icon.trim(), command = if (plainShell) "" else command,
        defaultDirectory = defaultDirectory?.trim()?.takeIf { it.isNotEmpty() })
    fun json() = JSONObject().put("id", id).put("name", name).put("icon", icon).put("command", command)
        .put("default_directory", defaultDirectory).put("built_in", builtInKind?.name)
    companion object {
        fun builtInId(agent: TaskCommand.Agent) = UUID.nameUUIDFromBytes("cmux-android-template:${agent.name}".toByteArray()).toString()
        fun defaults() = TaskCommand.Agent.entries.map { agent -> TaskTemplate(builtInId(agent), agent.label,
            when (agent) { TaskCommand.Agent.CLAUDE -> "agent:claude"; TaskCommand.Agent.CODEX -> "agent:codex"
                TaskCommand.Agent.OPENCODE -> "agent:opencode"; TaskCommand.Agent.SHELL -> "terminal" },
            agent.command.orEmpty(), builtInKind = agent) }
        fun read(raw: JSONObject) = TaskTemplate(raw.getString("id").also { UUID.fromString(it) },
            raw.getString("name"), raw.getString("icon"), raw.getString("command"),
            raw.opt("default_directory") as? String, (raw.opt("built_in") as? String)?.let(TaskCommand.Agent::valueOf)).normalized()
    }
}

internal data class TaskRecentDirectory(val path: String, val lastUsedAt: Long, val useCount: Long)

/** The caller supplies only the authenticated Mac's foreground inventory. */
internal fun preferredTaskDirectories(workspaces: List<NativeWorkspace>, selectedId: String?): List<String> =
    workspaces.sortedWith(compareByDescending<NativeWorkspace> { it.id == selectedId }
        .thenByDescending { it.lastActivityAt ?: Double.NEGATIVE_INFINITY }).flatMap { workspace ->
        listOfNotNull(workspace.terminals.firstOrNull { it.isFocused }?.directory, workspace.directory) +
            workspace.terminals.mapNotNull { it.directory }
    }.filter { it.isNotBlank() }.distinct()

internal data class TaskTemplateState(
    val entries: List<TaskTemplate> = TaskTemplate.defaults(), val lastTemplateId: String? = null,
    val lastOrigin: String? = null, val recent: Map<String, List<TaskRecentDirectory>> = emptyMap()
) {
    fun selected(id: String? = null) = entries.firstOrNull { it.id == id }
        ?: entries.firstOrNull { it.id == lastTemplateId } ?: entries.first()
    fun suggestedDirectory(template: TaskTemplate, origin: String, open: String?) =
        template.defaultDirectory?.trim()?.takeIf { it.isNotEmpty() }
            ?: open?.trim()?.takeIf { it.isNotEmpty() }
            ?: recent[origin]?.firstOrNull()?.path ?: "~"
    fun json(): JSONObject = JSONObject().put("version", 1).put("entries", JSONArray(entries.map { it.json() }))
        .put("last_template", lastTemplateId).put("last_origin", lastOrigin)
        .put("recent", JSONObject().also { out -> recent.forEach { (origin, rows) -> out.put(origin,
            JSONArray(rows.map { JSONObject().put("path", it.path).put("last_used", it.lastUsedAt).put("uses", it.useCount) })) } })
}

internal sealed interface TaskTemplateChange {
    data class Save(val template: TaskTemplate, val adding: Boolean) : TaskTemplateChange
    data class Delete(val id: String) : TaskTemplateChange
}

/** Durable state is owned by the same encrypted account repository as task drafts. */
internal class TaskTemplates(saved: JSONObject? = null) {
    private val mutable = MutableStateFlow(read(saved))
    val state = mutable.asStateFlow()
    private var closed = false
    @Synchronized fun preview(change: TaskTemplateChange): TaskTemplateState {
        check(!closed) { "Account changed while editing templates" }
        val previous = mutable.value
        return when (change) {
            is TaskTemplateChange.Save -> {
                val value = change.template.normalized().also { UUID.fromString(it.id) }
                val old = previous.entries.firstOrNull { it.id == value.id }
                if (change.adding) {
                    require(old == null) { "Template already exists" }
                    previous.copy(entries = previous.entries + value.copy(builtInKind = null))
                } else {
                    require(old != null) { "Template is no longer available" }
                    previous.copy(entries = previous.entries.map { if (it.id == value.id) value.copy(builtInKind = old.builtInKind) else it })
                }
            }
            is TaskTemplateChange.Delete -> {
                val old = previous.entries.firstOrNull { it.id == change.id }
                require(old?.builtInKind == null) { "Built-in templates cannot be deleted" }
                previous.copy(entries = previous.entries.filterNot { it.id == change.id },
                    lastTemplateId = previous.lastTemplateId?.takeUnless { it == change.id })
            }
        }
    }
    @Synchronized fun apply(change: TaskTemplateChange) { mutable.value = preview(change) }
    @Synchronized fun recordSuccess(templateId: String, origin: String, directory: String?, now: Long = System.currentTimeMillis()) {
        if (closed) return
        val previous = mutable.value
        val path = directory?.trim()?.takeIf { it.isNotEmpty() }
        val history = previous.recent[origin].orEmpty()
        val uses = history.firstOrNull { it.path == path }?.useCount ?: 0
        mutable.value = previous.copy(lastTemplateId = templateId.takeIf { id -> previous.entries.any { it.id == id } },
            lastOrigin = origin, recent = if (path == null) previous.recent else previous.recent +
                (origin to (listOf(TaskRecentDirectory(path, now, if (uses == Long.MAX_VALUE) uses else uses + 1)) +
                    history.filterNot { it.path == path }).take(20)))
    }
    @Synchronized fun close() { closed = true; mutable.value = TaskTemplateState() }
    companion object {
        private fun read(saved: JSONObject?): TaskTemplateState {
            if (saved == null) return TaskTemplateState()
            require(saved.getInt("version") == 1) { "Unsupported task template format" }
            val array = saved.getJSONArray("entries")
            val entries = (0 until array.length()).map { TaskTemplate.read(array.getJSONObject(it)) }
            require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate template identity" }
            val protected = entries.mapNotNull { it.builtInKind }
            require(protected.distinct().size == protected.size) { "Duplicate built-in template identity" }
            val complete = TaskTemplate.defaults().map { seed -> entries.firstOrNull { it.builtInKind == seed.builtInKind } ?: seed } +
                entries.filter { it.builtInKind == null }
            require(complete.map { it.id }.distinct().size == complete.size) { "Conflicting template identity" }
            val recent = saved.optJSONObject("recent") ?: JSONObject()
            return TaskTemplateState(complete, (saved.opt("last_template") as? String)?.takeIf { id -> complete.any { it.id == id } },
                saved.opt("last_origin") as? String, recent.keys().asSequence().associateWith { origin ->
                    val rows = recent.getJSONArray(origin)
                    (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
                        TaskRecentDirectory(row.getString("path"), row.getLong("last_used"), row.getLong("uses").coerceAtLeast(1)) } }
                        .filter { it.path.isNotBlank() }.distinctBy { it.path }.sortedByDescending { it.lastUsedAt }.take(20)
                })
        }
    }
}
