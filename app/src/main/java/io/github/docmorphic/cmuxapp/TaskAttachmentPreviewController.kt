package io.github.docmorphic.cmuxapp

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TaskAttachmentPreviewIdentity(
    val presentation: String, val session: String, val draft: String, val origin: String,
    val attachment: ComposerAttachment
)
internal data class TaskAttachmentPreviewState(val identity: TaskAttachmentPreviewIdentity? = null,
    val artifact: LocalFilePreview? = null, val error: String? = null)

/** Retains the exact staged bytes across view recreation. Calls are UI-dispatcher confined. */
internal class TaskAttachmentPreviewController(private val scope: CoroutineScope) : AutoCloseable {
    private class Request(val identity: TaskAttachmentPreviewIdentity, val root: File, val mime: String?,
        var valid: () -> Boolean, var read: suspend () -> ByteArray)
    private var request: Request? = null
    private var job: Job? = null
    private var closed = false
    private val mutable = MutableStateFlow(TaskAttachmentPreviewState())
    val state = mutable.asStateFlow()

    fun open(identity: TaskAttachmentPreviewIdentity, root: File, mime: String?,
        valid: () -> Boolean, read: suspend () -> ByteArray) {
        if (closed || !scope.isActive) return
        request?.takeIf { it.identity == identity && it.root == root && it.mime == mime }?.let {
            it.valid = valid; it.read = read
            if (!valid()) clear(identity)
            return
        }
        start(Request(identity, root, mime, valid, read))
    }
    fun retry(identity: TaskAttachmentPreviewIdentity) {
        request?.takeIf { !closed && it.identity == identity }?.let {
            start(Request(it.identity, it.root, it.mime, it.valid, it.read))
        }
    }
    private fun start(next: Request) {
        job?.cancel(); request = next
        mutable.value = TaskAttachmentPreviewState(next.identity)
        job = scope.launch {
            val directory = File(next.root, UUID.randomUUID().toString())
            var lease: AutoCloseable? = null
            try {
                check(next.valid()) { "Attachment was removed or its task changed." }
                val bytes = next.read()
                ensureActive()
                check(next.valid()) { "Attachment was removed or its task changed." }
                check(bytes.size == next.identity.attachment.size) { "The saved attachment is incomplete. Attach it again." }
                val artifact = withContext(Dispatchers.IO) {
                    lease = ArtifactExportCache.hold(directory)
                    ArtifactExportCache.prune(next.root)
                    check(directory.mkdirs()) { "Could not prepare the attachment preview." }
                    val file = File(directory, changesPreviewName(next.identity.attachment.name))
                    file.writeBytes(bytes)
                    LocalFilePreview(file, bytes.size.toLong(), next.mime,
                        taskAttachmentPreviewRoute(next.identity.attachment, next.mime))
                }
                ensureActive()
                check(next.valid()) { "Attachment was removed or its task changed." }
                if (request === next) mutable.value = TaskAttachmentPreviewState(next.identity, artifact)
                awaitCancellation()
            } catch (failure: Exception) {
                ensureActive()
                withContext(Dispatchers.IO) { directory.deleteRecursively() }
                if (request === next) mutable.value = TaskAttachmentPreviewState(next.identity,
                    error = failure.message ?: "Could not preview attachment.")
                awaitCancellation()
            } finally { withContext(NonCancellable + Dispatchers.IO) {
                try { directory.deleteRecursively() } finally { lease?.close() }
            } }
        }
    }
    fun clear(identity: TaskAttachmentPreviewIdentity) {
        if (request?.identity != identity) return
        request = null; mutable.value = TaskAttachmentPreviewState(); job?.cancel(); job = null
    }
    override fun close() { closed = true; request?.let { clear(it.identity) } }
}

internal fun taskAttachmentPreviewRoute(attachment: ComposerAttachment, mime: String?): ChangesPreviewRoute {
    val extension = attachment.name.substringAfterLast('.', "").lowercase()
    val kind = when {
        attachment.imageFormat != null -> "image"
        mime?.startsWith("text/") == true || mime in setOf("application/json", "application/xml", "application/javascript") ||
            extension in setOf("txt", "log", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "conf", "csv", "tsv",
                "sh", "bash", "zsh", "py", "kt", "kts", "java", "swift", "js", "jsx", "ts", "tsx", "css", "html",
                "c", "h", "cpp", "hpp", "rs", "go", "rb", "sql", "diff", "patch") -> "text"
        else -> "file"
    }
    return filePreviewRoute(kind, mime, attachment.name)
}
