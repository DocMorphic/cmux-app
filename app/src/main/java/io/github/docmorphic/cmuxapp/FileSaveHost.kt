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
    private val files = FileSaveFiles(File(application.noBackupFilesDir, "file-saves"))
    private val legacyRoot = File(application.cacheDir, "file-saves")
    private val resolver = application.contentResolver
    var pending by mutableStateOf(FileSaveSnapshot.decode(saved.get<String>(STATE)))
        private set
    var restoring by mutableStateOf(pending != null)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var lastSavedUri by mutableStateOf<Uri?>(null)
        private set
    var progress by mutableStateOf<Pair<Long, Long>?>(null)
        private set
    private var job: Job? = null
    private var hasDeferredResult = false
    private var deferredResult: Uri? = null
    val busy get() = pending != null || restoring
    val canRetry get() = !restoring && pending?.let { it.phase == FileSavePhase.FAILED && files.file(it).isFile } == true
    init {
        pending?.let { snapshot -> job = viewModelScope.launch {
            try {
                val recovered = files.restore(snapshot, legacyRoot)
                ensureActive(); update(recovered)
                when (recovered.phase) {
                    FileSavePhase.COMPLETED, FileSavePhase.CANCELLED -> {
                        withContext(Dispatchers.IO) {
                            files.finish(recovered, recovered.phase)
                            releaseGrant(recovered)
                            runCatching { files.record(recovered.copy(ownsGrant = false)) }
                        }
                        if (recovered.phase == FileSavePhase.COMPLETED)
                            lastSavedUri = recovered.destination?.let(Uri::parse)
                        update(null)
                    }
                    FileSavePhase.FAILED -> failure = "The file wasn't saved. Try another destination."
                    else -> Unit
                }
            } catch (error: Exception) {
                ensureActive(); update(null)
                cleanup(snapshot, null)
                failure = "The saved file copy is unavailable or incomplete. Reopen its preview and save it again."
            } finally {
                if (currentCoroutineContext().isActive) {
                    restoring = false
                    if (pending?.phase == FileSavePhase.WRITING) write(checkNotNull(pending), acquireGrant = false)
                    else if (hasDeferredResult) {
                        val uri = deferredResult; hasDeferredResult = false; deferredResult = null; result(uri)
                    }
                }
            }
        } }
    }
    private fun update(value: FileSaveSnapshot?) { pending = value; saved[STATE] = value?.encode() }
    private suspend fun record(value: FileSaveSnapshot) = withContext(Dispatchers.IO) { files.record(value) }
    fun begin(artifact: LocalFilePreview) {
        if (busy) return
        val request = try {
            val type = fileActionType(artifact.file.name, artifact.mime)
            FileSaveSnapshot(UUID.randomUUID().toString(), type.filename, type.mime, FileSavePhase.PREPARING)
        } catch (_: Exception) { failure = "This file's name or type isn't supported. Reopen its preview and try again."; return }
        failure = null; lastSavedUri = null; progress = null; update(request)
        job = viewModelScope.launch {
            try {
                files.prepare(request, artifact.file, artifact.size)
                val ready = request.copy(phase = FileSavePhase.READY)
                record(ready); ensureActive(); if (pending == request) update(ready)
            } catch (error: Exception) {
                ensureActive(); if (pending == request) {
                    update(null); cleanup(request, null)
                    failure = "Couldn't prepare the file for saving. Reopen its preview and try again."
                }
            }
        }
    }
    suspend fun launching(request: FileSaveSnapshot): Boolean {
        if (restoring || pending != request || request.phase != FileSavePhase.READY) return false
        val waiting = request.copy(phase = FileSavePhase.WAITING)
        record(waiting)
        if (pending != request) return false
        update(waiting); return true
    }
    fun launchFailed(error: Exception) {
        val request = pending?.takeIf { it.phase == FileSavePhase.WAITING || it.phase == FileSavePhase.READY } ?: return
        val failed = request.copy(phase = FileSavePhase.FAILED); update(failed)
        job = viewModelScope.launch { runCatching { record(failed) } }
        failure = if (error is android.content.ActivityNotFoundException) "No installed file picker is available."
            else "Couldn't open the save picker. Try again."
    }
    fun result(uri: Uri?) {
        if (restoring) { hasDeferredResult = true; deferredResult = uri; return }
        val request = pending?.takeIf { it.phase == FileSavePhase.WAITING } ?: return
        if (uri == null) { discard(); return }
        val writing = try { request.copy(phase = FileSavePhase.WRITING, destination = uri.toString()) }
            catch (error: Exception) { launchFailed(error); return }
        update(writing); write(writing, acquireGrant = true)
    }
    private fun write(request: FileSaveSnapshot, acquireGrant: Boolean) {
        job = viewModelScope.launch {
            var writing = request
            try {
                val uri = Uri.parse(checkNotNull(writing.destination))
                if (acquireGrant) {
                    // A provider may offer only task-lifetime access. Preserve grants owned by other features.
                    writing = withContext(Dispatchers.IO) {
                        val owned = runCatching {
                            if (resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) false
                            else { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION); true }
                        }.getOrDefault(false)
                        writing.copy(ownsGrant = owned).also { durable ->
                            try { files.record(durable) }
                            catch (error: Exception) { releaseGrant(durable); throw error }
                        }
                    }
                    ensureActive(); update(writing)
                }
                record(writing)
                var reported = -1L
                files.write(writing, progress = { received, total ->
                    if (received == total || reported < 0 || received - reported >= 1024 * 1024) {
                        reported = received
                        withContext(Dispatchers.Main.immediate) { if (pending == writing) progress = received to total }
                    }
                }) { checkNotNull(resolver.openOutputStream(uri, "wt")) { "Could not open the save destination." } }
                withContext(Dispatchers.IO) {
                    files.finish(writing, FileSavePhase.COMPLETED)
                    releaseGrant(writing)
                    runCatching { files.record(writing.copy(phase = FileSavePhase.COMPLETED, ownsGrant = false)) }
                }
                ensureActive()
                if (pending == writing) { lastSavedUri = uri; update(null); progress = null }
            } catch (error: Exception) {
                ensureActive(); if (pending == writing) {
                    val failed = writing.copy(phase = FileSavePhase.FAILED)
                    runCatching { record(failed) }; update(failed); progress = null
                    failure = if (error is SecurityException) "Permission to save here was denied. Choose another location."
                        else "Couldn't write to the selected location. Choose another location and try again."
                }
            }
        }
    }
    fun retry() {
        val request = pending ?: return
        if (!canRetry || job?.isActive == true) return
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { releaseGrant(request) }
                val ready = request.copy(phase = FileSavePhase.READY, destination = null, ownsGrant = false)
                record(ready); ensureActive()
                if (pending == request) { failure = null; update(ready) }
            } catch (error: Exception) { ensureActive(); failure = "Couldn't prepare the file for another save. Try again." }
        }
    }
    fun discard() {
        val request = pending; val previousJob = job; job = null; previousJob?.cancel()
        update(null); failure = null; progress = null; restoring = false
        hasDeferredResult = false; deferredResult = null
        if (request != null) cleanup(request, previousJob)
    }
    private fun releaseGrant(request: FileSaveSnapshot) {
        request.destination?.takeIf { request.ownsGrant }?.let { destination -> runCatching {
            resolver.releasePersistableUriPermission(Uri.parse(destination), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } }
    }
    private fun cleanup(request: FileSaveSnapshot, previousJob: Job?) {
        // Wait for a cancelled writer before removing its bytes or releasing its provider grant.
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            previousJob?.join()
            val latest = runCatching { files.latest(request) }.getOrDefault(request)
            val terminalPhase = latest.phase.takeIf { it == FileSavePhase.COMPLETED || it == FileSavePhase.CANCELLED }
                ?: FileSavePhase.CANCELLED
            runCatching { files.finish(latest, terminalPhase) }.onFailure { runCatching { files.remove(latest) } }
            releaseGrant(latest)
            runCatching { files.record(files.latest(latest).copy(ownsGrant = false)) }
        }
    }
    override fun onCleared() { discard() }
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
    LaunchedEffect(pending, model.restoring) {
        if (pending?.phase == FileSavePhase.READY && !model.restoring) {
            try { if (model.launching(pending)) launcher.launch(pending) }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); model.launchFailed(error) }
        }
    }
    CompositionLocalProvider(LocalFileSaves provides model) { content() }
    if (pending?.phase == FileSavePhase.WRITING && model.failure == null) AlertDialog(
        onDismissRequest = {}, title = { Text("Saving file…") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(pending.filename)
                val progress = model.progress
                if (progress != null && progress.second > 0)
                    LinearProgressIndicator(progress = { (progress.first.toFloat() / progress.second).coerceIn(0f, 1f) })
                else LinearProgressIndicator()
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = model::discard) { Text("Cancel") } })
    model.failure?.let { message -> AlertDialog(onDismissRequest = model::discard,
        title = { Text("Couldn't save file") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = if (model.canRetry) model::retry else model::discard) {
            Text(if (model.canRetry) "Try again" else "OK")
        } }, dismissButton = { if (model.canRetry) TextButton(onClick = model::discard) { Text("Cancel") } }) }
}
