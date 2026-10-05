package io.github.docmorphic.cmuxapp

import java.io.OutputStream
import kotlinx.coroutines.*

/** Persistent-state transfer, shared by workers and cleanup. No Activity, remote RPC or account credentials. */
internal class FileSaveTransfer(
    private val files: FileSaveFiles,
    private val open: (FileSaveSnapshot) -> OutputStream,
    private val releaseGrant: suspend (FileSaveSnapshot) -> Boolean
) {
    private class CancelledByUser : Exception()

    suspend fun run(id: String, progress: suspend (Long, Long) -> Unit = { _, _ -> }): FileSaveSnapshot? {
        val initial = withContext(Dispatchers.IO) { files.load(id) } ?: return null
        return files.withWriter(initial) {
            val current = files.latest(initial)
            if (current.phase in FileSaveFiles.terminal) return@withWriter finish(current, current.phase)
            if (files.isCancellationRequested(current)) return@withWriter finish(current, FileSavePhase.CANCELLED)
            if (current.phase != FileSavePhase.WRITING) return@withWriter current
            files.withDestination(current) { try {
                files.write(current, progress = { received, total ->
                    if (files.isCancellationRequested(current)) throw CancelledByUser()
                    progress(received, total)
                }) {
                    // A queued cancellation must never truncate the selected document.
                    if (files.isCancellationRequested(current)) throw CancelledByUser()
                    open(current)
                }
                currentCoroutineContext().ensureActive()
                finish(current, if (files.isCancellationRequested(current)) FileSavePhase.CANCELLED else FileSavePhase.COMPLETED)
            } catch (_: CancelledByUser) {
                finish(current, FileSavePhase.CANCELLED)
            } catch (error: Exception) {
                // Scheduler/process interruption leaves WRITING intact for a fresh worker.
                currentCoroutineContext().ensureActive()
                val latest = files.latest(current)
                if (latest.phase in FileSaveFiles.terminal) latest
                else current.copy(phase = FileSavePhase.FAILED).also(files::record)
            } }
        }
    }

    suspend fun cancel(value: FileSaveSnapshot) = withContext(Dispatchers.IO) {
        files.requestCancellation(value)
        files.withWriter(value) {
            val latest = files.latest(value)
            finish(latest, latest.phase.takeIf { it in FileSaveFiles.terminal } ?: FileSavePhase.CANCELLED)
        }
    }

    private suspend fun finish(value: FileSaveSnapshot, phase: FileSavePhase): FileSaveSnapshot {
        files.finish(value, phase)
        val finished = value.copy(phase = phase)
        return if (!finished.ownsGrant || releaseGrant(finished))
            finished.copy(ownsGrant = false).also(files::record)
        else finished // A later recovery pass retries releasing only this export's grant.
    }
}
