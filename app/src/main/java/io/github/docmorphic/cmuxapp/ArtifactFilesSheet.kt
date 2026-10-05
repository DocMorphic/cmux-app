package io.github.docmorphic.cmuxapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import java.io.File
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Base64

internal val filesAccent = Color(0xFF76B9FF)
internal val filesMuted = Color(0xFF9B9FA8)
private val filesPanel = Color(0xFF191B1F)

@Composable
internal fun ArtifactFilesSheet(rpc: ArtifactRpc, terminal: ArtifactAuthorization.Terminal, refreshSignal: Int = 0, navigation: ArtifactNavigationState? = null,
    retained: TerminalFilesPresentation? = null, connection: NativeFeedAvailability = NativeFeedAvailability.CONNECTED,
    onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = retained?.galleryStore() ?: remember(rpc, terminal) { ArtifactGalleryStore(scope, terminal, rpc) }
    val detail = navigation ?: remember(terminal) { ArtifactNavigationState() }
    val latestSignal by rememberUpdatedState(refreshSignal)
    var showingSession by remember(store) { mutableStateOf(true) }
    DisposableEffect(store, retained) { onDispose { if (retained == null) store.close() } }
    LaunchedEffect(store) { if (retained == null) { store.initialize().join(); store.setQuery(detail.searchText) } }
    // The terminal controller sends only accepted count reports, never provisional per-frame counts.
    LaunchedEffect(store) {
        snapshotFlow { latestSignal }.drop(1).collect { if (showingSession) store.refreshLive() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF111316), contentColor = Color(0xFFE5E7EB)) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                // collectAsState can retain its previous flow's value until its collector
                // restarts. A new client must never observe the old session admission.
                key(store) { ArtifactFilesContent(rpc, store, onDismiss, onScopeChanged = { showingSession = it }, navigation = detail,
                    retainedPreview = retained?.galleryPreview, retainedFolder = retained?.galleryFolder, connection = connection) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtifactFilesContent(rpc: ArtifactRpc, store: ArtifactGalleryStore, onDismiss: () -> Unit, onScopeChanged: (Boolean) -> Unit = {},
    navigation: ArtifactNavigationState? = null, retainedPreview: ArtifactPreviewController? = null, retainedFolder: ArtifactFolderController? = null,
    connection: NativeFeedAvailability = NativeFeedAvailability.CONNECTED) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("cmux-display", android.content.Context.MODE_PRIVATE) }
    val scan by store.inView.collectAsState()
    val authorization by store.sessionAuthorization.collectAsState()
    val session by store.session.collectAsState()
    val search by store.search.collectAsState()
    val query by store.query.collectAsState()
    val pending by store.pendingNewFiles.collectAsState()
    val detail = navigation ?: remember(store) { ArtifactNavigationState() }
    var sessionScope by detail::sessionScope
    var searchText by detail::searchText
    var grid by detail::grid
    var filter by detail::filter
    var sort by detail::sort
    var showMissing by remember { mutableStateOf(preferences.getBoolean("show-missing-files", false)) }
    var folded by detail::folded
    // Session IDs are re-established by the new terminal scan, never silently rebound.
    val routesReady = scan.scan != null && detail.matches(store.terminal, authorization)
    LaunchedEffect(store, scan.scan, authorization) {
        if (scan.scan != null && !detail.matches(store.terminal, authorization)) detail.clearRoutes()
    }
    val thumbnails = remember(rpc) { ArtifactThumbnails(rpc) }
    val listState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    val activeSession = sessionScope && authorization != null
    val state = if (query.isEmpty()) session else search
    val eager = filter != ArtifactFilter.ALL || sort != ArtifactSort.RECENT || !showMissing
    LaunchedEffect(activeSession) { onScopeChanged(activeSession) }
    LaunchedEffect(store, listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 || !listState.canScrollBackward }
            .collect { store.isAtTopOrFits = it }
    }
    LaunchedEffect(activeSession, query, filter, sort, showMissing, state.snapshot?.generation, state.snapshot?.nextCursor) {
        if (activeSession && eager && state.snapshot != null && state.error == null && !state.capped) store.loadMore(query.isNotEmpty(), eager = true)
    }
    fun open(item: ArtifactItem, items: List<ArtifactItem>, scope: ArtifactAuthorization) {
        detail.open(if (item.kind == ArtifactKind.DIRECTORY) ArtifactDestination.Folder(item, scope)
            else ArtifactDestination.Preview(artifactSwipeOrder(items), item.path, scope))
    }
    fun back() { detail.back() }
    BackHandler(enabled = detail.destinations.isNotEmpty()) { back() }
    LaunchedEffect(detail.destinations.lastOrNull(), routesReady, retainedPreview) {
        if (!routesReady || detail.destinations.lastOrNull() !is ArtifactDestination.Preview) retainedPreview?.clear()
    }
    when (val destination = detail.destinations.lastOrNull().takeIf { routesReady }) {
        is ArtifactDestination.Preview -> key(destination) { ArtifactFilePreview(rpc, destination, ::back, onDismiss,
            initialPath = detail.selectedPath, onSelectionChanged = { detail.selectedPath = it }, retained = retainedPreview, connection = connection) }
        is ArtifactDestination.Folder -> key(destination) {
            ArtifactFolderContent(rpc, thumbnails, destination, ::back, onDismiss, retained = retainedFolder, connection = connection) { item, entries ->
                open(item, entries, destination.authorization)
            }
        }
        null -> Column(Modifier.fillMaxSize()) {
            FilesHeader("Files", null, onDismiss) { FilesViewModeButton(grid) { grid = !grid } }
            if (authorization != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                .background(filesPanel, RoundedCornerShape(10.dp))) {
                listOf(true to "Session", false to "In view").forEach { (value, label) ->
                    TextButton(onClick = { sessionScope = value }, Modifier.weight(1f)
                        .background(if (sessionScope == value) Color(0xFF30343B) else Color.Transparent, RoundedCornerShape(10.dp))) {
                        Text(label, color = if (sessionScope == value) Color.White else filesMuted)
                    }
                }
            }
            if (activeSession) {
                FilesSearchField(searchText) { searchText = it; store.setQuery(it) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ArtifactFilter.entries.forEach { option ->
                            TextButton(onClick = { filter = option }, shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                                colors = ButtonDefaults.textButtonColors(containerColor = if (filter == option) Color(0xFF007AFF) else filesPanel)) {
                                Text(option.title, color = if (filter == option) Color.White else filesMuted, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    var sortMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { sortMenu = true }) { Text("${sort.title} ▾", color = filesAccent) }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            ArtifactSort.entries.forEach { option -> DropdownMenuItem(text = { Text(option.title) }, onClick = { sort = option; sortMenu = false }) }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(if (showMissing) "Hide missing" else "Show missing") }, onClick = {
                                showMissing = !showMissing
                                preferences.edit().putBoolean("show-missing-files", showMissing).apply()
                                sortMenu = false
                            })
                        }
                    }
                }
            }
            HorizontalDivider(color = filesPanel)
            if (activeSession && pending > 0 && query.isEmpty()) TextButton(onClick = {
                store.applyPending(); coroutineScope.launch { listState.scrollToItem(0) }
            }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("$pending new ${if (pending == 1) "file" else "files"}", color = filesAccent) }
            if (activeSession && state.loadingMore) LinearProgressIndicator(Modifier.fillMaxWidth())
            val groups = remember(activeSession, state.snapshot, scan.scan, filter, sort, showMissing, query) {
                if (activeSession) state.snapshot?.let { artifactGroups(it, filter, sort, showMissing, query.isNotEmpty()) }.orEmpty()
                else listOf(ArtifactGroup("", scan.scan?.items.orEmpty()))
            }
            val allItems = remember(groups) { groups.flatMap { it.items } }
            val loading = if (activeSession) state.loading else scan.loading
            val failure = if (activeSession) state.error else scan.error
            val refresh = {
                if (!activeSession) store.refreshInView()
                else if (query.isEmpty()) store.refreshSession() else store.retrySearch()
                Unit
            }
            PullToRefreshBox(loading, refresh, Modifier.weight(1f)) {
                LazyVerticalGrid(GridCells.Fixed(if (grid) 3 else 1), state = listState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(if (grid) 16.dp else 2.dp)) {
                    if (allItems.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        FilesMessage(if (loading) "Loading files…" else if (failure != null) "Couldn't load files" else if (!activeSession) "No files in view"
                            else if (query.isNotEmpty()) "No matching files" else if (filter != ArtifactFilter.ALL) "No ${filter.title.lowercase()}" else "No files in this session",
                            failure, if (!loading && failure != null) "Retry" else null, refresh)
                    }
                    groups.forEach { group ->
                        if (group.title.isNotEmpty() && group.items.isNotEmpty()) item(key = "section:${group.title}", span = { GridItemSpan(maxLineSpan) }) {
                            TextButton(onClick = { folded = if (group.title in folded) folded - group.title else folded + group.title }, Modifier.fillMaxWidth()) {
                                Text("${if (group.title in folded) "›" else "⌄"}  ${group.title}", Modifier.weight(1f), color = Color.White)
                                Text(group.items.size.toString(), color = filesMuted)
                            }
                        }
                        if (group.title !in folded) items(group.items, key = { it.path }) { item ->
                            ArtifactGalleryRow(rpc, item, grid, thumbnails, if (activeSession) authorization!! else store.terminal) {
                                open(item, allItems, store.sheetAuthorization())
                            }
                        }
                    }
                    if (activeSession && state.snapshot != null) item(key = "paging", span = { GridItemSpan(maxLineSpan) }) {
                        if (!eager && state.snapshot!!.nextCursor != null && state.error == null && !state.loadingMore) {
                            LaunchedEffect(state.snapshot!!.nextCursor) { store.loadMore(query.isNotEmpty()) }
                        }
                        when {
                            state.capped -> Text("Showing up to 2,000 referenced files. Search to narrow the results.", color = filesMuted, fontSize = 12.sp)
                            state.error != null -> TextButton(onClick = { store.loadMore(query.isNotEmpty(), eager) }) { Text("Retry loading more files", color = filesAccent) }
                            state.loadingMore -> Text("Loading files…", color = filesMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FilesHeader(title: String, onBack: (() -> Unit)?, onDone: () -> Unit, extra: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(title, Modifier.fillMaxWidth().padding(horizontal = 84.dp), textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack ?: onDone) { Text(if (onBack == null) "Done" else "‹ Back", color = filesAccent) }
            Spacer(Modifier.weight(1f))
            extra()
            if (onBack != null) TextButton(onClick = onDone) { Text("Done", color = filesAccent) }
        }
    }
}

@Composable
private fun FilesSearchField(value: String, onChange: (String) -> Unit) {
    BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
        cursorBrush = SolidColor(filesAccent),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 40.dp)
            .background(filesPanel, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp),
        decorationBox = { field ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(16.dp)) {
                    drawCircle(filesMuted, radius = size.width * .3f, center = androidx.compose.ui.geometry.Offset(size.width * .36f, size.height * .36f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx()))
                    drawLine(filesMuted, androidx.compose.ui.geometry.Offset(size.width * .6f, size.height * .6f), androidx.compose.ui.geometry.Offset(size.width * .95f, size.height * .95f), strokeWidth = 1.8.dp.toPx())
                }
                Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 9.dp)) {
                    if (value.isEmpty()) Text("Search session files", color = filesMuted, fontSize = 16.sp)
                    field()
                }
                if (value.isNotEmpty()) TextButton(onClick = { onChange("") }) { Text("Clear", color = filesMuted) }
            }
        })
}
@Composable
internal fun FilesMessage(title: String, message: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        message?.let { Text(it, color = filesMuted, fontSize = 13.sp) }
        action?.let { TextButton(onClick = onAction) { Text(it, color = filesAccent) } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtifactGalleryRow(rpc: ArtifactRpc, item: ArtifactItem, grid: Boolean, thumbnails: ArtifactThumbnails, authorization: ArtifactAuthorization, onOpen: () -> Unit) {
    key(rpc, authorization, item.path) { ArtifactGalleryRowContent(rpc, item, grid, thumbnails, authorization, onOpen) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtifactGalleryRowContent(rpc: ArtifactRpc, item: ArtifactItem, grid: Boolean, thumbnails: ArtifactThumbnails, authorization: ArtifactAuthorization, onOpen: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    var shareFailure by remember { mutableStateOf<String?>(null) }
    fun share() {
        if (sharing || !item.exists || item.kind == ArtifactKind.DIRECTORY) return
        sharing = true; menu = false; shareFailure = null
        scope.launch {
            var exported: LocalFilePreview? = null
            var handedOff = false
            try {
                val file = materializeArtifactShare(rpc, authorization, item.path, File(context.cacheDir, "task-previews")) { metadata ->
                    fileActionType(changesPreviewName(item.path), metadata.mime).filename
                }
                exported = file
                ensureActive()
                context.startActivity(Intent.createChooser(artifactShareIntent(context, file.file, fileActionType(file.file.name, file.mime).mime), "Share ${file.file.name}"))
                handedOff = true
            } catch (error: Exception) {
                ensureActive()
                shareFailure = error.message ?: "Could not prepare file."
            } finally {
                if (!handedOff) withContext(NonCancellable + Dispatchers.IO) { exported?.file?.parentFile?.deleteRecursively() }
                sharing = false
            }
        }
    }
    shareFailure?.let { message -> AlertDialog(onDismissRequest = { shareFailure = null }, title = { Text("Couldn't share file") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { shareFailure = null }) { Text("OK") } }) }
    Box {
        val modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true })
            .semantics { contentDescription = "Open ${if (item.kind == ArtifactKind.DIRECTORY) "folder" else "file"} ${item.path}" }
        if (grid) Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            ArtifactGlyph(item, thumbnails, authorization, Modifier.fillMaxWidth().aspectRatio(1f).background(filesPanel, RoundedCornerShape(12.dp)))
            Text(item.displayName, Modifier.padding(top = 7.dp), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (item.exists) Color.White else filesMuted)
            ArtifactMetadataLabel(item)
        } else Row(modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtifactGlyph(item, thumbnails, authorization, Modifier.size(42.dp).background(filesPanel, RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(item.displayName, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (item.exists) Color.White else filesMuted)
                ArtifactMetadataLabel(item)
            }
            if (item.kind == ArtifactKind.DIRECTORY) Text("›", color = filesMuted, fontSize = 22.sp)
        }
        if (sharing) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).size(18.dp).semantics { contentDescription = "Preparing ${item.displayName} to share" }, strokeWidth = 2.dp)
        DropdownMenu(menu, { menu = false }) {
            if (item.kind != ArtifactKind.DIRECTORY) DropdownMenuItem(text = { Text("Share") }, enabled = item.exists && !sharing, onClick = ::share)
            if (item.kind == ArtifactKind.DIRECTORY) DropdownMenuItem(text = { Text("Browse folder") }, enabled = item.exists, onClick = { menu = false; onOpen() })
            DropdownMenuItem(text = { Text("Copy path") }, onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("File path", item.path)); menu = false
            })
        }
    }
}
@Composable
private fun ArtifactMetadataLabel(item: ArtifactItem) {
    val context = LocalContext.current
    val text = if (!item.exists) "No longer on your Mac" else buildList {
        if (item.kind == ArtifactKind.DIRECTORY && item.childCount != null) add("${item.childCount}${if (item.childCountIsCapped) "+" else ""} items")
        item.modifiedAt?.let { seconds ->
            runCatching { java.time.Instant.ofEpochSecond(seconds.toLong()).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)) }.getOrNull()?.let(::add)
        }
        if (item.kind != ArtifactKind.DIRECTORY && item.size != null) add(android.text.format.Formatter.formatShortFileSize(context, item.size))
    }.joinToString(" · ")
    Text(text, color = filesMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
@Composable
private fun ArtifactGlyph(item: ArtifactItem, thumbnails: ArtifactThumbnails, authorization: ArtifactAuthorization, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null, item, thumbnails, authorization) {
        value = null
        if (item.kind == ArtifactKind.IMAGE && item.exists) try { value = thumbnails.load(item, authorization) }
        catch (failure: Exception) { if (failure is CancellationException) throw failure }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), item.displayName, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
        else FilesGlyph(artifactFilter(item), Modifier.size(28.dp))
    }
}

internal class ArtifactThumbnails(private val rpc: ArtifactRpc) {
    private data class Key(val scope: ArtifactAuthorization, val path: String, val modifiedAt: Double?, val size: Long?)
    private val cache = object : LruCache<Key, Bitmap>(8 * 1024 * 1024) { override fun sizeOf(key: Key, value: Bitmap) = value.byteCount }
    private val permits = Semaphore(3)
    suspend fun load(item: ArtifactItem, authorization: ArtifactAuthorization): Bitmap = permits.withPermit {
        val key = Key(authorization, item.path, item.modifiedAt, item.size)
        cache.get(key)?.let { return@withPermit it }
        val value = rpc.thumbnail(authorization, item.path, 256)
        val bitmap = withContext(Dispatchers.Default) {
            val encoded = value.getString("data_b64")
            check(encoded.length <= 2 * 1024 * 1024) { "Oversized thumbnail" }
            val bytes = Base64.getDecoder().decode(encoded)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            check(options.outWidth in 1..1024 && options.outHeight in 1..1024) { "Invalid thumbnail dimensions" }
            checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }
        currentCoroutineContext().ensureActive(); cache.put(key, bitmap); bitmap
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtifactFolderContent(rpc: ArtifactRpc, thumbnails: ArtifactThumbnails, destination: ArtifactDestination.Folder,
    onBack: () -> Unit, onDone: () -> Unit, retained: ArtifactFolderController? = null,
    connection: NativeFeedAvailability = NativeFeedAvailability.CONNECTED,
    onOpen: (ArtifactItem, List<ArtifactItem>) -> Unit) {
    val scope = rememberCoroutineScope()
    val controller = retained ?: remember { ArtifactFolderController(scope) }
    DisposableEffect(controller, retained) { onDispose { if (retained == null) controller.close() } }
    LaunchedEffect(controller, rpc, destination) { controller.open(rpc, destination) }
    val produced by controller.state.collectAsState()
    val state = produced.takeIf { controller.matches(it, rpc, destination) } ?: ArtifactFolderState(loading = true)
    val listing = state.listing
    val failure = state.failure?.presentation(destination.authorization, false, connection)
    val loading = state.loading
    Column(Modifier.fillMaxSize()) {
        FilesHeader(destination.item.displayName, onBack, onDone)
        Text(destination.item.path, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = filesMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        HorizontalDivider(color = filesPanel)
        PullToRefreshBox(loading, controller::retry, Modifier.weight(1f)) {
            LazyVerticalGrid(GridCells.Fixed(1), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (listing?.entries.isNullOrEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    FilesMessage(if (loading) "Loading folder…" else failure?.title ?: "Empty folder", failure?.message,
                        "Retry".takeIf { failure?.retry == true }, controller::retry)
                }
                items(listing?.entries.orEmpty(), key = { it.path }) { item ->
                    ArtifactGalleryRow(rpc, item, false, thumbnails, destination.authorization) { onOpen(item, listing!!.entries) }
                }
                if (!listing?.entries.isNullOrEmpty() && failure != null) item(span = { GridItemSpan(maxLineSpan) }) {
                    FilesMessage(failure.title, failure.message, "Retry".takeIf { failure.retry }, controller::retry)
                }
                if (listing?.truncated == true) item(span = { GridItemSpan(maxLineSpan) }) { Text("This folder contains more items than the Mac returned.", color = filesMuted, fontSize = 12.sp) }
            }
        }
    }
}
