package io.github.docmorphic.cmuxapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File

internal data class ChangesPreviewState(val identity: Any? = null, val artifact: ChangesPreviewArtifact? = null,
    val received: Long = 0, val total: Long? = null, val error: String? = null)

@Composable
internal fun ChangesBinaryPreview(transfer: ChangesContentTransfer, file: ChangedFile, generation: Any) {
    val context = LocalContext.current
    val policy = remember(file) { ChangesPreviewPolicy.forFile(file) }
    var revision by remember(file) { mutableStateOf(policy.initial) }
    var retry by remember { mutableIntStateOf(0) }
    val path = ChangesPreviewPolicy.path(file, revision)
    val selectedRevision = revision
    val identity = remember(transfer, path, revision, generation, retry) { Any() }
    val produced by produceState(ChangesPreviewState(), identity) {
        value = ChangesPreviewState(identity)
        val session = ChangesPreviewFiles(File(context.cacheDir, "changes-previews"), transfer)
        try {
            val metadata = transfer.metadata(path, selectedRevision)
            value = value.copy(total = metadata.size)
            val artifact = session.download(path, selectedRevision, metadata) { received, total ->
                withContext(Dispatchers.Main) { value = value.copy(received = received, total = total) }
            }
            ensureActive()
            value = value.copy(artifact = artifact)
            awaitCancellation()
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            value = value.copy(error = failure.message ?: "Could not load preview")
            awaitCancellation()
        } finally { withContext(NonCancellable + Dispatchers.IO) { session.close() } }
    }
    val state = produced.takeIf { it.identity === identity } ?: ChangesPreviewState(identity)
    Column(Modifier.fillMaxSize().semantics { contentDescription = "${if (revision == ChangesRevision.BASE) "Before" else "After"} preview $path" }) {
        if (policy.revisions.size > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            policy.revisions.forEach { option -> FilterChip(selected = revision == option, onClick = { revision = option },
                label = { Text(if (option == ChangesRevision.BASE) "Before" else "After") }, modifier = Modifier.weight(1f)) }
        }
        ChangesPreviewActions(state.artifact)
        if (state.error != null) ChangesNotice("Couldn't load preview", state.error.orEmpty()) { retry++ }
        else if (state.artifact == null) Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            if (state.total != null && state.total!! > 0) LinearProgressIndicator(progress = { (state.received.toFloat() / state.total!!).coerceIn(0f, 1f) })
            else LinearProgressIndicator()
            Text("Loading preview", Modifier.padding(top = 12.dp))
            state.total?.let { Text("${state.received} of $it bytes", color = changesMuted, fontSize = 12.sp) }
        } else key(state.artifact!!.file.absolutePath) { ChangesPreviewContent(state.artifact!!) }
    }
}

@Composable
private fun ChangesPreviewActions(artifact: ChangesPreviewArtifact?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<File?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val captured = pendingSave
        pendingSave = null
        if (captured != null) scope.launch {
            try {
                if (uri != null) withContext(Dispatchers.IO) {
                    val output = checkNotNull(context.contentResolver.openOutputStream(uri)) { "Could not open the save destination." }
                    output.use { target -> captured.inputStream().use { source ->
                        val bytes = ByteArray(64 * 1024)
                        while (true) { ensureActive(); val count = source.read(bytes); if (count < 0) break; target.write(bytes, 0, count) }
                    } }
                }
            } catch (error: Exception) { if (error is CancellationException) throw error; failure = error.message ?: "Could not save file." }
            finally { withContext(NonCancellable + Dispatchers.IO) { captured.parentFile?.deleteRecursively() }; busy = false }
        } else busy = false
    }
    fun perform(action: String) {
        val captured = artifact ?: return
        busy = true; failure = null; menu = false
        scope.launch {
            var exported: File? = null
            try {
                val file = exportChangesPreview(captured, File(context.cacheDir, "task-previews"))
                exported = file
                ensureActive()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.task-previews", file)
                val mime = captured.metadata.mime?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
                when (action) {
                    "Save" -> { pendingSave = file; save.launch(file.name) }
                    "Copy Image" -> {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newUri(context.contentResolver, file.name, uri))
                        busy = false
                    }
                    else -> {
                        val intent = if (action == "Share") Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        intent.clipData = ClipData.newRawUri(file.name, uri)
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(intent, if (action == "Share") "Share ${file.name}" else "Open ${file.name}"))
                        busy = false
                    }
                }
            } catch (error: Exception) {
                exported?.parentFile?.deleteRecursively(); pendingSave = null; busy = false
                if (error is CancellationException) throw error
                failure = if (error is android.content.ActivityNotFoundException) "No installed app can open this file." else error.message ?: "Could not prepare file."
            }
        }
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(artifact?.let { "${it.metadata.size} bytes" }.orEmpty(), Modifier.weight(1f), fontSize = 12.sp, color = changesMuted)
            Box {
                TextButton(onClick = { menu = true }, enabled = artifact != null && !busy) { Text(if (busy) "Preparing…" else "File actions") }
                DropdownMenu(menu, { menu = false }) {
                    listOf("Share", "Save", "Open").forEach { action -> DropdownMenuItem(text = { Text(action) }, onClick = { perform(action) }) }
                    if (artifact?.route == ChangesPreviewRoute.IMAGE) DropdownMenuItem(text = { Text("Copy Image") }, onClick = { perform("Copy Image") })
                }
            }
        }
        failure?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}
