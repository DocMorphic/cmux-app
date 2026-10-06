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

internal class TaskAttachmentPreviewModel : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val controller = TaskAttachmentPreviewController(scope)
    override fun onCleared() { controller.close(); scope.cancel() }
}
private fun Context.attachmentPreviewActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.attachmentPreviewActivity()
    else -> null
}

@Composable
internal fun TaskAttachmentPreview(identity: TaskAttachmentPreviewIdentity,
    repository: TaskDraftRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context.attachmentPreviewActivity()
    val owner = checkNotNull(LocalView.current.findViewTreeViewModelStoreOwner())
    val model = remember(owner) { ViewModelProvider(owner)[TaskAttachmentPreviewModel::class.java] }
    val controller = model.controller
    val state by controller.state.collectAsState()
    val attachment = identity.attachment
    LaunchedEffect(controller, identity, repository) {
        val extension = attachment.imageFormat ?: attachment.name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        controller.open(identity, File(context.applicationContext.cacheDir, "task-previews"), mime,
            valid = { repository.ownsAttachment(identity.draft, identity.origin, attachment) },
            read = { repository.readAttachment(identity.draft, identity.origin, attachment) })
    }
    DisposableEffect(controller, identity, activity) {
        onDispose { if (activity?.isChangingConfigurations != true) controller.clear(identity) }
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
