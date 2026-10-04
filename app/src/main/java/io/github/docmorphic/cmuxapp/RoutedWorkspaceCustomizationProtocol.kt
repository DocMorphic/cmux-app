package io.github.docmorphic.cmuxapp

import android.os.Bundle

internal typealias RoutedWorkspaceCustomizationSave = suspend (WorkspaceCustomizationDraft, WorkspaceCustomizationDraft) -> WorkspaceCustomizationResult

/** Only editor values cross IPC. Workspace/account identity stays bound to the host session. */
internal object RoutedWorkspaceCustomizationProtocol {
    fun draft(value: WorkspaceCustomizationDraft) = Bundle().apply {
        putString("name", value.name); putString("description", value.description); putString("color", value.color)
        putBoolean("pinned", value.pinned); putBoolean("truncated", value.descriptionTruncated)
    }
    fun draft(value: Bundle) = WorkspaceCustomizationDraft(checkNotNull(value.getString("name")),
        value.getString("description"), value.getString("color"), value.getBoolean("pinned"), value.getBoolean("truncated"))
    fun result(value: WorkspaceCustomizationResult) = Bundle().apply {
        putBoolean("succeeded", value.succeeded); putString("message", value.message)
        putBundle("baseline", value.baseline?.let(::draft)); putBundle("display", value.display?.let(::draft))
    }
    fun result(value: Bundle) = WorkspaceCustomizationResult(value.getBoolean("succeeded"),
        value.getBundle("baseline")?.let(::draft), value.getBundle("display")?.let(::draft), value.getString("message"))
}
