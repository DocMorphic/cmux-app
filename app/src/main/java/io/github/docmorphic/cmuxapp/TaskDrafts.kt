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
    val lastRequest: String? = null,
    val completedRequest: String? = null,
    val templateId: String? = null, val templateName: String? = null, val templateCommand: String? = null,
    val didEditDirectory: Boolean = false,
    val workspaceName: String = "", val groupId: String? = null,
    val lastRequestOrigin: String? = null, val completedOrigin: String? = null,
    val attachments: List<ComposerAttachment> = emptyList()
) {
    val command get() = templateCommand ?: agent.command
    fun selecting(template: TaskTemplate, suggestedDirectory: String): TaskDraft {
        val resetModel = (templateId ?: TaskTemplate.builtInId(agent)) != template.id ||
            command?.let(TaskAgentCommand::detect) != TaskAgentCommand.detect(template.command)
        return copy(agent = template.builtInKind ?: agent, templateId = template.id, templateName = template.name,
            templateCommand = template.command, directory = if (didEditDirectory) directory else suggestedDirectory,
            selection = if (resetModel) TaskModelSelection() else selection,
            defaultModel = if (resetModel) null else defaultModel)
    }
    val isEmpty get() = prompt.isBlank() && workspaceName.isBlank() && attachments.isEmpty() && lastRequest == null && completedRequest == null
    val title get() = workspaceName.trim().takeIf { it.isNotEmpty() } ?: prompt.trim().lineSequence().firstOrNull()?.takeIf { it.isNotEmpty() } ?: "Untitled task"
    fun onMac(nextOrigin: String, name: String, suggestedDirectory: String) = copy(origin = nextOrigin, macName = name,
        groupId = if (nextOrigin == origin) groupId else null,
        directory = if (nextOrigin == origin && didEditDirectory) directory else suggestedDirectory,
        didEditDirectory = nextOrigin == origin && didEditDirectory,
        selection = if (nextOrigin == origin) selection else TaskModelSelection(),
        defaultModel = if (nextOrigin == origin) defaultModel else null,
        lastRequestOrigin = if (lastRequest != null) lastRequestOrigin ?: origin else null,
        completedOrigin = if (completedRequest != null) completedOrigin ?: origin else null)
    fun restoredModels() = TaskModelResult(listOfNotNull(selection.explicit), TaskModelSource.FALLBACK, defaultModel)
    fun reconcileModels(provider: TaskAgentCommand?, result: TaskModelResult?): TaskDraft {
        if (command?.let(TaskAgentCommand::detect) != provider) return this
        val reconciled = selection.reconcile(result)
        return copy(selection = reconciled.copy(explicit = reconciled.model(result)), defaultModel = result?.defaultModel)
    }

    fun json(): JSONObject = JSONObject().put("id", id).put("origin", origin).put("mac_name", macName)
        .put("updated_at", updatedAt).put("agent", agent.name).put("prompt", prompt).put("directory", directory)
        .put("model", selection.explicit?.let(::modelJson)).put("effort", selection.effortId)
        .put("default_model", defaultModel?.let(::modelJson)).put("last_request", lastRequest?.let(::JSONObject))
        .put("completed_request", completedRequest?.let(::JSONObject))
        .put("template_id", templateId).put("template_name", templateName).put("template_command", templateCommand)
        .put("did_edit_directory", didEditDirectory)
        .put("workspace_name", workspaceName).put("group_id", groupId)
        .put("last_request_origin", lastRequestOrigin).put("completed_origin", completedOrigin)
        .put("attachments", JSONArray(attachments.map { it.json() }))

    companion object {
        internal fun modelJson(model: TaskModel): JSONObject = JSONObject().put("id", model.id)
            .put("display_name", model.name).put("default_effort_id", model.defaultEffortId)
            .put("efforts", JSONArray(model.efforts.map { effort -> JSONObject().put("id", effort.id)
                .put("display_name", effort.name).put("description", effort.description) }))
        internal fun model(raw: JSONObject?): TaskModel? = raw?.let {
            TaskModelParser.host(JSONObject().put("source", "discovered").put("models", JSONArray().put(it))).models.single()
        }
        fun read(raw: JSONObject): TaskDraft {
            val id = raw.getString("id"); UUID.fromString(id)
            val origin = raw.getString("origin"); require(origin.isNotBlank())
            val request = raw.optJSONObject("last_request")?.also { UUID.fromString(it.getString("operation_id")) }
            val completed = raw.optJSONObject("completed_request")?.also {
                UUID.fromString(it.getString("operation_id"))
                require(request != null && request.getString("operation_id") != it.getString("operation_id")) {
                    "Completed task needs a retired retry identity"
                }
            }
            return TaskDraft(id, origin, raw.getString("mac_name"), raw.getLong("updated_at"),
                TaskCommand.Agent.valueOf(raw.getString("agent")), raw.getString("prompt"), raw.getString("directory"),
                TaskModelSelection(model(raw.optJSONObject("model")), raw.opt("effort") as? String),
                model(raw.optJSONObject("default_model")), request?.toString(), completed?.toString(),
                (raw.opt("template_id") as? String)?.also { UUID.fromString(it) }, raw.opt("template_name") as? String,
                raw.opt("template_command") as? String, raw.optBoolean("did_edit_directory", true),
                raw.opt("workspace_name") as? String ?: "", (raw.opt("group_id") as? String)?.also { require(it.isNotBlank()) },
                (raw.opt("last_request_origin") as? String)?.also { require(it.isNotBlank()) },
                (raw.opt("completed_origin") as? String)?.also { require(it.isNotBlank()) },
                TaskAttachments.read(raw.optJSONArray("attachments")))
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

    /** Explicit Mac selection is the only operation allowed to change a draft's owner. */
    @Synchronized fun retarget(editor: Editor, draft: TaskDraft) {
        check(isCurrent(editor) && mutable.value.containsKey(editor.id)) { "Task session changed" }
        require(draft.id == editor.id && draft.origin.isNotBlank())
        mutable.value = bounded(mutable.value + (draft.id to draft.copy(updatedAt = timestamp())))
        leases.remove(editor.id)
    }

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

    // A durable edit must count as newest before applying the 20-draft limit,
    // including when an old empty editor gets its first attachment.
    @Synchronized fun saved(replacement: TaskDraft? = null): JSONObject = JSONObject().put("version", 1).put("drafts",
        JSONArray((if (replacement == null) mutable.value else mutable.value + (replacement.id to replacement.copy(updatedAt = timestamp()))).values
            .filterNot { it.isEmpty }.sortedByDescending { it.updatedAt }.take(LIMIT).map { it.json() }))

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
