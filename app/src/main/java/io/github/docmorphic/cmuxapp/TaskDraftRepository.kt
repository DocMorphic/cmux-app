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
    private val store: NativeCredentialStore, val session: String, private val files: AttachmentFiles
) {
    @Volatile private var closed = false
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
                retainFiles(saved)
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

    suspend fun selectMac(editor: TaskDrafts.Editor, origin: String, name: String, openDirectory: String?,
        adoptingIdentity: Boolean = false): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Keep the editor lease stable across the durable owner change. Old model/IME
            // callbacks become invalid before the caller starts connecting to the new Mac.
            synchronized(drafts) {
                check(drafts.isCurrent(editor)) { "Task session changed" }
                synchronized(templates) {
                    val current = checkNotNull(drafts.state.value[editor.id])
                    val choices = if (adoptingIdentity) templates.state.value else templates.state.value.rememberingPickers(current)
                    val moved = current.onMac(origin, name, choices.suggestedDirectory(choices.selected(current.templateId), origin, openDirectory))
                    val changed = when {
                        // The first verified handshake identifies the same Mac; keep in-progress input.
                        adoptingIdentity -> moved.copy(directory = current.directory, didEditDirectory = current.didEditDirectory,
                            selection = current.selection, defaultModel = current.defaultModel, groupId = current.groupId)
                        origin == current.origin -> moved
                        else -> choices.restorePickers(moved, openDirectory, current.templateId)
                    }
                    store.update { state -> requireSession(state)
                        state.put("task_drafts", drafts.saved(changed).put("templates", choices.rememberingPickers(changed).json())) }
                    if (!adoptingIdentity) templates.rememberPickers(current)
                    templates.rememberPickers(changed)
                    drafts.retarget(editor, changed)
                }
            }
        }
    }

    /** Ciphertext and metadata reach disk before a chip appears in the composer. */
    suspend fun attach(editor: TaskDrafts.Editor, prepared: AttachmentFiles.Prepared): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed && drafts.isCurrent(editor)) { "Task session changed" }
            requireSession(checkNotNull(store.load()))
            check(drafts.state.value.values.none { draft -> draft.attachments.any { it.id == prepared.attachment.id } })
            require(prepared.bytes.size == prepared.attachment.size)
            synchronized(drafts) {
                val current = checkNotNull(drafts.state.value[editor.id])
                TaskAttachments.validate(current.attachments + prepared.attachment)
            }
            files.write(prepared)
            try {
                synchronized(drafts) {
                    check(!closed && drafts.isCurrent(editor)) { "Task session changed" }
                    val current = checkNotNull(drafts.state.value[editor.id])
                    val attachments = (current.attachments + prepared.attachment).also(TaskAttachments::validate)
                    val changed = current.copy(attachments = attachments)
                    val saved = drafts.saved(changed).put("templates", templates.state.value.json())
                    store.update { state -> requireSession(state); state.put("task_drafts", saved) }
                    drafts.edit(editor) { it.copy(attachments = attachments) }
                }
            } catch (failure: Exception) { files.delete(prepared.attachment.id); throw failure }
        }
    }

    suspend fun removeAttachment(editor: TaskDrafts.Editor, id: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            synchronized(drafts) {
                check(!closed && drafts.isCurrent(editor)) { "Task session changed" }
                val current = checkNotNull(drafts.state.value[editor.id])
                val changed = current.copy(attachments = current.attachments.filterNot { it.id == id })
                val saved = drafts.saved(changed).put("templates", templates.state.value.json())
                store.update { state -> requireSession(state); state.put("task_drafts", saved) }
                drafts.edit(editor) { it.copy(attachments = changed.attachments) }
                retainFiles(saved)
            }
        }
    }

    suspend fun readAttachment(attachment: ComposerAttachment): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireSession(checkNotNull(store.load()))
            check(drafts.state.value.values.any { attachment in it.attachments }) { "Attachment was removed" }
            files.read(attachment).also { requireSession(checkNotNull(store.load())) }
        }
    }
    fun ownsAttachment(draftId: String, origin: String, attachment: ComposerAttachment): Boolean =
        !closed && drafts.state.value[draftId]?.let { it.origin == origin && attachment in it.attachments } == true

    /** Preview permission belongs to one task, including before and after decryption. */
    suspend fun readAttachment(draftId: String, origin: String, attachment: ComposerAttachment): ByteArray {
        check(ownsAttachment(draftId, origin, attachment)) { "Attachment was removed or its task changed." }
        return readAttachment(attachment).also {
            check(ownsAttachment(draftId, origin, attachment)) { "Attachment was removed or its task changed." }
        }
    }
    private fun retainFiles(saved: org.json.JSONObject) {
        val rows = saved.getJSONArray("drafts")
        files.retain((0 until rows.length()).flatMap { TaskAttachments.read(rows.getJSONObject(it).optJSONArray("attachments")) }
            .mapTo(hashSetOf()) { it.id })
    }

    /** Lifecycle flush does not depend on a disappearing composition's coroutine scope. */
    fun flush() { scope.launch { runCatching { persistNow() } } }
    private fun requireSession(state: org.json.JSONObject) {
        check(!closed && state.optString("refresh_token").isNotBlank() && state.optString("task_session") == session) {
            "Account changed while saving task drafts"
        }
    }
    private fun close() { closed = true; scope.cancel(); drafts.clear(); templates.close() }

    companion object {
        @Volatile private var instance: TaskDraftRepository? = null
        /** Call on IO: decrypts the existing collection before permitting edits. */
        fun get(context: Context, session: String): TaskDraftRepository = synchronized(this) {
            instance?.takeIf { it.session == session } ?: run {
                instance?.close()
                TaskDraftRepository(NativeCredentialStore(context.applicationContext), session, AttachmentFiles(context.applicationContext, taskFiles = true)).also { instance = it }
            }
        }
        fun clearAttachments(context: Context) { AttachmentFiles(context.applicationContext, taskFiles = true).retain(emptySet()); java.io.File(context.cacheDir, "task-previews").deleteRecursively() }
        fun clearMemory() = synchronized(this) { instance?.close(); instance = null }
    }
}
