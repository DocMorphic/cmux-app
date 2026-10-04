package io.github.docmorphic.cmuxapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope

internal data class RoutedChangesCapture(val access: WorkspaceChangesAccess, val retain: () -> RoutedSidebarLease,
    val current: () -> Boolean)

internal class RoutedChangesModel : ViewModel() {
    var capture: RoutedChangesCapture? = null
    var lease: RoutedSidebarLease? = null
    var presentation: WorkspaceChangesPresentation? = null
        private set
    fun bind(value: RoutedChangesCapture) {
        check(capture == null)
        capture = value; lease = value.retain()
        presentation = WorkspaceChangesPresentation(viewModelScope, value.access)
    }
    override fun onCleared() { presentation?.close(); presentation = null; lease?.close(); lease = null; capture = null }
}

/** Main-process sheet above the browser Activity, which retains its live WebView and unsent page state. */
class RoutedChangesActivity : ComponentActivity() {
    private lateinit var model: RoutedChangesModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[RoutedChangesModel::class.java]
        if (model.capture == null) {
            val captured = RoutedBrowserSessions.takeChanges(intent.getStringExtra("changes"))
            if (captured == null) { finish(); return }
            model.bind(captured)
        }
        val captured = checkNotNull(model.capture)
        setContent { CmuxTheme {
            androidx.compose.runtime.LaunchedEffect(captured) {
                while (captured.current()) kotlinx.coroutines.delay(250)
                finish()
            }
            if (captured.current()) WorkspaceChangesSheet(checkNotNull(model.presentation), { finish() })
        } }
    }
    override fun onStart() { super.onStart(); if (::model.isInitialized) model.lease?.active(true) }
    override fun onStop() { if (::model.isInitialized) model.lease?.active(false); super.onStop() }
}
