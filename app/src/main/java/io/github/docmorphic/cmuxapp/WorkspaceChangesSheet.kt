package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

@Composable
internal fun WorkspaceChangesSheet(access: WorkspaceChangesAccess, onDismiss: () -> Unit,
    navigation: ChangesNavigationState = remember(access) { ChangesNavigationState() }) {
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(access) {
        while (access.current()) delay(250)
        dismiss()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalWorkspaceShellChrome provides WorkspaceShellChrome()) {
            Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                val scope = rememberCoroutineScope()
                val store = remember(access) { ChangesStore(scope, access.workspaceId,
                    { access.read(WorkspaceChangesRead.Files) }, { path, budget -> access.read(WorkspaceChangesRead.Diff(path, budget)) },
                    fetchLines = { path -> access.content.currentLines(path) }) }
                DisposableEffect(store) { onDispose { store.close() } }
                LaunchedEffect(store) { store.refresh().join() }
                ChangesContent(store, access.title, onDismiss, access.content, navigation)
            }
        }
    }
}
