package io.github.docmorphic.cmuxapp

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
internal fun TaskAttachmentControls(repository: TaskDraftRepository, editor: TaskDrafts.Editor, origin: String,
    attachments: List<ComposerAttachment>, enabled: Boolean, canAdd: Boolean, isCurrent: () -> Boolean,
    onPreparing: (Boolean) -> Unit, onChanged: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember(context) { AttachmentFiles(context.applicationContext, taskFiles = true) }
    val guard by rememberUpdatedState(isCurrent)
    var menu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ComposerAttachment?>(null) }
    val owner = "${repository.session}:${editor.id}:$origin"
    var pickerOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerImages by rememberSaveable { mutableStateOf(false) }
    fun stage(uris: List<Uri>, images: Boolean) {
        if (uris.isEmpty() || !guard()) return
        val remaining = TaskAttachments.MAX_COUNT - repository.drafts.state.value[editor.id]?.attachments.orEmpty().size
        if (remaining <= 0) { onError("You can attach up to 10 items to a task."); return }
        onPreparing(true)
        scope.launch {
            try {
                for (uri in uris.take(remaining)) {
                    currentCoroutineContext().ensureActive(); check(guard()) { "Task session changed" }
                    val prepared = if (!images) files.prepare(uri, false, allowEmpty = true) else {
                        try {
                            files.prepare(uri, true, TaskAttachments.IMAGE_BYTES).let { prepared ->
                                val stem = prepared.attachment.name.substringBeforeLast('.', prepared.attachment.name).trim().ifEmpty { "image" }
                                prepared.copy(attachment = prepared.attachment.copy(name = "${stem.take(240)}.${prepared.attachment.imageFormat}"))
                            }
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            // Match iOS: an image that cannot be prepared may still be a general file.
                            files.prepare(uri, false, allowEmpty = true)
                        }
                    }
                    currentCoroutineContext().ensureActive(); check(guard()) { "Task session changed" }
                    repository.attach(editor, prepared)
                    currentCoroutineContext().ensureActive()
                    if (guard()) onChanged()
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (guard()) onError(failure.message ?: "That file couldn’t be read. Choose another file.")
            } finally { onPreparing(false) }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val matches = pickerOwner == owner; pickerOwner = null
        if (matches) stage(uris, pickerImages)
    }
    if (preview != null) TaskAttachmentPreview(checkNotNull(preview), repository) { preview = null }
    Column {
        if (attachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            attachments.forEach { item ->
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp) {
                    Row(Modifier.padding(6.dp)) {
                        Row(Modifier.clickable(enabled = enabled) { preview = item }.padding(6.dp)) {
                            AttachmentThumbnail(item, read = repository::readAttachment)
                            Spacer(Modifier.width(6.dp))
                            Text(item.name, Modifier.widthIn(max = 170.dp), maxLines = 2)
                        }
                        IconButton(onClick = {
                            onPreparing(true)
                            scope.launch {
                                try { repository.removeAttachment(editor, item.id); onChanged() }
                                catch (failure: Exception) { if (failure is CancellationException) throw failure; onError(failure.message ?: "Could not remove attachment") }
                                finally { onPreparing(false) }
                            }
                        }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Remove task attachment: ${item.name}" }) { Text("×") }
                    }
                }
            }
        }
        if (canAdd) Box {
            TextButton(onClick = { menu = true }, enabled = enabled) { Text("＋ Attach") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Photos") }, onClick = { menu = false; pickerOwner = owner; pickerImages = true; picker.launch(arrayOf("image/*")) })
                DropdownMenuItem(text = { Text("Files") }, onClick = { menu = false; pickerOwner = owner; pickerImages = false; picker.launch(arrayOf("*/*")) })
                DropdownMenuItem(text = { Text("Paste attachment") }, onClick = {
                    menu = false
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val data = clip.primaryClip
                    val uris = if (data == null) emptyList() else (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri?.takeIf { uri -> uri.scheme == "content" } }
                    if (uris.isEmpty()) onError("No copied photos or files. Paste text into the task prompt.")
                    else stage(uris, data?.description?.hasMimeType("image/*") == true)
                })
            }
        }
    }
}
