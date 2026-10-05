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
    retainedPreview: ArtifactPreviewController? = null, retainedFolder: ArtifactFolderController? = null, connection: NativeFeedAvailability = NativeFeedAvailability.CONNECTED,
    onDismiss: () -> Unit) {
    val detail = navigation ?: remember(terminal, path) { ArtifactNavigationState() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF111316), contentColor = Color(0xFFE5E7EB)) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                key(terminal, path) { ArtifactPathContent(rpc, terminal, path, onDismiss, detail, retainedPreview, retainedFolder, connection) }
            }
        }
    }
}

@Composable
private fun ArtifactPathContent(rpc: ArtifactRpc, terminal: ArtifactAuthorization.Terminal, path: String, onDismiss: () -> Unit,
    navigation: ArtifactNavigationState, retainedPreview: ArtifactPreviewController?, retainedFolder: ArtifactFolderController?, connection: NativeFeedAvailability) {
    var routes by navigation::destinations
    var failure by remember { mutableStateOf<ArtifactPreviewFailure?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val thumbnails = remember(rpc) { ArtifactThumbnails(rpc) }
    LaunchedEffect(rpc, retry, connection) {
        failure = null
        if (!navigation.matches(terminal, null)) navigation.clearRoutes()
        if (routes.isNotEmpty()) return@LaunchedEffect
        if (connection != NativeFeedAvailability.CONNECTED) {
            failure = ArtifactPreviewFailure(ArtifactPreviewFailure.Kind.MAC_UNREACHABLE)
            return@LaunchedEffect
        }
        try {
            val metadata = rpc.stat(terminal, path)
            ensureActive()
            if (!metadata.getBoolean("exists")) throw ArtifactPreviewException(
                ArtifactPreviewFailure(ArtifactPreviewFailure.Kind.FILE_NOT_FOUND), "This file no longer exists on your Mac.")
            val item = ArtifactItem(path, if (metadata.getBoolean("is_directory")) ArtifactKind.DIRECTORY else ArtifactKind.read(metadata.opt("kind")))
            routes = listOf(if (item.kind == ArtifactKind.DIRECTORY) ArtifactDestination.Folder(item, terminal)
                else ArtifactDestination.Preview(listOf(item), path, terminal))
        } catch (error: Exception) { ensureActive(); failure = ArtifactPreviewFailure.from(error, terminal) }
    }
    fun back() { if (routes.size <= 1) onDismiss() else navigation.back() }
    BackHandler { back() }
    LaunchedEffect(routes.lastOrNull(), retainedPreview) {
        if (routes.lastOrNull() !is ArtifactDestination.Preview) retainedPreview?.clear()
    }
    when (val route = routes.lastOrNull().takeIf { navigation.matches(terminal, null) }) {
        null -> Column(Modifier.fillMaxSize()) {
            FilesHeader(path.substringAfterLast('/'), null, onDismiss)
            val copy = failure?.presentation(terminal, false, connection)
            FilesMessage(copy?.title ?: "Loading preview…", copy?.message, "Retry".takeIf { copy?.retry == true }) { retry++ }
        }
        is ArtifactDestination.Preview -> key(route) { ArtifactFilePreview(rpc, route, ::back, onDismiss,
            initialPath = navigation.selectedPath, onSelectionChanged = { navigation.selectedPath = it }, retained = retainedPreview, connection = connection) }
        is ArtifactDestination.Folder -> key(route) {
            ArtifactFolderContent(rpc, thumbnails, route, ::back, onDismiss, retained = retainedFolder, connection = connection) { item, entries ->
                navigation.open(if (item.kind == ArtifactKind.DIRECTORY) ArtifactDestination.Folder(item, terminal)
                    else ArtifactDestination.Preview(artifactSwipeOrder(entries), item.path, terminal))
            }
        }
    }
}
