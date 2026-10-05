package io.github.docmorphic.cmuxapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import java.io.File

/** Nonexported lifecycle host for local synthetic artifacts; never touches credentials. */
class ArtifactPreviewTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(checkNotNull(intent.getStringExtra("path")))
        check(file.canonicalFile.toPath().startsWith(cacheDir.canonicalFile.toPath()))
        val route = ChangesPreviewRoute.valueOf(checkNotNull(intent.getStringExtra("route")))
        val preview = LocalFilePreview(file, file.length(), intent.getStringExtra("mime"), route)
        setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { FilePreviewContent(preview) }
        } } }
    }
}
