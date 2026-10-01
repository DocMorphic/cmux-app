package io.github.docmorphic.cmuxapp

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.Date
import java.text.DateFormat

@Composable
internal fun SshFilesSheet(session: NativeSshSession, hostId: UUID, terminal: SshTerminal, onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                SshFilesScreen(session, hostId, terminal, onDone)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun SshFilesScreen(session: NativeSshSession, hostId: UUID, terminal: SshTerminal? = null, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remote = remember(session, hostId) { SshFiles {
        check(session.isOpen) { "This account session ended" }
        checkNotNull(session.connections.autoConnect(hostId)) { "This computer is disconnected. Reconnect before browsing files." }
    } }
    val downloads = remember(session, hostId) { File(context.cacheDir, "ssh-files/${UUID.randomUUID()}") }
    val transfers = remember { MutableStateFlow<SshFileProgress?>(null) }
    val transfer by transfers.collectAsState()
    var trail by remember { mutableStateOf<List<String>>(emptyList()) }
    var entries by remember { mutableStateOf<List<SshFileEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<LocalFilePreview?>(null) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf<SshFileEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<SshFileEntry?>(null) }
    val directory = trail.lastOrNull()
    suspend fun load(path: String) { entries = remote.list(path) }
    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true; failure = null
        scope.launch {
            try { block() }
            catch (error: Exception) { ensureActive(); failure = sshFileError(error) }
            finally { busy = false; transfers.value = null }
        }
    }
    fun start() = act {
        val requested = try { terminal?.currentDirectory() } catch (error: Exception) { currentCoroutineContext().ensureActive(); null }
        val paths = remote.start(requested); load(paths.last()); trail = paths
    }
    fun back() {
        if (preview != null) { preview = null; previewPath = null }
        else if (trail.size > 1) act { val paths = trail.dropLast(1); load(paths.last()); trail = paths }
        else onDone()
    }
    BackHandler { back() }
    LaunchedEffect(remote) { start() }
    DisposableEffect(downloads) { onDispose { downloads.deleteRecursively() } }
    fun insert(path: String) {
        if (!SshFilePaths.canInsert(path)) { failure = "This path contains control characters. Use Copy Path instead."; return }
        if (terminal?.send(SshFilePaths.shellWord(path), paste = true) == true) onDone() else failure = "The terminal is disconnected. Reconnect before inserting a path."
    }
    fun upload(uris: List<Uri>, visualMedia: Boolean = false) {
        if (uris.isEmpty()) return
        act {
            val target = directory ?: return@act
            val selectedAt = Date()
            for ((index, uri) in uris.withIndex()) {
                val (filename, size) = withContext(Dispatchers.IO) {
                    var filename = "upload"; var size: Long? = null
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { row ->
                        if (row.moveToFirst()) {
                            val ni = row.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (ni >= 0 && !row.isNull(ni)) filename = row.getString(ni)
                            val si = row.getColumnIndex(OpenableColumns.SIZE)
                            if (si >= 0 && !row.isNull(si)) size = row.getLong(si).takeIf { it >= 0 }
                        }
                    }
                    if (visualMedia) {
                        val extension = context.contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                            ?: filename.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,16}")) } ?: "jpg"
                        filename = SshFilePresentation.photoName(index, extension, selectedAt)
                    }
                    filename to size
                }
                check(SshFilePaths.validName(filename)) { "This upload has an invalid name" }
                try { remote.upload(target, filename, size, { checkNotNull(context.contentResolver.openInputStream(uri)) { "Could not open this upload" } }) { bytes, total ->
                    transfers.value = SshFileProgress(filename, true, bytes, total)
                } } finally { load(target) }
            }
        }
    }
    // Local function references compare equal even when their captured directory
    // changes. Lambdas let the launcher's updated callback see the loaded folder.
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { upload(it) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { upload(it, visualMedia = true) }
    fun open(entry: SshFileEntry) = act {
        val path = SshFilePaths.join(checkNotNull(directory), entry.name)
        if (entry.directory || entry.symlink && remote.directory(path)) { load(path); trail = trail + path }
        else {
            val local = File(File(downloads, UUID.randomUUID().toString()), changesPreviewName(entry.name))
            val size = remote.download(path, local) { bytes, total -> transfers.value = SshFileProgress(entry.name, false, bytes, total) }
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.name.substringAfterLast('.', "").lowercase())
            var route = filePreviewRoute(if (mime?.startsWith("text/") == true || ArtifactSyntaxPolicy.language(entry.name) != null) "text" else "binary", mime, entry.name)
            if (route == ChangesPreviewRoute.TEXT && size > ChangesContentTransfer.PREVIEW_BYTES) route = ChangesPreviewRoute.EXTERNAL
            if (route == ChangesPreviewRoute.EXTERNAL && size <= 2 * 1024 * 1024) {
                val text = withContext(Dispatchers.IO) { runCatching {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(local.readBytes())).toString()
                }.getOrNull() }
                if (text != null && '\u0000' !in text) route = ChangesPreviewRoute.TEXT
            }
            preview = LocalFilePreview(local, size, mime, route); previewPath = path
        }
    }
    Column(Modifier.fillMaxSize().testTag("ssh.files")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = ::back, enabled = !busy) { Text("Back") }
            Text(if (preview != null) previewPath?.substringAfterLast('/') ?: preview!!.file.name else directory?.substringAfterLast('/')?.ifEmpty { "/" } ?: "Files", Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            TextButton(onClick = onDone) { Text("Done") }
        }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp).testTag("ssh.files.error")) }
        val local = preview
        if (local != null) {
            if (terminal != null) TextButton(onClick = { previewPath?.let(::insert) }) { Text("Insert Path in Terminal") }
            FilePreviewContent(local)
        } else if (directory != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { act { load(directory) } }, enabled = !busy, modifier = Modifier.testTag("ssh.files.refresh")) { Text("Refresh") }
                var addMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { addMenu = true }, enabled = !busy, modifier = Modifier.testTag("ssh.files.add")) {
                        Icon(painterResource(R.drawable.ic_task_plus), contentDescription = "Add", tint = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(text = { Text("Upload from Files…") }, onClick = { addMenu = false; documents.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text("Upload from Photos…") }, onClick = { addMenu = false; photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("New Folder…") }, modifier = Modifier.testTag("ssh.files.new-folder"), onClick = { addMenu = false; name = ""; newFolder = true })
                    }
                }
            }
            SelectionContainer {
                Text(directory, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.StartEllipsis)
            }
            val dates = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
            PullToRefreshBox(isRefreshing = busy && transfer == null, onRefresh = { act { load(directory) } }, modifier = Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().testTag("ssh.files.list"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    if (entries.isEmpty() && !busy) item { Text("This folder is empty.", Modifier.padding(12.dp)) }
                    items(entries, key = { it.name }) { entry ->
                        var menu by remember(entry.name) { mutableStateOf(false) }
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(vertical = 1.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).combinedClickable(enabled = !busy, onClick = { open(entry) }, onLongClick = { menu = true })
                                    .padding(12.dp).testTag("ssh.files.row.${entry.name}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(painterResource(if (entry.symlink) R.drawable.ic_ssh_file_link else if (entry.directory) R.drawable.ic_workspace_folder_fill else R.drawable.ic_workspace_file_text),
                                        contentDescription = null, modifier = Modifier.size(28.dp), tint = if (entry.directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                        Text(listOfNotNull(if (!entry.directory) android.text.format.Formatter.formatShortFileSize(context, entry.size) else null, dates.format(Date(entry.modified * 1000))).joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (entry.directory) Icon(painterResource(R.drawable.ic_workspace_chevron_right), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Box {
                                    IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.testTag("ssh.files.actions.${entry.name}")) {
                                        Icon(painterResource(R.drawable.ic_ssh_file_more), contentDescription = "Actions for ${entry.name}")
                                    }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        val path = SshFilePaths.join(directory, entry.name)
                                        if (terminal != null) DropdownMenuItem(text = { Text("Insert Path in Terminal") }, onClick = { menu = false; insert(path) })
                                        DropdownMenuItem(text = { Text("Copy Path") }, onClick = { menu = false; context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Path", path)) })
                                        DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; naming = entry; name = entry.name })
                                        DropdownMenuItem(text = { Text("Delete…") }, onClick = { menu = false; deleting = entry })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else if (!busy) TextButton(onClick = ::start) { Text("Try Again") }
        transfer?.let { progress ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("ssh.files.transfer")) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${if (progress.upload) "Uploading" else "Downloading"} ${progress.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    if (progress.total != null && progress.total > 0) LinearProgressIndicator(progress = { (progress.bytes.toFloat() / progress.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (busy && directory == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (newFolder || naming != null) AlertDialog(onDismissRequest = { newFolder = false; naming = null },
        title = { Text(if (newFolder) "New Folder" else "Rename") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.testTag("ssh.files.name")) },
        confirmButton = { TextButton(enabled = SshFilePaths.validName(name.trim()) && (newFolder || name.trim() != naming?.name), onClick = {
            val target = naming; val value = name.trim(); naming = null; newFolder = false
            act { try { if (target == null) remote.mkdir(checkNotNull(directory), value) else remote.rename(checkNotNull(directory), target, value) }
                finally { directory?.let { load(it) } } }
        }) { Text(if (newFolder) "Create" else "Rename") } },
        dismissButton = { TextButton(onClick = { newFolder = false; naming = null }) { Text("Cancel") } })
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete “${entry.name}”?") },
        text = { Text("This deletes it from the computer. You can't undo this.") },
        confirmButton = { TextButton(onClick = { deleting = null; act { try { remote.delete(checkNotNull(directory), entry) } finally { directory?.let { load(it) } } } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}
internal fun sshFileError(error: Exception): String = if (error is com.jcraft.jsch.SftpException) when (error.id) {
    com.jcraft.jsch.ChannelSftp.SSH_FX_PERMISSION_DENIED -> "You don't have permission to do that on this computer."
    com.jcraft.jsch.ChannelSftp.SSH_FX_NO_SUCH_FILE -> "That file or folder doesn't exist anymore."
    com.jcraft.jsch.ChannelSftp.SSH_FX_CONNECTION_LOST, com.jcraft.jsch.ChannelSftp.SSH_FX_NO_CONNECTION -> "The connection was lost. Check the folder before trying the action again."
    else -> "The computer couldn't complete this file operation. Refresh before trying again."
} else error.message ?: "Couldn't complete this file operation."
