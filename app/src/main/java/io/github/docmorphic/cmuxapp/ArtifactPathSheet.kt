package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.ensureActive

/** Direct terminal taps retain terminal authorization for every descendant and preview. */
@Composable
internal fun ArtifactPathSheet(rpc: ArtifactRpc, terminal: ArtifactAuthorization.Terminal, path: String, navigation: ArtifactNavigationState? = null,
    retainedPreview: ArtifactPreviewController? = null, onDismiss: () -> Unit) {
    val detail = navigation ?: remember(terminal, path) { ArtifactNavigationState() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF111316), contentColor = Color(0xFFE5E7EB)) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                key(rpc, terminal, path) { ArtifactPathContent(rpc, terminal, path, onDismiss, detail, retainedPreview) }
            }
        }
    }
}

@Composable
private fun ArtifactPathContent(rpc: ArtifactRpc, terminal: ArtifactAuthorization.Terminal, path: String, onDismiss: () -> Unit,
    navigation: ArtifactNavigationState, retainedPreview: ArtifactPreviewController?) {
    var routes by navigation::destinations
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val thumbnails = remember(rpc) { ArtifactThumbnails(rpc) }
    LaunchedEffect(retry) {
        failure = null
        if (!navigation.matches(terminal, null)) navigation.clearRoutes()
        if (routes.isNotEmpty()) return@LaunchedEffect
        try {
            val metadata = rpc.stat(terminal, path)
            ensureActive()
            check(metadata.getBoolean("exists")) { "This file no longer exists on your Mac." }
            val item = ArtifactItem(path, if (metadata.getBoolean("is_directory")) ArtifactKind.DIRECTORY else ArtifactKind.read(metadata.opt("kind")))
            routes = listOf(if (item.kind == ArtifactKind.DIRECTORY) ArtifactDestination.Folder(item, terminal)
                else ArtifactDestination.Preview(listOf(item), path, terminal))
        } catch (error: Exception) { ensureActive(); failure = error.message ?: "Could not open file" }
    }
    fun back() { if (routes.size <= 1) onDismiss() else navigation.back() }
    BackHandler { back() }
    LaunchedEffect(routes.lastOrNull(), retainedPreview) {
        if (routes.lastOrNull() !is ArtifactDestination.Preview) retainedPreview?.clear()
    }
    when (val route = routes.lastOrNull().takeIf { navigation.matches(terminal, null) }) {
        null -> Column(Modifier.fillMaxSize()) {
            FilesHeader(path.substringAfterLast('/'), null, onDismiss)
            FilesMessage(if (failure == null) "Loading preview…" else "Couldn't open file", failure, if (failure == null) null else "Retry") { retry++ }
        }
        is ArtifactDestination.Preview -> key(route) { ArtifactFilePreview(rpc, route, ::back, onDismiss,
            initialPath = navigation.selectedPath, onSelectionChanged = { navigation.selectedPath = it }, retained = retainedPreview) }
        is ArtifactDestination.Folder -> key(route) {
            ArtifactFolderContent(rpc, thumbnails, route, ::back, onDismiss) { item, entries ->
                navigation.open(if (item.kind == ArtifactKind.DIRECTORY) ArtifactDestination.Folder(item, terminal)
                    else ArtifactDestination.Preview(artifactSwipeOrder(entries), item.path, terminal))
            }
        }
    }
}
