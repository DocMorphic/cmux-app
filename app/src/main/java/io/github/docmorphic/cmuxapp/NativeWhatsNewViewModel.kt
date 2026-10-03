package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

internal class NativeWhatsNewViewModel(context: Context) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow<NativeWhatsNewCenter?>(null)
    val center = mutable.asStateFlow()
    var presentation: NativeWhatsNewPresentation? = null
        private set
    init {
        val application = context.applicationContext
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                NativeWhatsNewCenter(NativeWhatsNewCatalog.pages, BuildConfig.NOTICE_VERSION,
                    WhatsNewChannel.entries.single { it.token == BuildConfig.NOTICE_CHANNEL },
                    NativeWhatsNewFileStore(File(application.noBackupFilesDir, "whats-new")),
                    languages = listOf(Locale.getDefault().toLanguageTag()))
            }
            presentation = NativeWhatsNewPresentation(loaded)
            mutable.value = loaded
            // No Android notice feed is configured. Never query the iOS visibility endpoint.
            loaded.refresh()
        }
    }
    override fun onCleared() { scope.cancel(); mutable.value?.close() }
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == NativeWhatsNewViewModel::class.java)
            @Suppress("UNCHECKED_CAST") return NativeWhatsNewViewModel(context) as T
        }
    }
}
