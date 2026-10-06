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
    private val feed = NativeWhatsNewFeed()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val replay = if (BuildConfig.DEBUG) NativeWhatsNewReplay() else null
    val webArchive = NativeNoticeArchiveOwner(context.applicationContext, scope)
    private var webOwner: String? = null
    private var isWebOwnerCurrent: (String) -> Boolean = { false }
    private var webCookies: suspend (String) -> List<okhttp3.Cookie> = { emptyList() }
    fun configureWeb(owner: String?, isCurrent: (String) -> Boolean,
        cookies: suspend (String) -> List<okhttp3.Cookie>) {
        webOwner = owner; isWebOwnerCurrent = isCurrent; webCookies = cookies
    }
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
                    languages = listOf(Locale.getDefault().toLanguageTag()), feedIdentity = feed.cacheIdentity)
            }
            presentation = NativeWhatsNewPresentation(loaded, scope) { page, login, dark ->
                NativeNoticeRenderer(application, scope, loaded.webPolicy, (page.body as WhatsNewBody.Web).url,
                    dark, NativeWhatsNewWebLoad.LAUNCH_DEADLINE_MS,
                    currentOwner = { webOwner == login && isWebOwnerCurrent(login) }, cookies = webCookies)
            }
            mutable.value = loaded
            // Fetch only Android-owned metadata. Failure retains cached/compiled native pages.
            withContext(Dispatchers.IO) { loaded.refresh(feed::fetch) }
        }
    }
    override fun onCleared() { feed.close(); replay?.dismiss(); presentation?.close(); webArchive.close(); scope.cancel(); mutable.value?.close() }
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == NativeWhatsNewViewModel::class.java)
            @Suppress("UNCHECKED_CAST") return NativeWhatsNewViewModel(context) as T
        }
    }
}
