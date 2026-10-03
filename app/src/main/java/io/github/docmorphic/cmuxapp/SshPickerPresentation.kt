package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

internal enum class SshPickerOperation { WORKSPACE, TERMINAL, BROWSER, SECTION }
internal data class SshPickerCommand(val operation: SshPickerOperation, val section: Int? = null, val action: SshPaneAction? = null) {
    fun encode() = JSONObject().put("operation", operation.name).put("section", section).put("action", action?.name).toString()
    companion object {
        fun decode(raw: String?): SshPickerCommand? = runCatching {
            require(raw != null && raw.length <= 512)
            val value = JSONObject(raw)
            val operation = SshPickerOperation.valueOf(value.getString("operation"))
            val section = if (value.isNull("section")) null else value.get("section").let {
                require(it is Int && it >= 0); it
            }
            val action = if (value.isNull("action")) null else SshPaneAction.valueOf(value.getString("action"))
            require(if (operation == SshPickerOperation.SECTION) section != null && action != null else section == null && action == null)
            SshPickerCommand(operation, section, action)
        }.getOrNull()
    }
}

/** Credential-free menu metadata. The host validates returned commands against its current copy. */
internal data class SshPickerPresentation(val layout: SshPickerLayout, val enabled: Boolean, val creationEnabled: Boolean) {
    fun permits(command: SshPickerCommand) = enabled && creationEnabled && when (command.operation) {
        SshPickerOperation.WORKSPACE, SshPickerOperation.BROWSER -> true
        SshPickerOperation.TERMINAL -> layout.newTerminalTitle != null
        SshPickerOperation.SECTION -> layout.sections.singleOrNull { it.id == command.section }?.actions?.contains(command.action) == true
    }
    fun encode(): String {
        fun row(value: SshPickerRow) = JSONObject().put("target", value.target.encode()).put("title", value.title)
            .put("pane", value.paneLabel).put("starts", value.startsPane)
        return JSONObject().put("enabled", enabled).put("creation", creationEnabled).put("new_terminal", layout.newTerminalTitle)
            .put("sections", JSONArray().also { sections -> layout.sections.forEach { section ->
                sections.put(JSONObject().put("id", section.id).put("title", section.title).put("pane", section.targetPane)
                    .put("actions", JSONArray(section.actions.map { it.name })).put("rows", JSONArray().also { rows -> section.rows.forEach { rows.put(row(it)) } }))
            } }).put("browsers", JSONArray().also { rows -> layout.browsers.forEach { rows.put(row(it)) } }).toString()
    }
    companion object {
        fun decode(raw: String?): SshPickerPresentation? = runCatching {
            require(raw != null && raw.length <= 512_000)
            val value = JSONObject(raw)
            fun optional(value: JSONObject, key: String) = if (value.isNull(key)) null else value.getString(key)
            fun rows(array: JSONArray): List<SshPickerRow> {
                require(array.length() <= 1000)
                return (0 until array.length()).map { i -> array.getJSONObject(i).let {
                    SshPickerRow(checkNotNull(SshWorkspaceTarget.decode(it.getString("target"))), it.getString("title"), optional(it, "pane"), it.getBoolean("starts"))
                } }
            }
            val sections = value.getJSONArray("sections"); require(sections.length() <= 1000)
            val layout = SshPickerLayout((0 until sections.length()).map { i -> sections.getJSONObject(i).let { section ->
                val actions = section.getJSONArray("actions"); require(actions.length() <= SshPaneAction.entries.size)
                SshPickerSection(section.getInt("id"), section.getString("title"), rows(section.getJSONArray("rows")),
                    if (section.isNull("pane")) null else section.getInt("pane"), (0 until actions.length()).map { SshPaneAction.valueOf(actions.getString(it)) })
            } }, rows(value.getJSONArray("browsers")), optional(value, "new_terminal"))
            require(layout.sections.map { it.id }.distinct().size == layout.sections.size)
            SshPickerPresentation(layout, value.getBoolean("enabled"), value.getBoolean("creation"))
        }.getOrNull()
    }
}
