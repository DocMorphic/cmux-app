package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

internal val changesMuted = Color(0xFF969CA6)
internal val changesAdded = Color(0xFF2EA043)
internal val changesRemoved = Color(0xFFF85149)

@Composable
internal fun NativeChangesView(client: MobileRpcClient, workspaceId: String, title: String, onBack: () -> Unit,
    navigation: ChangesNavigationState? = null) {
    val scope = rememberCoroutineScope()
    val store = remember(client, workspaceId) { ChangesStore(scope, workspaceId,
        { client.changedFiles(workspaceId) }, { path, budget -> client.fileDiff(workspaceId, path,
            budget.takeIf { it != DiffContinuation.DEFAULT_BUDGET }) },
        fetchLines = { path -> client.changesContent(workspaceId).currentLines(path) }) }
    DisposableEffect(store) { onDispose { store.close() } }
    LaunchedEffect(store) { store.refresh().join() }
    val content = remember(client, workspaceId) { client.changesContent(workspaceId) }
    val detail = navigation ?: remember(client, workspaceId) { ChangesNavigationState() }
    ChangesContent(store, title, onBack, content, detail)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChangesContent(store: ChangesStore, title: String, onBack: () -> Unit, content: ChangesContentTransfer? = null,
    navigation: ChangesNavigationState) {
    val listing by store.listing.collectAsState()
    var selected by navigation::selected
    val snapshot = listing.snapshot
    val files = snapshot?.files.orEmpty()
    val preferences = LocalContext.current.getSharedPreferences("cmux-display", Context.MODE_PRIVATE)
    var fontSize by remember { mutableFloatStateOf(clampDiffFont(preferences.getFloat("diff-font-size", 12f))) }
    fun returnToList() { store.clearSelection(); selected = null }
    BackHandler { if (selected == null) onBack() else returnToList() }
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0C0E))) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (LocalWorkspaceShellChrome.current.let { it.split && !it.sidebarVisible }) NativeWorkspaceSidebarToggle()
            if (selected == null) TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Close changes" }) { Text("×") }
            else TextButton(onClick = ::returnToList) { Text("‹ Changes") }
            Text("Changes", Modifier.weight(1f).semantics { contentDescription = "Changes in $title" }, fontWeight = FontWeight.SemiBold)
            if (selected == null) IconButton(onClick = { store.refresh() }, enabled = !listing.loading,
                modifier = Modifier.semantics { contentDescription = "Refresh changes" }) {
                Icon(painterResource(R.drawable.ic_browser_reload), null, Modifier.size(20.dp))
            }
        }
        if (selected == null) {
            if (snapshot != null && !listing.notRepository) ChangesSummary(snapshot)
            ChangesFileList(listing, store, navigation, Modifier.weight(1f)) { selected = it }
        } else if (listing.notRepository || (snapshot != null && files.none { it.path == selected })) {
            ChangesNotice("File no longer changed", "Return to Changes to choose a current file.")
        } else if (snapshot == null) {
            if (listing.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else ChangesNotice("Couldn't load changes", listing.error ?: "Check the connection to your Mac.") { store.refresh() }
        } else {
            // A changed file set remounts at the selected path, never at a neighbor's old index.
            key(files.map { it.path }) {
                val pager = rememberPagerState(initialPage = files.indexOfFirst { it.path == selected }.coerceAtLeast(0), pageCount = { files.size })
                val scope = rememberCoroutineScope()
                LaunchedEffect(store, pager.settledPage) { files.getOrNull(pager.settledPage)?.let { selected = it.path; store.select(it.path) } }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(files.getOrNull(pager.currentPage)?.filename.orEmpty(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold)
                    Text("${pager.currentPage + 1} of ${files.size}", color = changesMuted, fontSize = 12.sp)
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0,
                        modifier = Modifier.size(40.dp).semantics { contentDescription = "Previous changed file" }) {
                        Icon(painterResource(R.drawable.ic_browser_back), null, Modifier.size(18.dp))
                    }
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage < files.lastIndex,
                        modifier = Modifier.size(40.dp).semantics { contentDescription = "Next changed file" }) {
                        Icon(painterResource(R.drawable.ic_browser_forward), null, Modifier.size(18.dp))
                    }
                }
                HorizontalDivider(color = Color(0xFF292C31))
                HorizontalPager(pager, Modifier.weight(1f), key = { files[it].path }) { index ->
                    ChangesDiffPage(store, files[index], fontSize, { fontSize = clampDiffFont(it) },
                        { preferences.edit().putFloat("diff-font-size", clampDiffFont(it)).apply() }, content, active = files[index].path == selected)
                }
            }
        }
    }
}

@Composable
private fun ChangesSummary(snapshot: ChangesSnapshot) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF15171A)).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text("${snapshot.branch ?: "Detached HEAD"}  →  ${snapshot.base ?: "HEAD"}", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${snapshot.fileCount} files changed", fontSize = 12.sp, color = changesMuted)
            Text("+${snapshot.additions}", fontSize = 12.sp, color = changesAdded)
            Text("−${snapshot.deletions}", fontSize = 12.sp, color = changesRemoved)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangesFileList(state: ChangesListState, store: ChangesStore, navigation: ChangesNavigationState,
    modifier: Modifier, onSelect: (String) -> Unit) {
    var collapsed by navigation::collapsed
    val tree = remember(state.snapshot?.files) { ChangedFilesTree(state.snapshot?.files.orEmpty()) }
    val rows = remember(tree, collapsed, state.error, state.notRepository) {
        if (state.error != null || state.notRepository) emptyList() else tree.rows(collapsed)
    }
    PullToRefreshBox(state.loading, { store.refresh() }, modifier) {
        LazyColumn(Modifier.fillMaxSize()) {
            when {
                state.notRepository -> item { ChangesNotice("Not a Git repository", "This workspace's directory isn't inside a Git repository.") }
                state.error != null -> item { ChangesNotice("Couldn't load changes", state.error) { store.refresh() } }
                state.loading && state.snapshot == null -> item { Text("Loading changes…", Modifier.padding(24.dp), color = changesMuted) }
                state.snapshot?.files?.isEmpty() == true -> item { ChangesNotice("No changes", "This workspace matches ${state.snapshot.base ?: "HEAD"}.") }
            }
            items(rows, key = { it.id }) { row ->
                when (row) {
                    is ChangesTreeRow.Directory -> Row(Modifier.fillMaxWidth().clickable {
                        collapsed = if (row.path in collapsed) collapsed - row.path else collapsed + row.path
                    }.padding(start = (14 + row.depth * 14).dp, end = 18.dp, top = 12.dp, bottom = 12.dp)
                        .semantics { contentDescription = "${if (row.path in collapsed) "Expand" else "Collapse"} folder ${row.path}" },
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (row.path in collapsed) "›" else "⌄", Modifier.width(18.dp), color = changesMuted)
                        Icon(painterResource(R.drawable.ic_workspace_folder), null, Modifier.size(18.dp), tint = changesMuted)
                        Text(row.name, Modifier.weight(1f).padding(start = 8.dp), fontSize = 14.sp)
                        Text(row.count.toString(), fontSize = 12.sp, color = changesMuted)
                    }
                    is ChangesTreeRow.File -> {
                        val file = row.file
                        Row(Modifier.fillMaxWidth().clickable { onSelect(file.path) }
                            .padding(start = (18 + row.depth * 14).dp, end = 18.dp, top = 12.dp, bottom = 12.dp)
                            .semantics { contentDescription = "Open diff ${file.path}" }, verticalAlignment = Alignment.CenterVertically) {
                            Text(file.kind.badge, Modifier.width(26.dp), fontWeight = FontWeight.Bold, color = when (file.kind) {
                                ChangeKind.ADDED, ChangeKind.UNTRACKED -> changesAdded
                                ChangeKind.DELETED -> changesRemoved
                                else -> Color(0xFF76B9FF)
                            })
                            Column(Modifier.weight(1f)) {
                                Text(file.filename, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                file.oldPath?.takeIf { it.isNotEmpty() }?.let { Text("from $it", color = changesMuted, fontSize = 11.sp) }
                            }
                            if (file.binary) Text("Binary", fontSize = 12.sp, color = changesMuted)
                            else Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("+${if (file.approximate) "≥" else ""}${file.additions}", color = changesAdded, fontSize = 12.sp)
                                Text("−${file.deletions}", color = changesRemoved, fontSize = 12.sp)
                            }
                        }
                        HorizontalDivider(color = Color(0xFF292C31))
                    }
                }
            }
            if (state.snapshot?.truncated == true) item { Text("Showing the first ${state.snapshot.files.size} changed files. See the rest on your Mac.",
                Modifier.padding(18.dp), color = changesMuted, fontSize = 12.sp) }
        }
    }
}

@Composable
internal fun ChangesNotice(title: String, detail: String, retry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(detail, Modifier.padding(top = 8.dp), color = changesMuted, fontSize = 13.sp)
        if (retry != null) TextButton(onClick = retry) { Text("Retry") }
    }
}
