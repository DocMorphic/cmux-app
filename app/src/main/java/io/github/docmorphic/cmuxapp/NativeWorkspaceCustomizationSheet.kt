package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal val LocalWorkspaceCustomizationAction = staticCompositionLocalOf<((NativeWorkspace) -> Unit)?> { null }
// iOS retains the owning Mac's capability snapshot through transport outages.
// Discovery is stable; the coordinator requires a live verified owner for every write.
internal fun NativeFeedSource.canCustomizeWorkspace() =
    WORKSPACE_METADATA_CAPABILITY in capabilities && "workspace.actions.v1" in capabilities
internal data class WorkspaceCustomizationTarget(val origin: String, val workspaceId: String)
internal val workspaceCustomizationTargetSaver = listSaver<WorkspaceCustomizationTarget?, String>(
    save = { it?.let { target -> listOf(target.origin, target.workspaceId) }.orEmpty() },
    restore = { if (it.size == 2) WorkspaceCustomizationTarget(it[0], it[1]) else null })
private val draftSaver = listSaver<WorkspaceCustomizationDraft, String>(
    save = { listOf(it.name, it.description.orEmpty(), it.color.orEmpty(), it.pinned.toString(), it.descriptionTruncated.toString()) },
    restore = { WorkspaceCustomizationDraft(it[0], it[1].ifEmpty { null }, it[2].ifEmpty { null }, it[3].toBoolean(), it[4].toBoolean()) })

@Composable
internal fun NativeWorkspaceCustomizationSheet(workspace: NativeWorkspace, onDismiss: () -> Unit,
    save: suspend (WorkspaceCustomizationDraft, WorkspaceCustomizationDraft) -> WorkspaceCustomizationResult) {
    NativeWorkspaceCustomizationSheet(workspace.id, WorkspaceCustomizationDraft.from(workspace), onDismiss, save)
}

@Composable
internal fun NativeWorkspaceCustomizationSheet(workspaceId: String, initial: WorkspaceCustomizationDraft, onDismiss: () -> Unit,
    save: suspend (WorkspaceCustomizationDraft, WorkspaceCustomizationDraft) -> WorkspaceCustomizationResult) {
    var baseline by rememberSaveable(workspaceId, stateSaver = draftSaver) { mutableStateOf(initial) }
    var draft by rememberSaveable(workspaceId, stateSaver = draftSaver) { mutableStateOf(baseline) }
    var selectedColor by rememberSaveable(workspaceId) { mutableStateOf(draft.color ?: "#007AFF") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val validation = runCatching { draft.normalized().validate(baseline) }.exceptionOrNull()?.message
    val canSave = !busy && validation == null && draft.normalized() != baseline
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(
        dismissOnBackPress = !busy, dismissOnClickOutside = !busy, usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                    Text("Customize Workspace", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(enabled = canSave, modifier = Modifier.testTag("workspace.customize.save"), onClick = saveClick@{
                        if (busy) return@saveClick
                        busy = true; failure = null
                        val submitted = draft.normalized()
                        scope.launch {
                            try {
                                val result = save(baseline, submitted)
                                if (result.succeeded) onDismiss()
                                else {
                                    result.baseline?.let { latest -> baseline = latest; draft = result.display ?: latest; selectedColor = draft.color ?: "#007AFF" }
                                    failure = result.message ?: "Could not save this workspace. Try again."
                                }
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                recordWorkspaceActionFailure(error)
                                failure = error.message ?: "Could not save this workspace. Try again."
                            } finally { busy = false }
                        }
                    }) { Text(if (busy) "Saving…" else "Save") }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Identity", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, enabled = !busy,
                        label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("workspace.customize.name"))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Pinned", Modifier.weight(1f))
                        Switch(draft.pinned, { draft = draft.copy(pinned = it) }, enabled = !busy,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White),
                            modifier = Modifier.semantics { contentDescription = "Pinned" })
                    }
                    Text("Description", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(draft.description.orEmpty(), { draft = draft.copy(description = it) },
                        enabled = !busy && !baseline.descriptionTruncated, minLines = 3, maxLines = 8,
                        placeholder = { Text("What is this workspace for?") },
                        modifier = Modifier.fillMaxWidth().testTag("workspace.customize.description"))
                    Text("Shown in the workspace list above live activity.", style = MaterialTheme.typography.bodySmall)
                    if (baseline.descriptionTruncated) Text(WorkspaceCustomizationDraft.truncatedMessage,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("workspace.customize.truncated"))
                    Text("Appearance", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Use Workspace Color", Modifier.weight(1f))
                        Switch(draft.color != null, { draft = draft.copy(color = if (it) selectedColor else null) }, enabled = !busy,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White),
                            modifier = Modifier.semantics { contentDescription = "Use Workspace Color" })
                    }
                    if (draft.color != null) WorkspaceColorEditor(draft.color!!, !busy) {
                        selectedColor = it; draft = draft.copy(color = it)
                    }
                    validation?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("workspace.customize.validation")) }
                }
            }
        }
        failure?.let { message -> AlertDialog(onDismissRequest = { failure = null },
            title = { Text("Couldn't save workspace") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("OK") } }) }
    }
}

/** RGB sliders plus exact hex entry provide all opaque colors without a platform ColorPicker. */
@Composable
private fun WorkspaceColorEditor(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val rgb = value.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).background(Color(0xFF000000L or (rgb ?: 0x007AFF))))
        OutlinedTextField(value, onChange, enabled = enabled, singleLine = true,
            label = { Text("Color (#RRGGBB)") }, modifier = Modifier.weight(1f).testTag("workspace.customize.color"))
    }
    listOf("Red" to 16, "Green" to 8, "Blue" to 0).forEach { (label, shift) ->
        val color = rgb ?: 0x007AFF
        Text(label, style = MaterialTheme.typography.bodySmall)
        Slider(value = ((color shr shift) and 255).toFloat(), valueRange = 0f..255f, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label }, onValueChange = { channel ->
                val updated = (color and (255L shl shift).inv()) or (channel.toLong() shl shift)
                onChange("#%06X".format(updated))
            })
    }
}
