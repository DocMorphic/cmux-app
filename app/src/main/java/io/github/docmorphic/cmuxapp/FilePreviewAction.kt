package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*

internal enum class FilePreviewAction(val label: String) {
    SHARE("Share"), SAVE("Save"), OPEN("Open"), COPY_IMAGE("Copy Image");
    companion object { val imageMenu = listOf(SHARE, SAVE, COPY_IMAGE) }
}

internal class FilePreviewActionHandler(val enabled: Boolean, val busy: Boolean, val perform: (FilePreviewAction) -> Unit)

/** Toolbar and image context menu use the same retained transfer owners and remote freshness policy. */
@Composable
internal fun filePreviewActionHandler(artifact: LocalFilePreview?, remote: RemoteArtifactSource?): FilePreviewActionHandler {
    val saves = checkNotNull(LocalFileSaves.current)
    val exports = checkNotNull(LocalFileExports.current)
    val exportState by exports.controller.state.collectAsState()
    return FilePreviewActionHandler((artifact != null || remote != null) && !saves.busy && !exportState.busy, saves.busy || exportState.busy) { action ->
        if (!saves.busy && !exports.controller.state.value.busy) {
            val source = remote.takeUnless { action == FilePreviewAction.COPY_IMAGE }
            if (artifact != null || source != null) when (action) {
                FilePreviewAction.SAVE -> saves.begin(artifact, source)
                FilePreviewAction.SHARE -> exports.begin(FileExportAction.SHARE, artifact, source)
                FilePreviewAction.OPEN -> exports.begin(FileExportAction.OPEN, artifact, source)
                FilePreviewAction.COPY_IMAGE -> exports.begin(FileExportAction.COPY_IMAGE, artifact, source)
            }
        }
    }
}
