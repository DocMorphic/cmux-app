package io.github.docmorphic.cmuxapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
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

@Composable
internal fun ChangesBinaryPreview(transfer: ChangesContentTransfer, file: ChangedFile, generation: Any,
    controller: ChangesPreviewController) {
    val root = File(LocalContext.current.cacheDir, "changes-previews")
    val policy = remember(file) { ChangesPreviewPolicy.forFile(file) }
    val choices by controller.revisions.collectAsState()
    val revision = choices[file.path]?.takeIf { it in policy.revisions } ?: policy.initial
    val path = ChangesPreviewPolicy.path(file, revision)
    val produced by controller.state.collectAsState()
    LaunchedEffect(controller, transfer, file, generation, revision) { controller.open(file, generation, transfer, root) }
    val state = produced.takeIf { controller.matches(it, file, generation, transfer, revision) } ?: ChangesPreviewState()
    Column(Modifier.fillMaxSize().semantics { contentDescription = "${if (revision == ChangesRevision.BASE) "Before" else "After"} preview $path" }) {
        if (policy.revisions.size > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            policy.revisions.forEach { option -> FilterChip(selected = revision == option, onClick = { controller.choose(file, option) },
                label = { Text(if (option == ChangesRevision.BASE) "Before" else "After") }, modifier = Modifier.weight(1f)) }
        }
        if (state.artifact == null) FilePreviewActions(null)
        if (state.error != null) ChangesNotice("Couldn't load preview", state.error.orEmpty()) { controller.retry() }
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
internal fun FilePreviewActions(artifact: LocalFilePreview?, viewer: ArtifactViewerState? = null, remote: RemoteArtifactSource? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saves = checkNotNull(LocalFileSaves.current)
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var fontDialog by remember { mutableStateOf(false) }
    fun perform(action: String) {
        val captured = artifact
        val source = remote.takeUnless { action == "Copy Image" }
        if (captured == null && source == null) return
        if (action == "Save") { menu = false; failure = null; saves.begin(captured, source); return }
        busy = true; failure = null; menu = false
        scope.launch {
            var exported: File? = null
            try {
                val root = File(context.cacheDir, "task-previews")
                val materialized = source?.materialize(root) { metadata -> fileActionType(changesPreviewName(source.path), metadata.mime).filename }
                val type = if (materialized != null) fileActionType(materialized.file.name, materialized.mime)
                    else fileActionType(checkNotNull(captured).file.name, captured.mime)
                val mime = type.mime
                val file = materialized?.file ?: exportFilePreview(checkNotNull(captured), root, type.filename)
                exported = file
                ensureActive()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.task-previews", file)
                when (action) {
                    "Copy Image" -> {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newUri(context.contentResolver, file.name, uri))
                        busy = false
                    }
                    else -> {
                        val intent = if (action == "Share") artifactShareIntent(context, file, mime)
                            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        intent.clipData = ClipData.newRawUri(file.name, uri)
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(intent, if (action == "Share") "Share ${file.name}" else "Open ${file.name}"))
                        busy = false
                    }
                }
            } catch (error: Exception) {
                exported?.parentFile?.deleteRecursively(); busy = false
                if (error is CancellationException) throw error
                failure = if (error is android.content.ActivityNotFoundException) "No installed app can open this file."
                    else if (source != null) ArtifactPreviewFailure.from(error, source.authorization)
                        .presentation(source.authorization, false, NativeFeedAvailability.CONNECTED).let { "${it.title}. ${it.message}" }
                    else error.message ?: "Could not prepare file."
            }
        }
    }
    if (fontDialog && viewer != null) AlertDialog(onDismissRequest = { fontDialog = false }, title = { Text("Text size") },
        text = { Column {
            Text("${viewer.fontSize.toInt()} pt")
            Slider(viewer.fontSize, viewer::setFont, valueRange = 8f..28f, steps = 19, modifier = Modifier.semantics { contentDescription = "Text size" })
        } }, confirmButton = { TextButton(onClick = { fontDialog = false }) { Text("Done") } },
        dismissButton = { TextButton(onClick = { viewer.setFont(15f) }) { Text("Reset") } })
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(artifact?.let { "${it.size} bytes" }.orEmpty(), Modifier.weight(1f), fontSize = 12.sp, color = changesMuted)
            Box {
                IconButton(onClick = { menu = true }, enabled = (artifact != null || remote != null) && !busy && !saves.busy,
                    modifier = Modifier.semantics { contentDescription = "Viewer actions" }) {
                    if (busy || saves.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Box(Modifier.size(24.dp).border(1.dp, filesMuted, CircleShape), contentAlignment = Alignment.Center) { Text("⋯", fontSize = 19.sp) }
                }
                DropdownMenu(menu, { menu = false }, containerColor = androidx.compose.ui.graphics.Color(0xFF232428)) {
                    listOf("Share", "Save", "Open").forEach { action -> DropdownMenuItem(text = { Text(action) }, onClick = { perform(action) }) }
                    if (artifact?.route == ChangesPreviewRoute.IMAGE) DropdownMenuItem(text = { Text("Copy Image") }, onClick = { perform("Copy Image") })
                    if (artifact?.route == ChangesPreviewRoute.TEXT && viewer != null) {
                        DropdownMenuItem(text = { Text("Copy Contents") }, enabled = !busy && artifact.size in 0..(4L * 1024 * 1024), onClick = {
                            menu = false; busy = true
                            scope.launch {
                                try {
                                    val text = viewer.document?.text ?: withContext(Dispatchers.IO) { artifact.file.readText() }
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(artifact.file.name, text))
                                } catch (error: Exception) { ensureActive(); failure = error.message ?: "Could not copy contents." }
                                finally { busy = false }
                            }
                        })
                        if (viewer.raw) {
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Search") }, onClick = { menu = false; viewer.searchOpen = !viewer.searchOpen; if (!viewer.searchOpen) viewer.closeSearch() })
                            DropdownMenuItem(text = { Text("Go to line") }, onClick = { menu = false; viewer.goToLineOpen = true }, enabled = viewer.document != null)
                            DropdownMenuItem(text = { Text("Top") }, onClick = { menu = false; viewer.jumpTo(0) })
                            DropdownMenuItem(text = { Text("End") }, onClick = { menu = false; viewer.jumpTo(viewer.document?.text?.length ?: 0) })
                            DropdownMenuItem(text = { Text("Line numbers") }, trailingIcon = { if (viewer.lineNumbers) Text("✓") }, onClick = { menu = false; viewer.lineNumbers = !viewer.lineNumbers })
                            DropdownMenuItem(text = { Text("Word wrap") }, trailingIcon = { if (viewer.wrap) Text("✓") }, onClick = { menu = false; viewer.updateWrap(!viewer.wrap) })
                            DropdownMenuItem(text = { Text("Text size") }, onClick = { menu = false; fontDialog = true })
                        }
                        if (viewer.markdown) {
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Raw") }, trailingIcon = { if (!viewer.rendered) Text("✓") }, onClick = { menu = false; viewer.rendered = false })
                            DropdownMenuItem(text = { Text("Rendered") }, enabled = viewer.renderedAvailable,
                                trailingIcon = { if (viewer.rendered) Text("✓") }, onClick = { menu = false; viewer.closeSearch(); viewer.rendered = true; viewer.failure = null })
                        }
                    }
                }
            }
        }
        failure?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}
