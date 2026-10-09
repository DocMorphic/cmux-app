package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Pairing-scoped choices, separate from prompt/attachments and submission recovery. */
internal data class TaskPickerPreferences(
    val templateId: String,
    val selection: TaskModelSelection,
    val defaultModel: TaskModel?,
    val directory: String,
    val didEditDirectory: Boolean,
    val groupId: String?
) {
    fun json() = JSONObject().put("template_id", templateId)
        .put("model", selection.explicit?.let(TaskDraft::modelJson))
        .put("effort", selection.effortId).put("default_model", defaultModel?.let(TaskDraft::modelJson))
        .put("directory", directory).put("did_edit_directory", didEditDirectory).put("group_id", groupId)

    companion object {
        fun from(draft: TaskDraft) = TaskPickerPreferences(checkNotNull(draft.templateId), draft.selection,
            draft.defaultModel, draft.directory, draft.didEditDirectory, draft.groupId)
        fun read(raw: JSONObject) = TaskPickerPreferences(raw.getString("template_id"),
            TaskModelSelection(TaskDraft.model(raw.optJSONObject("model")), raw.opt("effort") as? String),
            TaskDraft.model(raw.optJSONObject("default_model")), raw.getString("directory"),
            raw.getBoolean("did_edit_directory"), raw.opt("group_id") as? String)
    }
}

internal fun TaskTemplateState.rememberingPickers(draft: TaskDraft): TaskTemplateState {
    if (draft.origin.isBlank() || entries.none { it.id == draft.templateId }) return this
    return copy(lastOrigin = draft.origin, pickers = pickers + (draft.origin to TaskPickerPreferences.from(draft)))
}

/** New task: remembered -> last -> first. Mac switch: remembered -> previous -> last -> first. */
internal fun TaskTemplateState.restorePickers(
    draft: TaskDraft, openDirectory: String?, previousTemplateId: String? = null
): TaskDraft {
    val remembered = pickers[draft.origin]
    val template = entries.firstOrNull { it.id == remembered?.templateId }
        ?: selected(previousTemplateId)
    val matching = remembered?.takeIf { it.templateId == template.id }
    val typed = remembered?.didEditDirectory == true
    return draft.copy(didEditDirectory = false).selecting(template,
        if (typed) remembered!!.directory else suggestedDirectory(template, draft.origin, openDirectory))
        .copy(selection = matching?.selection ?: TaskModelSelection(), defaultModel = matching?.defaultModel,
            didEditDirectory = typed, groupId = remembered?.groupId)
}
