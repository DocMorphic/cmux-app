package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Durable task composition, scoped to the exact paired Mac and account session. */
internal data class TaskDraft(
    val id: String, val origin: String, val macName: String, val updatedAt: Long,
    val agent: TaskCommand.Agent = TaskCommand.Agent.CLAUDE,
    val prompt: String = "", val directory: String = "",
    val selection: TaskModelSelection = TaskModelSelection(), val defaultModel: TaskModel? = null,
    val lastRequest: String? = null
) {
    val isEmpty get() = prompt.isBlank() && lastRequest == null
    val title get() = prompt.trim().lineSequence().firstOrNull()?.takeIf { it.isNotEmpty() } ?: "Untitled task"
    fun restoredModels() = TaskModelResult(listOfNotNull(selection.explicit), TaskModelSource.FALLBACK, defaultModel)
    fun reconcileModels(provider: TaskAgentCommand?, result: TaskModelResult?): TaskDraft {
        if (agent.command?.let(TaskAgentCommand::detect) != provider) return this
        val reconciled = selection.reconcile(result)
        return copy(selection = reconciled.copy(explicit = reconciled.model(result)), defaultModel = result?.defaultModel)
    }

    fun json(): JSONObject = JSONObject().put("id", id).put("origin", origin).put("mac_name", macName)
        .put("updated_at", updatedAt).put("agent", agent.name).put("prompt", prompt).put("directory", directory)
        .put("model", selection.explicit?.let(::modelJson)).put("effort", selection.effortId)
        .put("default_model", defaultModel?.let(::modelJson)).put("last_request", lastRequest?.let(::JSONObject))

    companion object {
        private fun modelJson(model: TaskModel): JSONObject = JSONObject().put("id", model.id)
            .put("display_name", model.name).put("default_effort_id", model.defaultEffortId)
            .put("efforts", JSONArray(model.efforts.map { effort -> JSONObject().put("id", effort.id)
                .put("display_name", effort.name).put("description", effort.description) }))
        private fun model(raw: JSONObject?): TaskModel? = raw?.let {
            TaskModelParser.host(JSONObject().put("source", "discovered").put("models", JSONArray().put(it))).models.single()
        }
        fun read(raw: JSONObject): TaskDraft {
            val id = raw.getString("id"); UUID.fromString(id)
            val origin = raw.getString("origin"); require(origin.isNotBlank())
            val request = raw.optJSONObject("last_request")?.also { UUID.fromString(it.getString("operation_id")) }
            return TaskDraft(id, origin, raw.getString("mac_name"), raw.getLong("updated_at"),
                TaskCommand.Agent.valueOf(raw.getString("agent")), raw.getString("prompt"), raw.getString("directory"),
                TaskModelSelection(model(raw.optJSONObject("model")), raw.opt("effort") as? String),
                model(raw.optJSONObject("default_model")), request?.toString())
        }
    }
}

/** The iOS newest-first, 20-entry draft collection, with editor leases for stale callbacks. */
internal class TaskDrafts(saved: JSONObject? = null, private val now: () -> Long = System::currentTimeMillis) {
    data class Editor(val id: String, internal val generation: Long, internal val lease: UUID)
    private val mutable = MutableStateFlow(readSaved(saved))
    val state = mutable.asStateFlow()
    private var generation = 0L
    private val leases = mutableMapOf<String, UUID>()
    private var lastTimestamp = mutable.value.values.maxOfOrNull { it.updatedAt } ?: 0L

    @Synchronized fun begin(id: String, origin: String, macName: String, directory: String): Editor {
        UUID.fromString(id)
        val existing = mutable.value[id]
        require(existing == null || existing.origin == origin) { "This draft belongs to another Mac" }
        if (existing == null) mutable.value = mutable.value + (id to TaskDraft(id, origin, macName, timestamp(), directory = directory))
        val lease = UUID.randomUUID()
        leases[id] = lease
        return Editor(id, generation, lease)
    }

    @Synchronized fun isCurrent(editor: Editor) = editor.generation == generation && leases[editor.id] == editor.lease

    @Synchronized fun edit(editor: Editor, update: (TaskDraft) -> TaskDraft): TaskDraft {
        check(isCurrent(editor)) { "This task draft is open in another session" }
        val previous = checkNotNull(mutable.value[editor.id]) { "This task draft is no longer available" }
        val updated = update(previous)
        require(updated.id == previous.id && updated.origin == previous.origin) { "Draft identity cannot change" }
        if (updated == previous) return previous
        val result = updated.copy(updatedAt = timestamp())
        mutable.value = bounded(mutable.value + (result.id to result))
        return result
    }

    /** Late UI/IME/model callbacks are ignored; explicit submission uses the strict edit above. */
    @Synchronized fun editIfCurrent(editor: Editor, update: (TaskDraft) -> TaskDraft): TaskDraft? =
        if (isCurrent(editor) && editor.id in mutable.value) edit(editor, update) else null

    @Synchronized fun remove(editor: Editor): TaskDraft? {
        if (!isCurrent(editor)) return null
        val removed = mutable.value[editor.id] ?: return null
        mutable.value = mutable.value - editor.id
        return removed
    }

    /** A failed delete can restore only its still-current editor, never a newer session. */
    @Synchronized fun restore(editor: Editor, removed: TaskDraft) {
        if (isCurrent(editor) && removed.id == editor.id && editor.id !in mutable.value)
            mutable.value = bounded(mutable.value + (removed.id to removed))
    }

    @Synchronized fun end(editor: Editor) {
        if (!isCurrent(editor)) return
        if (mutable.value[editor.id]?.isEmpty == true) mutable.value = mutable.value - editor.id
        leases.remove(editor.id)
    }

    @Synchronized fun clear() { generation++; leases.clear(); mutable.value = emptyMap() }

    @Synchronized fun saved(): JSONObject = JSONObject().put("version", 1).put("drafts",
        JSONArray(mutable.value.values.filterNot { it.isEmpty }.sortedByDescending { it.updatedAt }.take(LIMIT).map { it.json() }))

    private fun timestamp(): Long = maxOf(now(), lastTimestamp + 1).also { lastTimestamp = it }
    private fun bounded(entries: Map<String, TaskDraft>): Map<String, TaskDraft> {
        val retained = entries.values.filterNot { it.isEmpty }.sortedByDescending { it.updatedAt }.take(LIMIT).mapTo(hashSetOf()) { it.id }
        return entries.filterValues { it.isEmpty || it.id in retained }
    }
    companion object {
        const val LIMIT = 20
        private fun readSaved(saved: JSONObject?): Map<String, TaskDraft> {
            if (saved == null) return emptyMap()
            require(saved.getInt("version") == 1) { "Unsupported task draft format" }
            val rows = saved.getJSONArray("drafts")
            val values = (0 until rows.length()).map { TaskDraft.read(rows.getJSONObject(it)) }
            require(values.map { it.id }.distinct().size == values.size) { "Duplicate task draft identity" }
            return values.filterNot { it.isEmpty }.sortedByDescending { it.updatedAt }.take(LIMIT).associateBy { it.id }
        }
    }
}
