package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
internal fun ArtifactFilePreview(rpc: ArtifactRpc, selection: ArtifactDestination.Preview, onBack: () -> Unit, onDone: () -> Unit,
    initialPath: String? = null, onSelectionChanged: (String) -> Unit = {}, retained: ArtifactPreviewController? = null) {
    val pager = rememberPagerState(initialPage = selection.files.indexOfFirst { it.path == (initialPath ?: selection.initialPath) }.coerceAtLeast(0), pageCount = { selection.files.size })
    val scope = rememberCoroutineScope()
    val selected by rememberUpdatedState(onSelectionChanged)
    LaunchedEffect(pager.settledPage) { selection.files.getOrNull(pager.settledPage)?.let { selected(it.path) } }
    Column(Modifier.fillMaxSize()) {
        FilesHeader(selection.files.getOrNull(pager.currentPage)?.displayName ?: "Preview", onBack, onDone)
        HorizontalPager(pager, key = { selection.files[it].path }, modifier = Modifier.weight(1f)) { index ->
            ArtifactPreviewPage(rpc, selection.authorization, selection.files[index].path, retained = retained,
                active = retained == null || index == pager.settledPage)
        }
        if (selection.files.size > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) { Text("Previous") }
            Text("${pager.currentPage + 1} of ${selection.files.size}", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 12.sp)
            TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage + 1 < selection.files.size) { Text("Next") }
        }
    }
}
@Composable
internal fun ArtifactPreviewPage(rpc: ArtifactRpc, authorization: ArtifactAuthorization, path: String, forceMarkdown: Boolean = false,
    retained: ArtifactPreviewController? = null, active: Boolean = true,
    connection: NativeFeedAvailability = NativeFeedAvailability.CONNECTED) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = retained ?: remember { ArtifactPreviewController(scope) }
    DisposableEffect(controller, retained) { onDispose { if (retained == null) controller.close() } }
    val root = File(context.cacheDir, "artifact-previews")
    LaunchedEffect(controller, rpc, authorization, path, forceMarkdown, root, active) {
        if (active) controller.open(rpc, authorization, path, root, forceMarkdown)
    }
    val produced by controller.state.collectAsState()
    val state = produced.takeIf { active && controller.matches(it, rpc, authorization, path, forceMarkdown) } ?: ArtifactPreviewState()
    Column(Modifier.fillMaxSize().semantics { contentDescription = "File preview $path" }) {
        if (state.artifact == null) FilePreviewActions(null)
        when {
            state.error != null -> {
                val failure = (state.failure ?: ArtifactPreviewFailure(ArtifactPreviewFailure.Kind.LOAD_FAILED))
                    .presentation(authorization, forceMarkdown && authorization is ArtifactAuthorization.Panel, connection) {
                        android.text.format.Formatter.formatShortFileSize(context, it)
                    }
                FilesMessage(failure.title, failure.message, "Retry".takeIf { failure.retry }, controller::retry)
            }
            state.artifact != null -> key(state.artifact!!.file.absolutePath) { FilePreviewContent(state.artifact!!) }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if ((state.total ?: 0) > 0) LinearProgressIndicator(progress = { (state.received.toFloat() / state.total!!).coerceIn(0f, 1f) })
                else LinearProgressIndicator()
                Text("Loading preview", Modifier.padding(12.dp))
            }
        }
    }
}
