package io.github.docmorphic.cmuxapp

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.concurrent.thread

/** Debug-only bounded pipe destination in a different process from the real Save worker. */
class FileSaveWorkerTestProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        return true
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        check(mode == "wt")
        val id = checkNotNull(uri.lastPathSegment)
        check(UUID.fromString(id).toString() == id && uri.pathSegments.size == 1)
        val root = File(checkNotNull(context).filesDir, "file-save-worker-$id")
        check(File(root, "config.json").isFile)
        val caller = Binder.getCallingPid()
        val attempt = synchronized(this) {
            val counter = File(root, "attempts")
            val next = (counter.takeIf { it.isFile }?.readText()?.toInt() ?: 0) + 1
            check(next <= 8) { "Unexpected repeated fixture write" }
            counter.writeText(next.toString()); next
        }
        val pipe = ParcelFileDescriptor.createPipe()
        thread(name = "save-fixture-$attempt", isDaemon = true) {
            try {
                val output = File(root, "output-$attempt")
                ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                    FileOutputStream(output).use { sink ->
                        var received = 0L
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            sink.write(buffer, 0, count); received += count
                            if (received >= 1024 * 1024 && !File(root, "release").exists()) {
                                sink.fd.sync()
                                File(root, "blocked-$attempt.json").writeText(JSONObject()
                                    .put("caller_pid", caller).put("bytes", received).put("attempt", attempt).toString())
                                val end = System.nanoTime() + 120_000_000_000L
                                while (!File(root, "release").exists() && System.nanoTime() < end) Thread.sleep(25)
                                check(File(root, "release").exists()) { "Fixture gate expired" }
                            }
                        }
                        sink.fd.sync()
                        File(root, "closed-$attempt").writeText(received.toString())
                    }
                }
            } catch (failure: Exception) {
                if (root.isDirectory) File(root, "error-$attempt").writeText(failure.javaClass.simpleName)
            }
        }
        return pipe[1]
    }
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read/write fixture only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
