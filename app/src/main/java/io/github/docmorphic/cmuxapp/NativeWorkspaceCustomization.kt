/* Save/rebase policy derived from cmux WorkspaceCustomizationDraft and
 * WorkspaceShellView+WorkspaceActions at 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val WORKSPACE_METADATA_CAPABILITY = "workspace.metadata.v1"
internal const val WORKSPACE_DESCRIPTION_MAX_BYTES = 4096
internal fun normalizedWorkspaceDescription(value: String?): String? = value
    ?.replace("\r\n", "\n")?.replace('\r', '\n')?.trim()?.takeIf { it.isNotEmpty() }

internal enum class WorkspaceCustomizationField { NAME, DESCRIPTION, COLOR, PINNED }
internal data class WorkspaceCustomizationDraft(
    val name: String, val description: String?, val color: String?, val pinned: Boolean,
    val descriptionTruncated: Boolean = false
) {
    fun normalized() = copy(name = name.trim(), description = normalizedWorkspaceDescription(description),
        color = color?.trim()?.uppercase()?.takeIf { it.isNotEmpty() })
    fun value(field: WorkspaceCustomizationField): Any? = when (field) {
        WorkspaceCustomizationField.NAME -> name
        WorkspaceCustomizationField.DESCRIPTION -> description
        WorkspaceCustomizationField.COLOR -> color
        WorkspaceCustomizationField.PINNED -> pinned
    }
    fun retainingEdits(baseline: WorkspaceCustomizationDraft, latest: WorkspaceCustomizationDraft) = copy(
        name = if (name != baseline.name) name else latest.name,
        description = if (description != baseline.description) description else latest.description,
        descriptionTruncated = if (description != baseline.description) false else latest.descriptionTruncated,
        color = if (color != baseline.color) color else latest.color,
        pinned = if (pinned != baseline.pinned) pinned else latest.pinned)
    fun validate(baseline: WorkspaceCustomizationDraft) {
        require(name.isNotBlank()) { "Enter a workspace name." }
        if (description != baseline.description) {
            require(!baseline.descriptionTruncated) { truncatedMessage }
            require((description?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= WORKSPACE_DESCRIPTION_MAX_BYTES) {
                "Description must be 4 KB or less."
            }
        }
        if (color != baseline.color) require(color == null || Regex("#[0-9A-F]{6}").matches(color)) {
            "Use a color in #RRGGBB format."
        }
    }
    companion object {
        const val truncatedMessage = "This Mac description is longer than Android can edit. Change it on Mac to avoid losing text."
        fun from(workspace: NativeWorkspace) = WorkspaceCustomizationDraft(workspace.title,
            workspace.description, workspace.color, workspace.isPinned, workspace.descriptionTruncated ||
                (normalizedWorkspaceDescription(workspace.description)?.toByteArray(Charsets.UTF_8)?.size ?: 0) > WORKSPACE_DESCRIPTION_MAX_BYTES).normalized()
    }
}

internal data class WorkspaceCustomizationResult(val succeeded: Boolean,
    val baseline: WorkspaceCustomizationDraft? = null, val display: WorkspaceCustomizationDraft? = null,
    val message: String? = null)

/** Owner supplies fresh reads and at-most-once writes. Never retry a mutation automatically. */
internal suspend fun saveWorkspaceCustomization(
    baseline: WorkspaceCustomizationDraft, submitted: WorkspaceCustomizationDraft,
    read: suspend () -> WorkspaceCustomizationDraft,
    write: suspend (WorkspaceCustomizationField, WorkspaceCustomizationDraft) -> Unit
): WorkspaceCustomizationResult {
    val initial = baseline.normalized()
    val draft = submitted.normalized()
    var latest: WorkspaceCustomizationDraft? = null
    try {
        draft.validate(initial)
        for (field in WorkspaceCustomizationField.entries) {
            currentCoroutineContext().ensureActive()
            if (draft.value(field) == initial.value(field)) continue
            val fresh = read().normalized().also { latest = it }
            currentCoroutineContext().ensureActive()
            if (draft.value(field) == fresh.value(field)) continue
            if (fresh.value(field) != initial.value(field)) return WorkspaceCustomizationResult(false, fresh, fresh,
                "This workspace changed on your Mac. Review the latest values and save again.")
            if (field == WorkspaceCustomizationField.DESCRIPTION && fresh.descriptionTruncated)
                return WorkspaceCustomizationResult(false, fresh, fresh, WorkspaceCustomizationDraft.truncatedMessage)
            write(field, draft)
            latest = read().normalized()
        }
        currentCoroutineContext().ensureActive()
        return WorkspaceCustomizationResult(true)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        recordWorkspaceActionFailure(failure)
        // Even a rejected/timed-out request may have landed on the Mac.
        try { latest = read().normalized() } catch (refreshFailure: Exception) {
            if (refreshFailure is CancellationException) throw refreshFailure
        }
        return WorkspaceCustomizationResult(false, latest, latest?.let { draft.retainingEdits(initial, it) },
            failure.message ?: "Could not save this workspace. Refresh and try again.")
    }
}
