package io.github.docmorphic.cmuxapp

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.*

internal class NativeFeedbackViewModel(handle: SavedStateHandle) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val controller = NativeFeedbackController(scope, handle["feedback"]) { handle["feedback"] = it }
    override fun onCleared() { scope.cancel() }
}
