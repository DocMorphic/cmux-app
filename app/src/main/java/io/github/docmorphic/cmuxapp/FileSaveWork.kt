package io.github.docmorphic.cmuxapp

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

internal object FileSaveWork {
    const val KEY = "save_id"
    const val CANCEL = "cancel"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun files(context: Context) = FileSaveFiles(File(context.noBackupFilesDir, "file-saves"))
    fun transfer(context: Context) = FileSaveTransfer(files(context), open = { request ->
        checkNotNull(context.contentResolver.openOutputStream(Uri.parse(checkNotNull(request.destination)), "wt")) {
            "Could not open the save destination."
        }
    }, releaseGrant = { request -> releaseGrant(context, request) })
    suspend fun releaseGrant(context: Context, request: FileSaveSnapshot): Boolean {
        if (!request.ownsGrant) return true
        val uri = Uri.parse(checkNotNull(request.destination))
        val files = files(context)
        return files.withGrantLock {
            // A second export may be borrowing the permission this one acquired.
            if (files.entries().any { it.id != request.id && it.destination == request.destination && it.phase == FileSavePhase.WRITING })
                return@withGrantLock false
            runCatching {
                val resolver = context.contentResolver
                if (resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission })
                    resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                true
            }.getOrDefault(false)
        }
    }
    /** The browser process must not create a second WorkManager scheduler/database owner. */
    fun dispatch(context: Context, request: FileSaveSnapshot) {
        context.sendBroadcast(Intent(context, FileSaveWorkReceiver::class.java).putExtra(KEY, request.id))
    }
    suspend fun enqueue(context: Context, id: String, recovery: Boolean) {
        require(UUID.fromString(id).toString() == id)
        val work = OneTimeWorkRequestBuilder<FileSaveWorker>().setInputData(workDataOf(KEY to id)).build()
        // Explicit retries queue after an older finishing worker; KEEP could lose that retry.
        WorkManager.getInstance(context).enqueueUniqueWork("cmux.file-save.$id",
            if (recovery) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE, work).await()
    }
    fun recover(context: Context) {
        scope.launch {
            files(context).recoverable().forEach { value ->
                runCatching { enqueue(context, value.id, recovery = true) }
            }
        }
    }
}

/** Non-exported and always hosted in the default process, including requests from :browser. */
class FileSaveWorkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(FileSaveWork.KEY) ?: return
        if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not()) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val files = FileSaveWork.files(context)
                val request = files.load(id) ?: return@launch
                if (intent.getBooleanExtra(FileSaveWork.CANCEL, false)) files.requestCancellation(request)
                FileSaveWork.enqueue(context.applicationContext, id, recovery = intent.getBooleanExtra(FileSaveWork.CANCEL, false))
            } catch (_: Exception) {
                // The durable WRITING/cancellation record will be recovered on the next app start.
            } finally { result.finish() }
        }
    }
}

class FileSaveWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(FileSaveWork.KEY) ?: return@withContext Result.failure()
        val files = FileSaveWork.files(applicationContext)
        val request = runCatching { files.load(id) }.getOrNull() ?: return@withContext Result.failure()
        try {
            if (request.phase == FileSavePhase.WRITING && !files.isCancellationRequested(request))
                setForeground(foreground(id, 0, 0))
            var reported = -1L
            var notifiedAt = 0L
            var progressAt = 0L
            val final = FileSaveWork.transfer(applicationContext).run(id) { received, total ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (reported < 0 || received == total || received - reported >= 1024 * 1024 && now - progressAt >= 250) {
                    reported = received
                    progressAt = now
                    files.reportProgress(request, received, total)
                    setProgress(workDataOf("received" to received, "total" to total))
                    if (now - notifiedAt >= 1000 || received == total) {
                        notifiedAt = now; setForeground(foreground(id, received, total))
                    }
                }
            }
            // Provider failure is a durable user-visible state, not a scheduler failure:
            // failing this chain could cancel a retry queued as this worker finishes.
            if (final?.phase in FileSaveFiles.terminal && final?.ownsGrant == true) Result.retry() else Result.success()
        } catch (error: Exception) {
            ensureActive()
            // Foreground admission/scheduler failures do not discard the durable user export.
            Result.retry()
        }
    }
    private fun foreground(id: String, received: Long, total: Long): ForegroundInfo {
        val context = applicationContext
        val channel = "cmux_file_saves"
        NativeOngoingNotifications.register(context.getSystemService(NotificationManager::class.java), channel, "Saving files")
        val notificationId = 0x40000000 or (id.hashCode() and 0x3fffffff)
        val cancel = PendingIntent.getBroadcast(context, notificationId,
            Intent(context, FileSaveWorkReceiver::class.java).setData(Uri.parse("cmux-save:$id"))
                .putExtra(FileSaveWork.KEY, id).putExtra(FileSaveWork.CANCEL, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Saving file").setContentText("Your file is being saved to the selected location.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setProgress(100, if (total > 0) ((received.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0, total <= 0)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build()).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId, notification)
    }
}
