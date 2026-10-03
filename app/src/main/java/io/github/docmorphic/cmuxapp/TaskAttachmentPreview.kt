package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

@Composable
internal fun TaskAttachmentPreview(attachment: ComposerAttachment, repository: TaskDraftRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<File?>(null) }
    val image = produceState<android.graphics.Bitmap?>(null, attachment.id) {
        if (attachment.imageFormat != null) try {
            val bytes = repository.readAttachment(attachment)
            value = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        } catch (error: Exception) { if (error is CancellationException) throw error; failure = error.message }
    }.value
    DisposableEffect(Unit) { onDispose { exported?.deleteRecursively() } }
    val viewer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        exported?.deleteRecursively(); exported = null; opening = false
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(attachment.name) }, text = {
        Column {
            if (image != null) Image(image.asImageBitmap(), attachment.name, Modifier.fillMaxWidth().heightIn(max = 350.dp))
            Text("${attachment.size} bytes")
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !opening, onClick = {
        opening = true; failure = null
        scope.launch {
            var created: File? = null
            try {
                val bytes = repository.readAttachment(attachment)
                val file = withContext(Dispatchers.IO) {
                    val root = File(context.cacheDir, "task-previews").apply { mkdirs() }
                    root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.deleteRecursively() }
                    val folder = File(root, UUID.randomUUID().toString()).apply { check(mkdirs()); created = this }
                    val name = attachment.name.substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }.take(60).ifBlank { "attachment" }
                    File(folder, name).apply { writeBytes(bytes) }
                }
                currentCoroutineContext().ensureActive()
                check(repository.drafts.state.value.values.any { attachment in it.attachments }) { "Attachment was removed" }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.task-previews", file)
                val extension = attachment.imageFormat ?: attachment.name.substringAfterLast('.', "").lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                exported = created
                viewer.launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (error: Exception) {
                created?.deleteRecursively(); exported = null; opening = false
                if (error is CancellationException) throw error
                failure = if (error is android.content.ActivityNotFoundException) "No installed app can open this file." else error.message ?: "Could not open attachment"
            }
        }
    }) { Text(if (opening) "Opening…" else "Open") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
