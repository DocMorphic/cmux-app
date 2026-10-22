package io.github.docmorphic.cmuxapp

import androidx.compose.ui.res.stringResource

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskDraftsSheet(
    drafts: List<TaskDraft>, busy: Boolean, error: String?, onDismiss: () -> Unit, onNew: () -> Unit,
    onResume: (TaskDraft) -> Unit, onDelete: (TaskDraft) -> Unit
) {
    NativeModalBottomSheet(onDismissRequest = onDismiss, containerColor = NativeAppColors.panel) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            TextButton(onClick = onNew, enabled = !busy) { Text("＋ New Draft") }
            Text("Drafts", Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cmux_ui_terminal_artifact_gallery_done_ca8c7883)) }
        }
        error?.let { Text(it, color = NativeAppColors.error, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        if (drafts.isEmpty()) Column(Modifier.fillMaxWidth().padding(28.dp)) {
            Text("No Other Drafts", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Leave the composer with an unsent task and save it here.", color = NativeAppColors.muted)
        } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(drafts.sortedByDescending { it.updatedAt }, key = { it.id }) { draft ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                    Column(Modifier.weight(1f).clickable(enabled = !busy) { onResume(draft) }.padding(vertical = 14.dp)) {
                        Text(draft.title, maxLines = 2, style = MaterialTheme.typography.titleMedium)
                        Text(listOf(draft.templateName ?: draft.agent.label, draft.macName, draft.directory).filter { it.isNotBlank() }.joinToString(" · "),
                            maxLines = 1, color = NativeAppColors.muted, style = MaterialTheme.typography.bodySmall)
                        Text(DateUtils.getRelativeTimeSpanString(draft.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                            color = NativeAppColors.muted, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { onDelete(draft) }, enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = "Delete draft: ${draft.title}" }) {
                        Text("Delete", color = NativeAppColors.error)
                    }
                }
                HorizontalDivider(color = NativeAppColors.line)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
