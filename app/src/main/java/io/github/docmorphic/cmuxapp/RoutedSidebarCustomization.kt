package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.util.UUID

internal data class RoutedSidebarCustomization(val key: String, val ticket: String, val draft: WorkspaceCustomizationDraft)
internal data class RoutedSidebarCustomizationSave(val key: String, val ticket: String,
    val baseline: WorkspaceCustomizationDraft, val submitted: WorkspaceCustomizationDraft)

/** Keep oversized descriptions read-only without transferring unbounded Mac text. */
internal fun WorkspaceCustomizationDraft.forSidebar(): WorkspaceCustomizationDraft =
    if ((description?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= WORKSPACE_DESCRIPTION_MAX_BYTES) this
    else copy(description = NativeSearchText.prefix(checkNotNull(description), WORKSPACE_DESCRIPTION_MAX_BYTES / 4), descriptionTruncated = true)

/** Lives only in the main process; its closure captures the exact pairing and workspace. */
internal class RoutedSidebarCustomizationEditor(key: String, initial: WorkspaceCustomizationDraft,
    val current: () -> Boolean,
    private val save: suspend (WorkspaceCustomizationDraft, WorkspaceCustomizationDraft, () -> Boolean) -> WorkspaceCustomizationResult) {
    val value = RoutedSidebarCustomization(key, UUID.randomUUID().toString(), initial.forSidebar())
    private var baseline = value.draft
    suspend fun save(command: RoutedSidebarCustomizationSave, canSend: () -> Boolean): WorkspaceCustomizationResult {
        check(command.key == value.key && command.ticket == value.ticket && current() && canSend()) { "Workspace editor changed. Reopen Customize." }
        check(command.baseline == baseline) { "Workspace values changed. Reopen Customize." }
        val result = save(baseline, command.submitted) { current() && canSend() }
        val bounded = result.copy(baseline = result.baseline?.forSidebar(), display = result.display?.forSidebar())
        bounded.baseline?.let { baseline = it }
        return bounded
    }
}

internal object RoutedSidebarCustomizationWire {
    private fun checked(value: String) = value.also {
        require(it.toByteArray(Charsets.UTF_8).size <= RoutedSidebarWire.MAX_BYTES) { "Workspace editor values are too large." }
    }
    private fun token(value: String) = value.also { require(it.length in 1..128 && it.none(Char::isISOControl)) }
    private fun draft(value: WorkspaceCustomizationDraft) = JSONObject().put("name", value.name)
        .put("description", value.description).put("color", value.color).put("pinned", value.pinned).put("truncated", value.descriptionTruncated)
    private fun draft(value: JSONObject) = WorkspaceCustomizationDraft(value.getString("name"),
        if (value.isNull("description")) null else value.getString("description"),
        if (value.isNull("color")) null else value.getString("color"), value.getBoolean("pinned"), value.getBoolean("truncated"))
    fun editor(value: RoutedSidebarCustomization): String = checked(JSONObject().put("key", token(value.key))
        .put("ticket", token(value.ticket)).put("draft", draft(value.draft)).toString())
    fun editor(value: String): RoutedSidebarCustomization = JSONObject(checked(value)).let {
        RoutedSidebarCustomization(token(it.getString("key")), token(it.getString("ticket")), draft(it.getJSONObject("draft")))
    }
    fun save(value: RoutedSidebarCustomizationSave): String = checked(JSONObject().put("key", token(value.key))
        .put("ticket", token(value.ticket)).put("baseline", draft(value.baseline)).put("submitted", draft(value.submitted)).toString())
    fun save(value: String): RoutedSidebarCustomizationSave = JSONObject(checked(value)).let {
        RoutedSidebarCustomizationSave(token(it.getString("key")), token(it.getString("ticket")),
            draft(it.getJSONObject("baseline")), draft(it.getJSONObject("submitted")))
    }
    fun result(value: WorkspaceCustomizationResult): String = checked(JSONObject().put("succeeded", value.succeeded)
        .put("baseline", value.baseline?.let(::draft)).put("display", value.display?.let(::draft)).put("message", value.message).toString())
    fun result(value: String): WorkspaceCustomizationResult = JSONObject(checked(value)).let {
        WorkspaceCustomizationResult(it.getBoolean("succeeded"), it.optJSONObject("baseline")?.let(::draft),
            it.optJSONObject("display")?.let(::draft), if (it.isNull("message")) null else it.getString("message"))
    }
}
