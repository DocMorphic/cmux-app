package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

internal suspend fun MobileRpcClient.taskDirectoryPage(path: String, offset: Long): TaskDirectoryPage {
    if (!TaskDirectoryPaths.browsable(path) || offset < 0) throw TaskDirectoryPathError()
    return TaskDirectoryPage.read(request("mobile.directory.list", JSONObject().put("path", path).put("offset", offset).put("limit", 50), timeoutMillis = 4_000))
}
internal suspend fun MobileRpcClient.taskDirectorySearch(query: String) = TaskDirectorySearch.read(
    request("mobile.directory.search", JSONObject().put("query", query.trim()), timeoutMillis = 4_000))

/** Each request is tied to this exact composer connection and disappears with the picker. */
@Composable
internal fun TaskDirectoryPickerView(client: MobileRpcClient, origin: String, selectedPath: String,
    candidates: List<TaskDirectoryCandidate>, isCurrent: () -> Boolean,
    onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var stackJson by rememberSaveable(origin) { mutableStateOf(JSONArray(TaskDirectoryPaths.ancestry(selectedPath)).toString()) }
    val stack = remember(stackJson) { JSONArray(stackJson).let { a -> (0 until a.length()).map { a.getString(it) } } }
    val current = stack.lastOrNull()
    fun navigate(path: String) { stackJson = JSONArray(stack + path).toString() }
    fun back() { if (stack.isEmpty()) onDismiss() else stackJson = JSONArray(stack.dropLast(1)).toString() }
    BackHandler { back() }
    var query by rememberSaveable(origin, current) { mutableStateOf("") }
    var browse by remember(client, origin, current) { mutableStateOf(current?.let { TaskDirectoryBrowse().navigate(it) } ?: TaskDirectoryBrowse()) }
    var remote by remember(client, origin, current) { mutableStateOf<TaskDirectorySearch?>(null) }
    var searchError by remember(client, origin, current) { mutableStateOf<String?>(null) }
    var searching by remember(client, origin, current) { mutableStateOf(false) }
    var searchRetry by remember(client, origin, current) { mutableIntStateOf(0) }
    val currentGuard by rememberUpdatedState(isCurrent)
    val index = remember(candidates) { TaskDirectorySuggestions(candidates) }
    val suggested = remember(index) { index.suggestions("", 6) }
    val recents = remember(suggested) { suggested.filter { it.path !in setOf("~", "/") }.take(5) }
    val request = browse.pending
    LaunchedEffect(client, origin, current, request) {
        if (request == null) return@LaunchedEffect
        try {
            check(currentGuard()) { "Connection changed" }
            val page = client.taskDirectoryPage(request.path, request.offset)
            currentCoroutineContext().ensureActive()
            if (currentGuard()) browse = browse.receive(request, page)
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            val message = taskDirectoryFailure(failure, search = false)
            if (currentGuard()) browse = browse.fail(request, message)
        }
    }
    LaunchedEffect(client, origin, current, query, searchRetry) {
        remote = null; searchError = null; searching = false
        if (query.isBlank()) return@LaunchedEffect
        searching = true
        try {
            delay(140)
            check(currentGuard()) { "Connection changed" }
            val result = client.taskDirectorySearch(query)
            currentCoroutineContext().ensureActive()
            if (currentGuard()) { remote = result; searching = false }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            val message = taskDirectoryFailure(failure, search = true)
            if (currentGuard()) { searchError = message; searching = false }
        }
    }
    fun choose(path: String) { if (currentGuard()) onSelect(path) }
    val page = browse.snapshot
    val results = index.merged(remote, query)
    Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0C0E)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (stack.isNotEmpty()) TextButton(onClick = ::back, modifier = Modifier.semantics { contentDescription = "Parent folder" }) { Text("‹ Back") }
                Text(current?.let { TaskDirectoryPaths.name(page?.path ?: it) } ?: "Choose Folder", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search folders") },
                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }, modifier = Modifier.semantics { contentDescription = "Clear folder search" }) { Text("×") } })
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp)) {
                if (query.isNotBlank()) {
                    items(results, key = { it.path }) { candidate -> DirectoryCandidateRow(candidate, selectedPath) { choose(candidate.path) } }
                    item {
                        if (searching) Text("Searching this Mac…", Modifier.padding(12.dp))
                        else if (results.isEmpty() && searchError == null) Text("No Matching Folders", Modifier.padding(12.dp))
                        remote?.status?.let { Text(it, Modifier.padding(12.dp), color = Color(0xFF9B9FA8)) }
                        searchError?.let { Text(it, Modifier.padding(12.dp), color = Color(0xFFFF9999)); TextButton(onClick = { searchRetry++ }) { Text("Retry search") } }
                        Text("Search checks the Mac’s indexed folders and scans its home folder live. Browse to reach restricted locations.", Modifier.padding(12.dp), color = Color(0xFF9B9FA8), style = MaterialTheme.typography.bodySmall)
                    }
                } else if (current == null) {
                    if (suggested.isNotEmpty()) {
                        item { Text("Suggested", color = Color(0xFF9B9FA8), modifier = Modifier.padding(vertical = 8.dp)) }
                        items(suggested, key = { it.path }) { candidate -> DirectoryCandidateRow(candidate, selectedPath) { choose(candidate.path) } }
                    }
                    item {
                        Text("Locations", color = Color(0xFF9B9FA8), modifier = Modifier.padding(vertical = 12.dp))
                        TextButton(onClick = { navigate("~") }, modifier = Modifier.fillMaxWidth()) { Text("Home") }
                        TextButton(onClick = { navigate("/") }, modifier = Modifier.fillMaxWidth()) { Text("Computer") }
                        if (selectedPath.isNotBlank() && selectedPath !in setOf("~", "/")) TextButton(onClick = { navigate(selectedPath) }) { Text(selectedPath) }
                    }
                } else {
                    items(page?.entries.orEmpty(), key = { it.path }) { entry ->
                        Row(Modifier.fillMaxWidth().semantics { contentDescription = "Open folder: ${entry.name}" }
                            .clickable(enabled = entry.readable) { navigate(entry.path) }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(painterResource(R.drawable.ic_workspace_folder), null, Modifier.size(24.dp), tint = if (entry.readable) Color(0xFF76B9FF) else Color.Gray)
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, color = if (entry.readable) Color.White else Color.Gray)
                                val details = listOfNotNull("Hidden".takeIf { entry.hidden }, "Package".takeIf { entry.isPackage }, "Symbolic link".takeIf { entry.symbolicLink }, "Cannot read folder".takeUnless { entry.readable })
                                if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Color(0xFF9B9FA8))
                            }
                            Text("›", color = Color(0xFF9B9FA8))
                        }
                        HorizontalDivider(color = Color(0xFF30333A))
                    }
                    item {
                        if (browse.pending != null) Text("Loading folders…", Modifier.padding(12.dp))
                        if (browse.error != null) { Text(browse.error!!, Modifier.padding(12.dp), color = Color(0xFFFF9999)); TextButton(onClick = { browse = browse.retry() }) { Text("Retry folder") } }
                        if (page != null) {
                            Text(page.path, Modifier.padding(vertical = 12.dp), fontFamily = FontFamily.Monospace, color = Color(0xFF9B9FA8))
                            Text("${page.total} folders", color = Color(0xFF9B9FA8))
                            if (page.next != null && browse.pending == null && browse.error == null) {
                                LaunchedEffect(page.next) { browse = browse.next() }
                            }
                        }
                    }
                }
            }
            if (query.isBlank()) {
                if (recents.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    recents.forEach { item -> TextButton(onClick = { navigate(item.path) }, modifier = Modifier.semantics { contentDescription = "Browse recent folder: ${item.path}" }) { Text(TaskDirectoryPaths.name(item.path)) } }
                }
                if (current != null && page != null) Button(onClick = { choose(page.path) }, Modifier.fillMaxWidth().padding(bottom = 12.dp)) { Text("Choose ${TaskDirectoryPaths.name(page.path)}") }
            }
        }
    }
}

@Composable
private fun DirectoryCandidateRow(candidate: TaskDirectoryCandidate, selectedPath: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().semantics { selected = candidate.path == selectedPath; contentDescription = "Use folder: ${candidate.path}" }
        .clickable(onClick = onClick).padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_workspace_folder), null, Modifier.size(24.dp), tint = Color(0xFF76B9FF))
        Column(Modifier.weight(1f)) { Text(TaskDirectoryPaths.name(candidate.path)); Text(candidate.path, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9B9FA8))
            Text(listOfNotNull(candidate.source.label, candidate.context).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Color(0xFF9B9FA8)) }
        if (candidate.path == selectedPath) Text("✓")
    }
}
