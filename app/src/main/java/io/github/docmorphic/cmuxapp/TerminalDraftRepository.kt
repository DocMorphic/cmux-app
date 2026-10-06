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
    private val storageLock = Any()
    private val files = AttachmentFiles(context.applicationContext)
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

    suspend fun attach(target: TerminalDrafts.Target, prepared: AttachmentFiles.Prepared, generation: Long) =
        withContext(Dispatchers.IO) {
            synchronized(storageLock) {
                files.write(prepared)
                try {
                    drafts.attach(target, prepared.attachment, generation)
                    save()
                } catch (failure: Exception) {
                    drafts.removeAttachment(target, prepared.attachment.id)
                    files.delete(prepared.attachment.id)
                    throw failure
                }
            }
        }

    suspend fun read(attachment: ComposerAttachment): ByteArray = withContext(Dispatchers.IO) {
        synchronized(storageLock) { files.read(attachment) }
    }

    suspend fun read(target: TerminalDrafts.Target, attachment: ComposerAttachment, generation: Long): ByteArray = withContext(Dispatchers.IO) {
        synchronized(storageLock) {
            check(drafts.ownsAttachment(target, attachment, generation)) { "Attachment was removed or its terminal changed." }
            files.read(attachment).also {
                check(drafts.ownsAttachment(target, attachment, generation)) { "Attachment was removed or its terminal changed." }
            }
        }
    }

    private fun save() = synchronized(storageLock) {
        try {
            val saved = drafts.saved()
            store.update { state -> state.put("drafts", saved) }
            // Delete only payloads absent from the metadata we successfully persisted.
            val retained = buildSet<String> {
                for (index in 0 until saved.length()) {
                    val attachments = saved.getJSONObject(index).getJSONArray("attachments")
                    for (item in 0 until attachments.length()) add(attachments.getJSONObject(item).getString("id"))
                }
            }
            files.retain(retained)
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
