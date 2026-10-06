package io.github.docmorphic.cmuxapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

@Composable
internal fun TaskAttachmentControls(repository: TaskDraftRepository, editor: TaskDrafts.Editor, origin: String,
    attachments: List<ComposerAttachment>, enabled: Boolean, canAdd: Boolean, isCurrent: () -> Boolean, canPreview: () -> Boolean,
    onPreparing: (Boolean) -> Unit, onChanged: () -> Unit, onError: (String) -> Unit,
    content: @Composable (@Composable () -> Unit, @Composable () -> Unit, (TerminalPasteContent) -> Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val files = remember(context) { AttachmentFiles(context.applicationContext, taskFiles = true) }
    val guard by rememberUpdatedState(isCurrent)
    val previewGuard by rememberUpdatedState(canPreview)
    var menu by remember { mutableStateOf(false) }
    val owner = "${repository.session}:${editor.id}:$origin"
    var previewId by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var previewPresentation by rememberSaveable(owner) { mutableStateOf("") }
    val currentOwner by rememberUpdatedState(owner)
    val currentEditor by rememberUpdatedState(editor)
    var pickerOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var staging by remember(owner, editor) { mutableStateOf(false) }
    fun current() = currentOwner == owner && currentEditor == editor && guard()
    fun previewCurrent() = currentOwner == owner && currentEditor == editor && previewGuard()
    fun stage(items: List<TerminalPasteContent.Item.Attachment>, release: () -> Unit = {}, photoLibrary: Boolean = false): Boolean {
        if (items.isEmpty() || staging || !enabled || !canAdd || !current() || !scope.isActive) return false
        val remaining = TaskAttachments.MAX_COUNT - repository.drafts.state.value[editor.id]?.attachments.orEmpty().size
        if (items.size > remaining) { onError("You can attach up to 10 items to a task."); return false }
        staging = true
        onPreparing(true)
        scope.launch {
            try {
                var unreadable = 0
                for ((uri, imageHint) in items) {
                    currentCoroutineContext().ensureActive(); check(current()) { "Task session changed" }
                    val prepared = readComposerAttachment(
                        guard = { check(current()) { "Task session changed" } }, failed = { unreadable++ }
                    ) {
                        val images = if (photoLibrary) files.isPhotoImage(uri) else imageHint
                        if (!images) files.prepare(uri, false, allowEmpty = true) else {
                            try {
                                files.prepare(uri, true, TaskAttachments.IMAGE_BYTES).let { prepared ->
                                    val stem = prepared.attachment.name.substringBeforeLast('.', prepared.attachment.name).trim().ifEmpty { "image" }
                                    prepared.copy(attachment = prepared.attachment.copy(name = "${stem.take(240)}.${prepared.attachment.imageFormat}"))
                                }
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                currentCoroutineContext().ensureActive(); check(current()) { "Task session changed" }
                                // Match iOS: an image that cannot be prepared may still be a general file.
                                files.prepare(uri, false, allowEmpty = true)
                            }
                        }
                    } ?: continue
                    currentCoroutineContext().ensureActive(); check(current()) { "Task session changed" }
                    repository.attach(editor, prepared)
                    currentCoroutineContext().ensureActive()
                    if (current()) onChanged()
                }
                // Successful draft edits clear old errors; report partial failure after them.
                if (unreadable > 0 && current()) onError(
                    "$unreadable attachment${if (unreadable == 1) "" else "s"} couldn't be read. Try adding the missing files again."
                )
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (current()) onError(failure.message ?: "That file couldn’t be read. Choose another file.")
            } finally { staging = false; onPreparing(false) }
        }.invokeOnCompletion { release() }
        return true
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val matches = pickerOwner == owner; pickerOwner = null
        if (matches) stage(uris.map { TerminalPasteContent.Item.Attachment(it, false) })
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        val matches = pickerOwner == owner; pickerOwner = null
        if (matches) stage(uris.map { TerminalPasteContent.Item.Attachment(it, false) }, photoLibrary = true)
    }
    attachments.singleOrNull { it.id == previewId }?.takeIf { previewCurrent() }?.let { attachment ->
        TaskAttachmentPreview(ComposerAttachmentPreviewIdentity(previewPresentation,
            ComposerAttachmentPreviewOwner.Task(repository.session, editor.id, origin), attachment), repository) { previewId = null }
    }
    content({
        if (attachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            attachments.forEach { item ->
                key(item.id) {
                    ComposerAttachmentChip(item, owner, repository::readAttachment, task = true,
                        canPreview = previewCurrent(), canRemove = enabled,
                        removeLabel = "Remove task attachment: ${item.name}",
                        onPreview = {
                            focus.clearFocus(); keyboard?.hide()
                            previewPresentation = java.util.UUID.randomUUID().toString(); previewId = item.id
                        }, onRemove = {
                            onPreparing(true)
                            scope.launch {
                                try { repository.removeAttachment(editor, item.id); onChanged() }
                                catch (failure: Exception) { if (failure is CancellationException) throw failure; onError(failure.message ?: "Could not remove attachment") }
                                finally { onPreparing(false) }
                            }
                        })
                }
            }
        }
    }, {
        if (canAdd) Box {
            TaskComposerCircle("Add task attachment", R.drawable.ic_task_plus, enabled, onClick = { menu = true })
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Photos") }, onClick = {
                    menu = false; focus.clearFocus(); keyboard?.hide(); pickerOwner = owner
                    photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                })
                DropdownMenuItem(text = { Text("Files") }, onClick = {
                    menu = false; focus.clearFocus(); keyboard?.hide(); pickerOwner = owner; picker.launch(arrayOf("*/*"))
                })
                DropdownMenuItem(text = { Text("Paste attachment") }, onClick = {
                    menu = false
                    val action = ComposerClipboardPaste(context, ::current, { enabled && canAdd },
                        receive = { pasted ->
                            stage(pasted.items.filterIsInstance<TerminalPasteContent.Item.Attachment>(), pasted::close)
                        }, report = onError)
                    if (!action.paste()) onError("No copied photos or files. Paste text into the task prompt.")
                })
            }
        }
    }, { pasted ->
        val items = pasted.items.filterIsInstance<TerminalPasteContent.Item.Attachment>()
        items.size == pasted.items.size && stage(items, pasted::close)
    })
}
