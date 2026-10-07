package io.github.docmorphic.cmuxapp

import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Emulator-only host; Android owns the real saved state and picker-result restoration. */
class FileSaveProcessTestActivity : ComponentActivity() {
    private lateinit var directory: File
    private fun marker(name: String, value: String) {
        val target = File(directory, name)
        val partial = File(directory, "$name.partial")
        partial.writeText(value); check(partial.renameTo(target))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        super.onCreate(savedInstanceState)
        val id = checkNotNull(intent.getStringExtra("fixtureId"))
        check(UUID.fromString(id).toString() == id)
        directory = File(filesDir, "file-save-process-$id").apply { mkdirs() }
        val source = File(cacheDir, "file-save-process-$id.txt")
        val pid = Process.myPid()
        marker("created-$pid", (savedInstanceState != null).toString())
        setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            val model = checkNotNull(LocalFileSaves.current)
            LaunchedEffect(model.pending, model.restoring, model.failure, model.lastSavedUri) {
                marker("status", JSONObject().put("pid", pid).put("restored", savedInstanceState != null)
                    .put("pending", model.pending?.encode()?.let(::JSONObject) ?: JSONObject.NULL)
                    .put("restoring", model.restoring).put("failure", model.failure ?: JSONObject.NULL)
                    .put("uri", model.lastSavedUri?.toString() ?: JSONObject.NULL).toString())
            }
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Text("Save process $pid")
                if (source.isFile) FilePreviewContent(LocalFilePreview(source, source.length(), "text/plain", ChangesPreviewRoute.TEXT))
                else { FilePreviewActions(null); Text("Preview source removed") }
            }
        } } }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        marker("saved", Process.myPid().toString())
    }
    override fun onStop() {
        super.onStop()
        marker("stopped", Process.myPid().toString())
    }
}
