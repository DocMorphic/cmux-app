package io.github.docmorphic.cmuxapp

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps draft writes ordered across activity recreation and screen changes. */
class TerminalDraftRepository private constructor(context: Context) {
    private val store = NativeCredentialStore(context.applicationContext, "native_terminal_drafts")
    val drafts = TerminalDrafts(store.load()?.optJSONArray("drafts"))
    private val mutableError = MutableStateFlow<String?>(null)
    val saveError = mutableError.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            drafts.state.collect {
                runCatching { save() }
            }
        }
    }

    /** Persist a pending send before transmission so process death cannot hide it. */
    suspend fun persistNow() = withContext(Dispatchers.IO) { save() }

    private fun save() {
        try {
            store.update { state -> state.put("drafts", drafts.saved()) }
            mutableError.value = null
        } catch (failure: Exception) {
            mutableError.value = "Could not save terminal drafts"
            throw failure
        }
    }

    companion object {
        @Volatile private var instance: TerminalDraftRepository? = null
        fun get(context: Context): TerminalDraftRepository = instance ?: synchronized(this) {
            instance ?: TerminalDraftRepository(context).also { instance = it }
        }
    }
}
