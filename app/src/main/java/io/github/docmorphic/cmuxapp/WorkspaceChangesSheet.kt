package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

@Composable
internal fun WorkspaceChangesSheet(presentation: WorkspaceChangesPresentation, onDismiss: () -> Unit) {
    val access = presentation.access
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(access) {
        while (access.current()) delay(250)
        dismiss()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalWorkspaceShellChrome provides WorkspaceShellChrome()) {
            Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                ChangesContent(presentation.store, access.title, onDismiss, access.content, presentation.navigation)
            }
        }
    }
}
