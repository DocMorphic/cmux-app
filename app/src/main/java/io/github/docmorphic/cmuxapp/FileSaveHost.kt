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
import java.util.concurrent.ConcurrentHashMap
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
    private val uiLeases = ConcurrentHashMap<String, FileSaveUiLease>()
    private var hasDeferredResult = false
    private var deferredResult: Uri? = null
    val busy get() = pending != null || restoring
    val canRetry get() = !restoring && pending?.let { it.phase == FileSavePhase.FAILED && files.file(it).isFile } == true
    init { pending?.let { restore(it) } }
    private fun releaseUi(id: String) {
        uiLeases.remove(id)?.close()
    }
    fun recoverPending() {
        if (busy || failure != null || job?.isActive == true) return
        restore(null)
    }
    private fun restore(savedSnapshot: FileSaveSnapshot?) {
        restoring = true
        job = viewModelScope.launch {
            var snapshot = savedSnapshot
            var acquiredId: String? = null
            try {
                val claimed = withContext(NonCancellable + Dispatchers.IO) {
                    val candidates = savedSnapshot?.let(::listOf) ?: files.recoveryCandidates()
                    candidates.firstOrNull { candidate ->
                        files.claimUi(candidate.id)?.let {
                            acquiredId = candidate.id; uiLeases[candidate.id] = it; true
                        } ?: false
                    }
                }
                snapshot = claimed
                ensureActive()
                if (claimed == null) { update(null); return@launch }
                update(claimed)
                val disk = withContext(Dispatchers.IO) { files.latest(claimed) }
                val recovered = if (disk.phase == FileSavePhase.WRITING || disk.phase in FileSaveFiles.terminal) disk else {
                    val value = files.restore(claimed, legacyRoot)
                    // A fresh Activity has no matching outstanding picker result. Ask before opening another picker.
                    if (savedSnapshot == null && value.phase !in FileSaveFiles.terminal)
                        files.withWriter(value) { value.copy(phase = FileSavePhase.FAILED).also(files::record) }
                    else value
                }
                ensureActive(); update(recovered)
                when (recovered.phase) {
                    FileSavePhase.COMPLETED, FileSavePhase.CANCELLED -> {
                        FileSaveWork.transfer(getApplication()).run(recovered.id)
                        if (recovered.phase == FileSavePhase.COMPLETED) lastSavedUri = recovered.destination?.let(Uri::parse)
                        update(null); releaseUi(recovered.id)
                    }
                    FileSavePhase.FAILED -> failure = "${recovered.filename} wasn't saved. Try another destination, or cancel to remove its private copy."
                    else -> Unit
                }
            } catch (error: Exception) {
                ensureActive(); update(null)
                snapshot?.let { cleanup(it, null) }
                failure = "The saved file copy is unavailable or incomplete. Reopen its preview and save it again."
            } finally {
                if (currentCoroutineContext().isActive) {
                    restoring = false
                    if (pending?.phase == FileSavePhase.WRITING) write(checkNotNull(pending), acquireGrant = false)
                    else if (hasDeferredResult) {
                        val uri = deferredResult; hasDeferredResult = false; deferredResult = null; result(uri)
                    }
                } else acquiredId?.let(::releaseUi)
            }
        }
    }
    private fun update(value: FileSaveSnapshot?) { pending = value; saved[STATE] = value?.encode() }
    private suspend fun record(value: FileSaveSnapshot) = withContext(Dispatchers.IO) { files.record(value) }
    fun begin(artifact: LocalFilePreview?, remote: RemoteArtifactSource? = null) {
        if (busy || artifact == null && remote == null) return
        val request = try {
            val type = fileActionType(remote?.let { changesPreviewName(it.path) } ?: checkNotNull(artifact).file.name, artifact?.mime)
            FileSaveSnapshot(UUID.randomUUID().toString(), type.filename, type.mime, FileSavePhase.PREPARING)
        } catch (_: Exception) { failure = "This file's name or type isn't supported. Reopen its preview and try again."; return }
        failure = null; lastSavedUri = null; progress = null; update(request)
        job = viewModelScope.launch {
            var preparing = request
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    uiLeases[request.id] = checkNotNull(files.claimUi(request.id))
                }
                ensureActive()
                if (remote == null) {
                    val local = checkNotNull(artifact)
                    files.prepare(preparing, local.file, local.size)
                } else {
                    val metadata = remote.metadata()
                    val type = fileActionType(changesPreviewName(remote.path), metadata.mime)
                    preparing = request.copy(filename = type.filename, mime = type.mime)
                    ensureActive(); update(preparing)
                    var reported = -1L
                    remote.prepareSave(files, preparing, metadata) { received, total ->
                        if (reported < 0 || received == total || received - reported >= 1024 * 1024) {
                            reported = received
                            withContext(Dispatchers.Main.immediate) { if (pending == preparing) progress = received to total }
                        }
                    }
                }
                val ready = preparing.copy(phase = FileSavePhase.READY)
                record(ready); ensureActive(); if (pending == preparing) { progress = null; update(ready) }
            } catch (error: Exception) {
                ensureActive(); if (pending == preparing) {
                    update(null); progress = null; cleanup(preparing, null)
                    failure = if (remote == null) "Couldn't prepare the file for saving. Reopen its preview and try again."
                        else ArtifactPreviewFailure.from(error, remote.authorization)
                            .presentation(remote.authorization, false, NativeFeedAvailability.CONNECTED).let { "${it.title}. ${it.message}" }
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
                    // Persist before dispatch. A worker needs only the private identity, never a URI in its Data.
                    writing = withContext(NonCancellable + Dispatchers.IO) { files.withGrantLock {
                        val owned = runCatching {
                            if (resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) false
                            else { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION); true }
                        }.getOrDefault(false)
                        writing.copy(ownsGrant = owned).also { durable ->
                            try { files.record(durable) }
                            catch (error: Exception) {
                                if (owned) runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                                throw error
                            }
                            FileSaveWork.dispatch(getApplication(), durable)
                        }
                    } }
                    ensureActive(); update(writing)
                }
                if (!acquireGrant) FileSaveWork.dispatch(getApplication(), writing)
                while (pending?.id == writing.id) {
                    val latest = withContext(Dispatchers.IO) { files.latest(writing) }
                    ensureActive()
                    when (latest.phase) {
                        FileSavePhase.COMPLETED -> { lastSavedUri = uri; update(null); progress = null; releaseUi(writing.id); return@launch }
                        FileSavePhase.CANCELLED -> { update(null); progress = null; releaseUi(writing.id); return@launch }
                        FileSavePhase.FAILED -> {
                            update(latest); progress = null
                            failure = "${latest.filename} wasn't saved. Choose another location, or cancel to remove its private copy."
                            return@launch
                        }
                        else -> progress = withContext(Dispatchers.IO) { files.progress(latest) }
                    }
                    delay(250)
                }
            } catch (error: Exception) {
                ensureActive()
                if (pending?.id == writing.id) {
                    // Never race a worker by overwriting its terminal record from this observer.
                    progress = null
                    failure = "Couldn't check the save. Reopen the app to recover its status."
                }
            }
        }
    }
    fun retry() {
        val request = pending ?: return
        if (!canRetry || job?.isActive == true) return
        job = viewModelScope.launch {
            try {
                val ready = files.withWriter(request) {
                    check(files.latest(request).phase == FileSavePhase.FAILED)
                    check(FileSaveWork.releaseGrant(getApplication(), request)) { "Could not release save permission" }
                    request.copy(phase = FileSavePhase.READY, destination = null, ownsGrant = false).also(files::record)
                }
                ensureActive()
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
    private fun cleanup(request: FileSaveSnapshot, previousJob: Job?) {
        // Wait for a cancelled writer before removing its bytes or releasing its provider grant.
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            runCatching { files.requestCancellation(request) }
            previousJob?.join()
            try {
                if (files.file(request).parentFile?.exists() == true) {
                    val final = FileSaveWork.transfer(getApplication()).cancel(request)
                    if (final.ownsGrant) FileSaveWork.dispatch(getApplication(), final)
                }
            } catch (_: Exception) { /* Keep the durable record for recovery. */ }
            finally { releaseUi(request.id) }
        }
    }
    override fun onCleared() {
        // The persistent writer outlives closing the preview/Activity. Other phases have no destination job.
        if (pending?.phase in setOf(FileSavePhase.WRITING, FileSavePhase.FAILED)) {
            val request = pending; val previousJob = job
            previousJob?.cancel()
            viewModelScope.launch(NonCancellable + Dispatchers.IO) {
                previousJob?.join(); request?.let { releaseUi(it.id) }
            }
        }
        else discard()
    }
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
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val pending = model.pending
    LaunchedEffect(model, lifecycle, pending, model.failure) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.recoverPending()
            awaitCancellation()
        }
    }
    LaunchedEffect(pending, model.restoring) {
        if (pending?.phase == FileSavePhase.READY && !model.restoring) {
            try { if (model.launching(pending)) launcher.launch(pending) }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); model.launchFailed(error) }
        }
    }
    CompositionLocalProvider(LocalFileSaves provides model) { FileExportHost(content) }
    if (pending?.phase in setOf(FileSavePhase.PREPARING, FileSavePhase.WRITING) && model.failure == null) AlertDialog(
        onDismissRequest = {}, title = { Text(if (pending?.phase == FileSavePhase.PREPARING) "Preparing file…" else "Saving file…") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(pending?.filename.orEmpty())
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
