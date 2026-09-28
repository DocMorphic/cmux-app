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
internal fun ArtifactFilePreview(rpc: ArtifactRpc, selection: ArtifactDestination.Preview, onBack: () -> Unit, onDone: () -> Unit) {
    val pager = rememberPagerState(initialPage = selection.files.indexOfFirst { it.path == selection.initialPath }.coerceAtLeast(0), pageCount = { selection.files.size })
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        FilesHeader(selection.files.getOrNull(pager.currentPage)?.displayName ?: "Preview", onBack, onDone)
        HorizontalPager(pager, key = { selection.files[it].path }, modifier = Modifier.weight(1f)) { index ->
            ArtifactPreviewPage(rpc, selection.authorization, selection.files[index].path)
        }
        if (selection.files.size > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) { Text("Previous") }
            Text("${pager.currentPage + 1} of ${selection.files.size}", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 12.sp)
            TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage + 1 < selection.files.size) { Text("Next") }
        }
    }
}
private data class ArtifactPreviewLoad(val identity: Any? = null, val artifact: LocalFilePreview? = null, val total: Long? = null,
    val received: Long = 0, val error: String? = null)
@Composable
private fun ArtifactPreviewPage(rpc: ArtifactRpc, authorization: ArtifactAuthorization, path: String) {
    val context = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    val identity = remember(rpc, authorization, path, retry) { Any() }
    val produced by produceState(ArtifactPreviewLoad(), identity) {
        value = ArtifactPreviewLoad(identity)
        val transfer = ArtifactContentTransfer(rpc, authorization)
        val files = ArtifactPreviewFiles(File(context.cacheDir, "artifact-previews"), transfer)
        try {
            val metadata = transfer.metadata(path)
            value = value.copy(total = metadata.size)
            val artifact = files.download(path, metadata) { received, total ->
                withContext(Dispatchers.Main) { value = value.copy(received = received, total = total) }
            }
            ensureActive(); value = value.copy(artifact = artifact); awaitCancellation()
        } catch (failure: Exception) {
            ensureActive(); value = value.copy(error = failure.message ?: "Could not load preview"); awaitCancellation()
        } finally { withContext(NonCancellable + Dispatchers.IO) { files.close() } }
    }
    val state = produced.takeIf { it.identity === identity } ?: ArtifactPreviewLoad(identity)
    Column(Modifier.fillMaxSize().semantics { contentDescription = "File preview $path" }) {
        FilePreviewActions(state.artifact)
        when {
            state.error != null -> FilesMessage("Couldn't load preview", state.error, "Retry") { retry++ }
            state.artifact != null -> key(state.artifact!!.file.absolutePath) { FilePreviewContent(state.artifact!!) }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if ((state.total ?: 0) > 0) LinearProgressIndicator(progress = { (state.received.toFloat() / state.total!!).coerceIn(0f, 1f) })
                else LinearProgressIndicator()
                Text("Loading preview", Modifier.padding(12.dp))
            }
        }
    }
}
