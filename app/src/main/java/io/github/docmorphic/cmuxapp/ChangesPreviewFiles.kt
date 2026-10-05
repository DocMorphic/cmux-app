package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal data class ChangesPreviewPolicy(val revisions: List<ChangesRevision>, val initial: ChangesRevision) {
    companion object {
        fun forFile(file: ChangedFile) = when (file.kind) {
            ChangeKind.MODIFIED, ChangeKind.RENAMED -> ChangesPreviewPolicy(ChangesRevision.entries, ChangesRevision.CURRENT)
            ChangeKind.DELETED -> ChangesPreviewPolicy(listOf(ChangesRevision.BASE), ChangesRevision.BASE)
            else -> ChangesPreviewPolicy(listOf(ChangesRevision.CURRENT), ChangesRevision.CURRENT)
        }
        fun path(file: ChangedFile, revision: ChangesRevision) = if (revision == ChangesRevision.BASE) file.oldPath ?: file.path else file.path
    }
}
internal enum class ChangesPreviewRoute { IMAGE, PDF, MEDIA, TEXT, EXTERNAL }
internal fun changesPreviewRoute(metadata: ChangesFileMetadata, path: String): ChangesPreviewRoute = filePreviewRoute(metadata.kind, metadata.mime, path)
internal fun filePreviewRoute(kind: String, mimeType: String?, path: String): ChangesPreviewRoute {
    val extension = path.substringAfterLast('.', "").lowercase()
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return when {
        kind == "image" || mime.startsWith("image/") -> ChangesPreviewRoute.IMAGE
        extension == "pdf" || mime == "application/pdf" -> ChangesPreviewRoute.PDF
        mime.startsWith("video/") || mime.startsWith("audio/") || extension in setOf("mp4", "mov", "m4v", "webm", "mkv", "mp3", "wav", "m4a", "aac", "ogg", "flac", "opus", "aiff") -> ChangesPreviewRoute.MEDIA
        kind == "text" || MarkdownPreviewPolicy.isMarkdown(path, mime) -> ChangesPreviewRoute.TEXT
        else -> ChangesPreviewRoute.EXTERNAL
    }
}
internal fun changesPreviewName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
    .filterNot { it.isISOControl() }.take(120).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "file"
internal data class ChangesPreviewArtifact(val path: String, val revision: ChangesRevision, val metadata: ChangesFileMetadata,
    val route: ChangesPreviewRoute, val file: File)
internal data class LocalFilePreview(val file: File, val size: Long, val mime: String?, val route: ChangesPreviewRoute)
internal fun ChangesPreviewArtifact.localPreview() = LocalFilePreview(file, metadata.size, metadata.mime, route)

/** One revision owns one directory; callers close it after cancellation or leaving the page. */
internal class ChangesPreviewFiles(root: File, private val transfer: ChangesContentTransfer) : AutoCloseable {
    private val directory = File(root, UUID.randomUUID().toString())
    suspend fun download(path: String, revision: ChangesRevision, metadata: ChangesFileMetadata,
        progress: suspend (Long, Long) -> Unit): ChangesPreviewArtifact = withContext(Dispatchers.IO) {
        val route = changesPreviewRoute(metadata, path)
        val limit = if (route == ChangesPreviewRoute.MEDIA) ChangesContentTransfer.MEDIA_BYTES else ChangesContentTransfer.PREVIEW_BYTES
        check(metadata.size <= limit) { "This file is too large to preview (${metadata.size} bytes; limit $limit bytes)." }
        check(directory.mkdirs()) { "Could not create the preview folder." }
        val partial = File(directory, "download.partial")
        val destination = File(directory, changesPreviewName(path).let { if (it == "download.partial") "file-download.partial" else it })
        try {
            partial.outputStream().use { output ->
                transfer.stream(path, revision, metadata, limit) { bytes, received, total ->
                    output.write(bytes); progress(received, total)
                }
                output.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            check(partial.renameTo(destination)) { "Could not finish the preview download." }
            ChangesPreviewArtifact(path, revision, metadata, route, destination)
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }
    override fun close() { directory.deleteRecursively() }
}

/** Export a snapshot independent of the preview lifetime; a picker/clipboard may outlive its page. */
internal suspend fun exportChangesPreview(artifact: ChangesPreviewArtifact, root: File, filename: String = artifact.file.name): File =
    exportFilePreview(artifact.localPreview(), root, filename)
internal suspend fun exportFilePreview(artifact: LocalFilePreview, root: File, filename: String = artifact.file.name): File {
    val directory = File(root, UUID.randomUUID().toString())
    val lease = ArtifactExportCache.hold(directory)
    try {
        return withContext(Dispatchers.IO) {
            root.mkdirs(); ArtifactExportCache.prune(root)
            check(directory.mkdirs()) { "Could not prepare the file." }
            val output = File(directory, changesPreviewName(filename))
            artifact.file.inputStream().use { input -> output.outputStream().use { target ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ensureActive(); val count = input.read(buffer); if (count < 0) break
                    target.write(buffer, 0, count)
                }
            } }
            ensureActive(); output
        }
    } catch (failure: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
        throw failure
    } finally { lease.close() }
}
