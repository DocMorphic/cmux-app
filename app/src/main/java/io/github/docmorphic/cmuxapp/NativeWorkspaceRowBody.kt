package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val nativeMuted = Color(0xFF9B9FA8)
private val nativeAccent = Color(0xFF76B9FF)

internal data class WorkspaceRowVisual(val workspace: NativeWorkspace,
    val preferences: NativeDisplayPreferences, val highlighted: Boolean,
    val changes: WorkspaceChangesChip?, val trailing: String, val indent: Int)

private fun Modifier.workspaceVisualTag(measuring: Boolean, tag: String) = if (measuring) this else testTag(tag)

/** Stateless drawing only: measurement must never create menus, dialogs, jobs or gesture owners. */
@Composable
internal fun NativeWorkspaceRowBody(model: WorkspaceRowVisual, measuring: Boolean,
    modifier: Modifier = Modifier, onChanges: () -> Unit) {
    val workspace = model.workspace
    val displayPreferences = model.preferences
    val highlighted = model.highlighted
    val changesChip = model.changes
    val trailing = model.trailing
    Row(modifier.padding(start = model.indent.dp).padding(horizontal = 18.dp)
        .background(if (highlighted) nativeAccent.copy(alpha = .14f) else Color.Transparent, RoundedCornerShape(14.dp))
        .padding(horizontal = if (highlighted) 10.dp else 0.dp, vertical = 8.dp).workspaceVisualTag(measuring, "workspace.row:${workspace.id}"), verticalAlignment = Alignment.CenterVertically) {
        NativeUnreadGutter(workspace.unreadState)
        val workspaceAccent = workspace.color?.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) }?.drop(1)?.toLongOrNull(16)
        Box(Modifier.width(3.dp).fillMaxHeight().padding(vertical = 5.dp)
            .background(workspaceAccent?.let { Color(0xFF000000L or it).copy(alpha = .95f) } ?: Color.Transparent, RoundedCornerShape(1.5.dp))
            .workspaceVisualTag(measuring, "workspace.color:${workspace.id}"))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (workspace.isPinned) Icon(painterResource(R.drawable.ic_workspace_pin_fill), null, tint = nativeMuted,
                    modifier = Modifier.size(11.dp).alignBy { it.measuredHeight }.workspaceVisualTag(measuring, "workspace.pin:${workspace.id}"))
                Text(workspace.title.ifBlank { "Workspace" }, Modifier.weight(1f).alignByBaseline().workspaceVisualTag(measuring, "workspace.title:${workspace.id}"),
                    fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                    color = if (highlighted) nativeAccent else LocalContentColor.current,
                    maxLines = if (displayPreferences.wrapTitles) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                if (trailing.isNotEmpty()) Text(trailing, Modifier.alignByBaseline().workspaceVisualTag(measuring, "workspace.status:${workspace.id}"),
                    color = nativeMuted, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1)
            }
            workspace.description?.trim()?.takeIf { it.isNotEmpty() }?.let {
                Text(it, Modifier.workspaceVisualTag(measuring, "workspace.description:${workspace.id}"), fontSize = 15.sp, lineHeight = 20.sp,
                    minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(workspace.preview?.takeIf { it.isNotEmpty() } ?: workspace.terminals.firstOrNull()?.title ?: workspace.title,
                    Modifier.weight(1f).workspaceVisualTag(measuring, "workspace.preview:${workspace.id}"), color = nativeMuted, fontSize = 15.sp, lineHeight = 20.sp,
                    minLines = displayPreferences.previewLines, maxLines = displayPreferences.previewLines, overflow = TextOverflow.Ellipsis)
                changesChip?.takeIf { it.files > 0 }?.let { chip ->
                    NativeWorkspaceChangesChip(chip, workspace.id, measuring) { if (!measuring) onChanges() }
                }
            }
        }
    }
}
