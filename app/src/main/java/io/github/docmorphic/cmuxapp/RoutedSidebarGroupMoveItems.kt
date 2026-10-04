package io.github.docmorphic.cmuxapp

import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The shared anchored submenu stays mounted while its bounded IPC pages load. */
@Composable
internal fun RoutedSidebarGroupMoveItems(controller: RoutedSidebarController, key: String, onBack: () -> Unit,
    onMove: (RoutedSidebarMutation) -> Unit) {
    var page by remember(controller, key) { mutableStateOf<RoutedSidebarGroupPage?>(null) }
    var failure by remember(controller, key) { mutableStateOf<String?>(null) }
    var attempt by remember(controller, key) { mutableIntStateOf(0) }
    LaunchedEffect(controller, key, attempt) {
        page = null; failure = null
        try { page = controller.groupMenu(key) }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            failure = error.message ?: "Could not load groups."
        }
    }
    NativeWorkspaceGroupMoveItems(page?.menu() ?: NativeWorkspaceGroupMoveMenu(), onBack) { destination ->
        page?.let { onMove(RoutedSidebarMutation(key, RoutedSidebarMutationKind.MOVE_TO_GROUP,
            menuRevision = it.revision, destination = destination)) }
    }
    if (page == null) {
        DropdownMenuItem(text = { Text(failure ?: "Loading groups…") }, enabled = false, onClick = {})
        if (failure != null) DropdownMenuItem(text = { Text("Retry") }, onClick = { attempt++ })
    }
}
