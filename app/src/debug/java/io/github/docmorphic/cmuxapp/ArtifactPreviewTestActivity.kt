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
import androidx.compose.ui.Modifier
import java.io.File

/** Nonexported lifecycle host for local synthetic artifacts; never touches credentials. */
class ArtifactPreviewTestActivity : ComponentActivity() {
    private lateinit var fixture: ArtifactPreviewTestModel
    internal var loading: Boolean
        get() = fixture.loading
        set(value) { fixture.loading = value }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fixture = ViewModelProvider(this)[ArtifactPreviewTestModel::class.java]
        if (!fixture.initialized) { loading = savedInstanceState?.getBoolean("loading") ?: false; fixture.initialized = true }
        val file = File(checkNotNull(intent.getStringExtra("path")))
        check(file.canonicalFile.toPath().startsWith(cacheDir.canonicalFile.toPath()))
        val route = ChangesPreviewRoute.valueOf(checkNotNull(intent.getStringExtra("route")))
        val preview = LocalFilePreview(file, file.length(), intent.getStringExtra("mime"), route)
        setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (loading) Column { FilePreviewActions(null); Text("Loading replacement preview") }
                else FilePreviewContent(preview)
            }
        } } }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("loading", loading); super.onSaveInstanceState(outState) }
}

/** Route changes can arrive while the picker has stopped the Activity and saved its old Bundle. */
internal class ArtifactPreviewTestModel : ViewModel() {
    var initialized = false
    var loading by mutableStateOf(false)
}
