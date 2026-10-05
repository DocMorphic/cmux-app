package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

internal val LocalFileSaves = staticCompositionLocalOf<FileSaveModel?> { null }

internal class FileSaveContract : ActivityResultContract<FileSaveSnapshot, Uri?>() {
    override fun createIntent(context: Context, input: FileSaveSnapshot) = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(input.mime).putExtra(Intent.EXTRA_TITLE, input.filename)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK && it.scheme == "content" }
}

/** Activity-owned, independent of the preview's loading/loaded call site and file lifetime. */
internal class FileSaveModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val files = FileSaveFiles(File(application.cacheDir, "file-saves"))
    private val resolver = application.contentResolver
    var pending by mutableStateOf(FileSaveSnapshot.decode(saved.get<String>(STATE)))
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var lastSavedUri by mutableStateOf<Uri?>(null)
        private set
    private var job: Job? = null
    val busy get() = pending != null
    val canRetry get() = pending?.let { it.phase == FileSavePhase.FAILED && files.file(it).isFile } == true
    init {
        when (pending?.phase) {
            FileSavePhase.PREPARING -> { discard(); failure = "Save preparation was interrupted. Open the file and try again." }
            FileSavePhase.WRITING -> write(checkNotNull(pending))
            FileSavePhase.FAILED -> failure = "The file wasn't saved. Try another destination."
            else -> Unit
        }
    }
    private fun update(value: FileSaveSnapshot?) { pending = value; saved[STATE] = value?.encode() }
    fun begin(artifact: LocalFilePreview) {
        if (busy) return
        val request = try {
            val type = fileActionType(artifact.file.name, artifact.mime)
            FileSaveSnapshot(UUID.randomUUID().toString(), type.filename, type.mime, FileSavePhase.PREPARING)
        } catch (_: Exception) { failure = "This file's name or type isn't supported. Reopen its preview and try again."; return }
        failure = null; lastSavedUri = null; update(request)
        job = viewModelScope.launch {
            try {
                files.prepare(request, artifact.file, artifact.size)
                ensureActive(); if (pending == request) update(request.copy(phase = FileSavePhase.READY))
            } catch (error: Exception) {
                ensureActive(); if (pending == request) { update(null); failure = "Couldn't prepare the file for saving. Reopen its preview and try again." }
            }
        }
    }
    fun launching(request: FileSaveSnapshot): Boolean {
        if (pending != request || request.phase != FileSavePhase.READY) return false
        update(request.copy(phase = FileSavePhase.WAITING)); return true
    }
    fun launchFailed(error: Exception) {
        pending?.takeIf { it.phase == FileSavePhase.WAITING }?.let { update(it.copy(phase = FileSavePhase.FAILED)) }
        failure = if (error is android.content.ActivityNotFoundException) "No installed file picker is available."
            else "Couldn't open the save picker. Try again."
    }
    fun result(uri: Uri?) {
        val request = pending?.takeIf { it.phase == FileSavePhase.WAITING } ?: return
        if (uri == null) { discard(); return }
        var writing = try { request.copy(phase = FileSavePhase.WRITING, destination = uri.toString()) }
            catch (error: Exception) { launchFailed(error); return }
        // Preserve grants already owned by another app feature. Some providers offer only task-lifetime access.
        val ownsGrant = runCatching {
            if (resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) false
            else { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION); true }
        }.getOrDefault(false)
        writing = writing.copy(ownsGrant = ownsGrant)
        update(writing); write(writing)
    }
    private fun write(request: FileSaveSnapshot) {
        job = viewModelScope.launch {
            try {
                val uri = Uri.parse(checkNotNull(request.destination))
                files.write(request) { checkNotNull(resolver.openOutputStream(uri, "wt")) { "Could not open the save destination." } }
                ensureActive()
                if (pending == request) { lastSavedUri = uri; discard() }
            } catch (error: Exception) {
                ensureActive(); if (pending == request) {
                    update(request.copy(phase = FileSavePhase.FAILED))
                    failure = if (error is SecurityException) "Permission to save here was denied. Choose another location."
                        else "Couldn't write to the selected location. Choose another location and try again."
                }
            }
        }
    }
    fun retry() {
        val request = pending ?: return
        if (!canRetry) return
        releaseGrant(request)
        failure = null; update(request.copy(phase = FileSavePhase.READY, destination = null, ownsGrant = false))
    }
    fun discard() {
        val request = pending; update(null); failure = null
        if (request != null) cleanup(request)
    }
    private fun releaseGrant(request: FileSaveSnapshot) {
        request.destination?.takeIf { request.ownsGrant }?.let { destination -> runCatching {
            resolver.releasePersistableUriPermission(Uri.parse(destination), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } }
    }
    private fun cleanup(request: FileSaveSnapshot) {
        // Cleanup must finish even when the Activity is permanently destroyed.
        viewModelScope.launch(NonCancellable + Dispatchers.IO) { runCatching { files.remove(request) } }
        releaseGrant(request)
    }
    override fun onCleared() { job?.cancel(); pending?.let(::cleanup) }
    companion object { private const val STATE = "file-save.snapshot.v1" }
}

/** Mounted once at the themed Activity root, before any conditional preview content. */
@Composable
internal fun FileSaveHost(content: @Composable () -> Unit) {
    if (LocalFileSaves.current != null) { content(); return }
    val owner = checkNotNull(LocalView.current.findViewTreeViewModelStoreOwner())
    val model = remember(owner) { ViewModelProvider(owner)[FileSaveModel::class.java] }
    val contract = remember { FileSaveContract() }
    val launcher = rememberLauncherForActivityResult(contract, model::result)
    val pending = model.pending
    LaunchedEffect(pending) {
        if (pending?.phase == FileSavePhase.READY && model.launching(pending)) {
            try { launcher.launch(pending) } catch (error: Exception) { model.launchFailed(error) }
        }
    }
    CompositionLocalProvider(LocalFileSaves provides model) { content() }
    model.failure?.let { message -> AlertDialog(onDismissRequest = model::discard,
        title = { Text("Couldn't save file") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = if (model.canRetry) model::retry else model::discard) {
            Text(if (model.canRetry) "Try again" else "OK")
        } }, dismissButton = { if (model.canRetry) TextButton(onClick = model::discard) { Text("Cancel") } }) }
}
