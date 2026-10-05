package io.github.docmorphic.cmuxapp

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider

internal class RetainedBrowserTestModel(application: Application) : AndroidViewModel(application) {
    val web = LocalBrowserWebOwner(application)
    var surface: LocalBrowserSurface? = null
    var closed = false
    override fun onCleared() { web.close(); surface?.close(); closed = true }
}

/** Emulator-only lifecycle host. No credentials or privileged JavaScript interface. */
class RetainedBrowserTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[RetainedBrowserTestModel::class.java]
        if (model.surface == null) model.surface = LocalBrowserSurface("retained-browser-fixture", intent.getStringExtra("url"))
        val surface = checkNotNull(model.surface)
        setContent { CmuxTheme { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            val page by surface.state.collectAsState()
            Text(page.title ?: "Loading fixture")
            Box(Modifier.weight(1f)) { LocalBrowserPane(surface, retainedWeb = model.web) { finish() } }
        } } }
    }
}
