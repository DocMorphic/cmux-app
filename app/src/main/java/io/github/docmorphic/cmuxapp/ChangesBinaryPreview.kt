package io.github.docmorphic.cmuxapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
internal fun FilePreviewActions(artifact: LocalFilePreview?, viewer: ArtifactViewerState? = null, remote: RemoteArtifactSource? = null,
    complete: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = filePreviewActionHandler(artifact, remote)
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var fontDialog by remember { mutableStateOf(false) }
    fun perform(action: FilePreviewAction) {
        failure = null; menu = false
        actions.perform(action)
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
                IconButton(onClick = { menu = true }, enabled = actions.enabled && !busy,
                    modifier = Modifier.semantics { contentDescription = "Viewer actions" }) {
                    if (busy || actions.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Box(Modifier.size(24.dp).border(1.dp, filesMuted, CircleShape), contentAlignment = Alignment.Center) { Text("⋯", fontSize = 19.sp) }
                }
                DropdownMenu(menu, { menu = false }, containerColor = androidx.compose.ui.graphics.Color(0xFF232428)) {
                    listOf(FilePreviewAction.SHARE, FilePreviewAction.SAVE, FilePreviewAction.OPEN).forEach { action -> DropdownMenuItem(text = { Text(action.label) }, onClick = { perform(action) }) }
                    if (artifact?.route == ChangesPreviewRoute.IMAGE) DropdownMenuItem(text = { Text("Copy Image") }, onClick = { perform(FilePreviewAction.COPY_IMAGE) })
                    remote?.let { source -> DropdownMenuItem(text = { Text("Copy path") }, onClick = {
                        menu = false
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("File path", source.path))
                    }) }
                    if (artifact?.route == ChangesPreviewRoute.TEXT && viewer != null) {
                        DropdownMenuItem(text = { Text("Copy Contents") }, enabled = complete && !busy && artifact.size in 0..(4L * 1024 * 1024), onClick = {
                            menu = false; busy = true
                            scope.launch {
                                try {
                                    val text = viewer.document?.text ?: withContext(Dispatchers.IO) { artifact.file.readText() }
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(artifact.file.name, text))
                                } catch (error: Exception) { ensureActive(); failure = "This file can't be copied. Reopen its preview and try again." }
                                finally { busy = false }
                            }
                        })
                        if (viewer.raw) {
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Search") }, onClick = { menu = false; viewer.searchOpen = !viewer.searchOpen; if (!viewer.searchOpen) viewer.closeSearch() })
                            DropdownMenuItem(text = { Text("Go to line") }, onClick = { menu = false; viewer.goToLineOpen = true }, enabled = viewer.document != null)
                            DropdownMenuItem(text = { Text("Top") }, onClick = { menu = false; viewer.jumpTo(0) })
                            DropdownMenuItem(text = { Text(if (complete) "End" else "Latest") }, onClick = { menu = false; viewer.jumpTo(viewer.document?.text?.length ?: 0, follow = true) })
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
