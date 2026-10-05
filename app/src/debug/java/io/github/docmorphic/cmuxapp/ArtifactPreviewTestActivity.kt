package io.github.docmorphic.cmuxapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.Modifier
import java.io.File

/** Nonexported lifecycle host for local synthetic artifacts; never touches credentials. */
class ArtifactPreviewTestActivity : ComponentActivity() {
    private lateinit var fixture: ArtifactPreviewTestModel
    internal var loading: Boolean
        get() = fixture.loading
        set(value) { fixture.loading = value }
    internal var streaming: ArtifactStreamingText?
        get() = fixture.streaming
        set(value) { fixture.streaming = value }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("export_id")?.let { id ->
            ViewModelProvider(this, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    FileExportModel(application, SavedStateHandle(mapOf("file-export.pending.v1" to id))) as T
            })[FileExportModel::class.java]
        }
        fixture = ViewModelProvider(this)[ArtifactPreviewTestModel::class.java]
        if (!fixture.initialized) { loading = savedInstanceState?.getBoolean("loading") ?: false; fixture.initialized = true }
        val file = File(checkNotNull(intent.getStringExtra("path")))
        check(file.canonicalFile.toPath().startsWith(cacheDir.canonicalFile.toPath()))
        val route = ChangesPreviewRoute.valueOf(checkNotNull(intent.getStringExtra("route")))
        val preview = LocalFilePreview(file, file.length(), intent.getStringExtra("mime"), route)
        if (streaming == null && savedInstanceState == null) intent.getStringExtra("initial_text")?.let {
            streaming = ArtifactStreamingText(preview, ArtifactTextDocument(it))
        }
        val remote = intent.getStringExtra("remote_path")?.let { path ->
            RemoteArtifactSource(ArtifactRpc(ArtifactCapabilities(false, false, false, false, false)) { _, _ ->
                error("This fixture has no remote transport")
            }, ArtifactAuthorization.Terminal("fixture", "fixture"), path)
        }
        setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (loading) Column { FilePreviewActions(null); Text("Loading replacement preview") }
                else FilePreviewContent(preview, remote, streaming, streaming?.document?.text?.toByteArray()?.size?.toLong() ?: preview.size)
            }
        } } }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("loading", loading); super.onSaveInstanceState(outState) }
}

/** Route changes can arrive while the picker has stopped the Activity and saved its old Bundle. */
internal class ArtifactPreviewTestModel : ViewModel() {
    var initialized = false
    var loading by mutableStateOf(false)
    var streaming by mutableStateOf<ArtifactStreamingText?>(null)
}
