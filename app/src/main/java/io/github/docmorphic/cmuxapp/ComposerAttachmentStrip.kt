package io.github.docmorphic.cmuxapp

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun NativeTerminalAttachmentStrip(repository: TerminalDraftRepository, target: TerminalDrafts.Target,
    attachments: List<ComposerAttachment>, canRemove: Boolean, preparing: Boolean, modifier: Modifier = Modifier,
    beforePreview: () -> Unit) {
    val generation = repository.drafts.generation
    ComposerAttachmentStrip(ComposerAttachmentPreviewOwner.Terminal(target, generation), repository,
        attachments, canRemove, if (preparing) "Preparing…" else null, modifier, beforePreview,
        owns = { repository.drafts.ownsAttachment(target, it, generation) },
        read = { repository.read(target, it, generation) },
        remove = { if (repository.drafts.ownsAttachment(target, it, generation)) repository.drafts.removeAttachment(target, it.id) })
}

@Composable
internal fun SshTerminalAttachmentStrip(draft: SshComposerPool.Draft, attachments: List<ComposerAttachment>,
    canRemove: Boolean, preparing: Boolean, modifier: Modifier = Modifier, beforePreview: () -> Unit) {
    ComposerAttachmentStrip(ComposerAttachmentPreviewOwner.Ssh(draft.previewBinding), draft,
        attachments, canRemove, if (preparing) { if (draft.current.operation != null) "Sending…" else "Preparing…" } else null,
        modifier, beforePreview, draft::ownsAttachment,
        read = { withContext(Dispatchers.Default) { draft.read(it) } }, remove = { draft.remove(it.id) })
}

@Composable
private fun ComposerAttachmentStrip(owner: ComposerAttachmentPreviewOwner, source: Any,
    attachments: List<ComposerAttachment>, canRemove: Boolean, progress: String?, modifier: Modifier,
    beforePreview: () -> Unit, owns: (ComposerAttachment) -> Boolean,
    read: suspend (ComposerAttachment) -> ByteArray, remove: (ComposerAttachment) -> Unit) {
    var selected by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var presentation by rememberSaveable(owner) { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    attachments.singleOrNull { it.id == selected }?.takeIf(owns)?.let { attachment ->
        ComposerAttachmentPreview(ComposerAttachmentPreviewIdentity(presentation, owner, attachment), source,
            valid = { owns(attachment) }, read = { read(attachment) }, onDismiss = { selected = null })
    }
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { item ->
            key(item.id) {
                ComposerAttachmentChip(item, owner, read, task = false, canPreview = owns(item), canRemove = canRemove && owns(item),
                    removeLabel = "Remove ${item.name}", onPreview = {
                        beforePreview(); focus.clearFocus(); keyboard?.hide()
                        presentation = UUID.randomUUID().toString(); selected = item.id
                    }, onRemove = { remove(item) })
            }
        }
        progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Separate preview and removal hit targets, with image dimensions matching the iOS composers. */
@Composable
internal fun ComposerAttachmentChip(attachment: ComposerAttachment, owner: Any,
    read: suspend (ComposerAttachment) -> ByteArray, task: Boolean, canPreview: Boolean,
    canRemove: Boolean, removeLabel: String, onPreview: () -> Unit, onRemove: () -> Unit) {
    val foreground = LocalContentColor.current
    val shape = RoundedCornerShape(10.dp)
    val height = if (task) 72.dp else 56.dp
    Box(Modifier.testTag("composer.attachment.${attachment.id}").padding(top = 12.dp, end = 12.dp)) {
        Box(Modifier.clip(shape).background(foreground.copy(alpha = 0.07f))
            .border(1.dp, foreground.copy(alpha = 0.15f), shape)
            .clickable(enabled = canPreview, role = Role.Button, onClickLabel = "Preview attachment", onClick = onPreview)
            .semantics(mergeDescendants = true) {
                contentDescription = attachment.name
                customActions = if (canRemove) listOf(CustomAccessibilityAction(removeLabel) { onRemove(); true }) else emptyList()
            }, contentAlignment = Alignment.Center) {
            if (attachment.imageFormat != null) {
                AttachmentThumbnail(attachment, read, Modifier.size(if (task) 96.dp else 56.dp, height).clearAndSetSemantics { },
                    if (task) ContentScale.Fit else ContentScale.Crop, owner)
            } else Row(Modifier.height(height).widthIn(max = if (task) 190.dp else 240.dp)
                .padding(start = 10.dp, end = 28.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("▤", Modifier.clearAndSetSemantics { })
                Column {
                    Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = if (task) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelMedium)
                    if (!task) Text(Formatter.formatShortFileSize(LocalContext.current, attachment.size.toLong()),
                        style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = 0.6f))
                }
            }
        }
        IconButton(onClick = onRemove, enabled = canRemove, modifier = Modifier.align(Alignment.TopEnd)
            .offset(x = 12.dp, y = (-12).dp).size(44.dp).semantics { contentDescription = removeLabel }) {
            Box(Modifier.size(22.dp).background(Color.Black.copy(alpha = 0.65f), CircleShape), contentAlignment = Alignment.Center) {
                Text("×", color = Color.White.copy(alpha = if (canRemove) 1f else 0.4f))
            }
        }
    }
}
