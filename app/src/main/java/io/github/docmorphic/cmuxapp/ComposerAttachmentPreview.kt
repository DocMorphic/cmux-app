package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import kotlinx.coroutines.*
import java.io.File

internal class ComposerAttachmentPreviewModel : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val controller = ComposerAttachmentPreviewController(scope)
    val selections = ComposerAttachmentSelections()
    fun select(owner: ComposerAttachmentPreviewOwner, snapshot: ComposerAttachmentSnapshot, presentation: String) {
        val previous = selections.state.value
        if (selections.select(owner, snapshot, presentation) != null) previous?.let { controller.clear(it.identity) }
    }
    fun dismiss(identity: ComposerAttachmentPreviewIdentity) {
        controller.clear(identity); selections.clear(identity)
    }
    fun dismissOwner(owner: ComposerAttachmentPreviewOwner) {
        selections.state.value?.takeIf { it.identity.owner == owner }?.let { dismiss(it.identity) }
    }
    override fun onCleared() { controller.close(); selections.close(); scope.cancel() }
}
internal fun Context.attachmentPreviewActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.attachmentPreviewActivity()
    else -> null
}

@Composable
internal fun rememberComposerAttachmentPreviewModel(): ComposerAttachmentPreviewModel {
    val owner = checkNotNull(LocalView.current.findViewTreeViewModelStoreOwner())
    return remember(owner) { ViewModelProvider(owner)[ComposerAttachmentPreviewModel::class.java] }
}

@Composable
internal fun TaskAttachmentPreview(identity: ComposerAttachmentPreviewIdentity,
    repository: TaskDraftRepository, onDismiss: () -> Unit) {
    val owner = identity.owner as ComposerAttachmentPreviewOwner.Task
    ComposerAttachmentPreview(identity, repository,
        valid = { repository.session == owner.session && repository.ownsAttachment(owner.draft, owner.origin, identity.attachment) },
        read = { repository.readAttachment(owner.draft, owner.origin, identity.attachment) }, onDismiss)
}

@Composable
internal fun ComposerAttachmentPreview(identity: ComposerAttachmentPreviewIdentity, source: Any,
    valid: () -> Boolean, read: suspend () -> ByteArray, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context.attachmentPreviewActivity()
    val model = rememberComposerAttachmentPreviewModel()
    val controller = model.controller
    val state by controller.state.collectAsState()
    val attachment = identity.attachment
    LaunchedEffect(controller, identity, source) {
        val extension = attachment.imageFormat ?: attachment.name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        controller.open(identity, File(context.applicationContext.cacheDir, "task-previews"), mime, valid, read)
    }
    DisposableEffect(controller, identity, activity) {
        onDispose { if (activity?.isChangingConfigurations != true) model.dismiss(identity) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(attachment.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                HorizontalDivider()
                val visible = state.takeIf { it.identity == identity }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        visible?.artifact != null -> FilePreviewContent(visible.artifact)
                        visible?.error != null -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(visible.error, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { controller.retry(identity) }) { Text("Retry") }
                        }
                        else -> CircularProgressIndicator()
                    }
                }
            }
        }
    }
}
