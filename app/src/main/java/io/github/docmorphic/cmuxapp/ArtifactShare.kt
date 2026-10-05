package io.github.docmorphic.cmuxapp

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File

internal data class FileActionType(val filename: String, val mime: String)
internal fun fileActionType(filename: String, hostMime: String?): FileActionType {
    val types = MimeTypeMap.getSingleton()
    val inferred = types.getMimeTypeFromExtension(filename.substringAfterLast('.', "").lowercase())
    val mime = hostMime?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: inferred ?: "application/octet-stream"
    val extension = types.getExtensionFromMimeType(mime)?.takeIf { it.matches(Regex("[A-Za-z0-9]+")) }
    return FileActionType(if (extension != null && inferred != mime) "$filename.$extension" else filename, mime)
}

internal fun artifactShareIntent(context: Context, file: File, mime: String): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.task-previews", file)
    return Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).apply {
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** Shares original bytes without inline-preview limits. Successful exports outlive the row. */
internal suspend fun materializeArtifactShare(rpc: ArtifactRpc, authorization: ArtifactAuthorization, path: String,
    root: File, name: (ArtifactMetadata) -> String = { changesPreviewName(path) }): LocalFilePreview {
    val transfer = ArtifactContentTransfer(rpc, authorization)
    val metadata = transfer.metadata(path)
    currentCoroutineContext().ensureActive()
    withContext(Dispatchers.IO) {
        root.mkdirs()
        ArtifactExportCache.prune(root)
    }
    val files = ArtifactPreviewFiles(root, transfer)
    val lease = ArtifactExportCache.hold(files.directory)
    try {
        return files.download(path, metadata, byteLimit = Long.MAX_VALUE, filename = name(metadata)) { _, _ -> }
    } catch (failure: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) { files.close() }
        throw failure
    } finally { lease.close() }
}
