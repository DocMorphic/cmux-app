package io.github.docmorphic.cmuxapp

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.FileProvider
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

internal val LocalFileExports = staticCompositionLocalOf<FileExportModel?> { null }

internal class FileExportModel(application: Application) : AndroidViewModel(application) {
    val controller = FileExportController(viewModelScope)
    private val root = File(application.cacheDir, "task-previews")
    fun begin(action: FileExportAction, artifact: LocalFilePreview?, remote: RemoteArtifactSource? = null) {
        if (artifact == null && remote == null) return
        val key = remote ?: checkNotNull(artifact).file.absolutePath
        val title = remote?.let { changesPreviewName(it.path) } ?: checkNotNull(artifact).file.name
        controller.begin(key, title, action, failure = { error ->
            if (remote == null) "Couldn't prepare this file. Open its preview and try again."
            else ArtifactPreviewFailure.from(error, remote.authorization)
                .presentation(remote.authorization, false, NativeFeedAvailability.CONNECTED).let { "${it.title}. ${it.message}" }
        }) {
            if (remote != null) remote.materialize(root) { metadata -> fileActionType(changesPreviewName(remote.path), metadata.mime).filename }
            else {
                val local = checkNotNull(artifact); val type = fileActionType(local.file.name, local.mime)
                val exported = exportFilePreview(local, root, type.filename)
                local.copy(file = exported, mime = type.mime)
            }
        }
    }
    override fun onCleared() { controller.close() }
}

/** Only the resumed Activity launches the chooser. No Activity/context is kept in the retained model. */
@Composable
internal fun FileExportHost(content: @Composable () -> Unit) {
    if (LocalFileExports.current != null) { content(); return }
    val owner = checkNotNull(LocalView.current.findViewTreeViewModelStoreOwner())
    val model = remember(owner) { ViewModelProvider(owner)[FileExportModel::class.java] }
    val controller = model.controller
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context by rememberUpdatedState(LocalContext.current)
    val state by controller.state.collectAsState()
    LaunchedEffect(controller, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            controller.state.collect { ready ->
                val artifact = controller.claim(ready) ?: return@collect
                try {
                    val file = artifact.file; val mime = fileActionType(file.name, artifact.mime).mime
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.task-previews", file)
                    if (ready.action == FileExportAction.COPY_IMAGE) {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newUri(context.contentResolver, file.name, uri))
                    } else {
                        val share = ready.action == FileExportAction.SHARE
                        val intent = if (share) artifactShareIntent(context, file, mime)
                            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        intent.clipData = ClipData.newRawUri(file.name, uri)
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(intent, "${if (share) "Share" else "Open"} ${file.name}"))
                    }
                    controller.handedOff(ready)
                } catch (error: Exception) {
                    controller.presentationFailed(ready, if (error is android.content.ActivityNotFoundException)
                        "No installed app can open this file." else "Couldn't open this file action. Try again.")
                }
            }
        }
    }
    CompositionLocalProvider(LocalFileExports provides model) { content() }
    if (state.phase == FileExportPhase.PREPARING) AlertDialog(onDismissRequest = controller::clear,
        title = { Text("Preparing file…") }, text = { Column { Text(state.title); LinearProgressIndicator() } },
        confirmButton = {}, dismissButton = { TextButton(onClick = controller::clear) { Text("Cancel") } })
    state.failure?.let { message -> AlertDialog(onDismissRequest = controller::clear,
        title = { Text("Couldn't prepare file") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = controller::clear) { Text("OK") } }) }
}
