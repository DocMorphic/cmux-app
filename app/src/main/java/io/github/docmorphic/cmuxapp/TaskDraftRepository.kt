package io.github.docmorphic.cmuxapp

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Ordered, encrypted writes outlive a composer; account tokens fence every transaction. */
internal class TaskDraftRepository private constructor(
    private val store: NativeCredentialStore, val session: String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableError = MutableStateFlow<String?>(null)
    val saveError = mutableError.asStateFlow()
    private val saved = checkNotNull(store.load()) { "Sign in to load task drafts" }
        .also { requireSession(it) }.optJSONObject("task_drafts")
    val drafts = TaskDrafts(saved)
    val templates = TaskTemplates(saved?.optJSONObject("templates"))

    init {
        scope.launch {
            combine(drafts.state, templates.state) { a, b -> a to b }.collectLatest {
                delay(300)
                try { persistNow() } catch (failure: CancellationException) { throw failure } catch (_: Exception) { }
            }
        }
    }

    suspend fun persistNow(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val saved = drafts.saved().put("templates", templates.state.value.json())
                store.update { state -> requireSession(state); state.put("task_drafts", saved) }
                mutableError.value = null
            } catch (failure: Exception) {
                if (failure !is CancellationException) mutableError.value = "Could not save task drafts"
                throw failure
            }
        }
    }

    /** Save before publishing a template edit; failures keep the editor and old selection. */
    suspend fun updateTemplates(change: TaskTemplateChange): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val proposed = templates.preview(change)
            store.update { state -> requireSession(state)
                state.put("task_drafts", drafts.saved().put("templates", proposed.json())) }
            templates.apply(change)
        }
    }

    /** Lifecycle flush does not depend on a disappearing composition's coroutine scope. */
    fun flush() { scope.launch { runCatching { persistNow() } } }
    private fun requireSession(state: org.json.JSONObject) {
        check(state.optString("refresh_token").isNotBlank() && state.optString("task_session") == session) {
            "Account changed while saving task drafts"
        }
    }
    private fun close() { scope.cancel(); drafts.clear(); templates.close() }

    companion object {
        @Volatile private var instance: TaskDraftRepository? = null
        /** Call on IO: decrypts the existing collection before permitting edits. */
        fun get(context: Context, session: String): TaskDraftRepository = synchronized(this) {
            instance?.takeIf { it.session == session } ?: run {
                instance?.close()
                TaskDraftRepository(NativeCredentialStore(context.applicationContext), session).also { instance = it }
            }
        }
        fun clearMemory() = synchronized(this) { instance?.close(); instance = null }
    }
}
