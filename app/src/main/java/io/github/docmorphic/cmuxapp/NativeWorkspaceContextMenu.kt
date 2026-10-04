package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.CustomAccessibilityAction

/** Shared by the list's single long-press/drag recognizer and each owning row. */
internal data class WorkspaceContextMenuKey(val row: String, val owner: NativeCredentialStore.PairedMac? = null)
internal class WorkspaceContextMenuCoordinator {
    var activeKey by mutableStateOf<WorkspaceContextMenuKey?>(null)
    var heldKey by mutableStateOf<WorkspaceContextMenuKey?>(null)
}
internal val LocalWorkspaceContextMenus = compositionLocalOf<WorkspaceContextMenuCoordinator?> { null }
internal val LocalWorkspaceContextKey = compositionLocalOf<WorkspaceContextMenuKey?> { null }
internal val LocalWorkspaceMoveActions = compositionLocalOf<List<CustomAccessibilityAction>> { emptyList() }

internal class WorkspaceContextMenu(private val coordinator: WorkspaceContextMenuCoordinator, private val key: WorkspaceContextMenuKey) {
    var expanded: Boolean
        get() = coordinator.activeKey == key
        set(value) { if (value) coordinator.activeKey = key else if (expanded) coordinator.activeKey = null }
    // Preserve the original window's held gesture until release or drag admission.
    val held get() = coordinator.heldKey == key
}

@Composable
internal fun rememberWorkspaceContextMenu(id: String): WorkspaceContextMenu {
    val fallback = remember { WorkspaceContextMenuCoordinator() }
    val coordinator = LocalWorkspaceContextMenus.current ?: fallback
    val key = LocalWorkspaceContextKey.current ?: WorkspaceContextMenuKey(id)
    val menu = remember(coordinator, key) { WorkspaceContextMenu(coordinator, key) }
    DisposableEffect(menu) { onDispose { menu.expanded = false } }
    return menu
}
