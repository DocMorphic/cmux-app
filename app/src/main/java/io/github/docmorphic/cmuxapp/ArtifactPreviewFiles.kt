package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.UUID

internal data class ArtifactMetadata(val size: Long, val kind: ArtifactKind, val mime: String?) {
    companion object {
        fun read(value: JSONObject): ArtifactMetadata {
            if (!value.getBoolean("exists")) throw ArtifactPreviewException(
                ArtifactPreviewFailure(ArtifactPreviewFailure.Kind.FILE_NOT_FOUND), "This file no longer exists on your Mac.")
            if (value.getBoolean("is_directory")) throw ArtifactPreviewException(
                ArtifactPreviewFailure(ArtifactPreviewFailure.Kind.NOT_REGULAR_FILE), "This path is a folder.")
            val size = value.getLong("size")
            requireArtifactResponse(size >= 0, "Invalid file size from your Mac.")
            return ArtifactMetadata(size, ArtifactKind.read(value.opt("kind")), value.opt("mime_type") as? String)
        }
    }
}

/** Standard artifact contract: ordered, stat-size-pinned chunks. Changes retains its separate fingerprint check. */
internal class ArtifactContentTransfer(private val rpc: ArtifactRpc, private val authorization: ArtifactAuthorization) {
    suspend fun metadata(path: String) = ArtifactMetadata.read(rpc.stat(authorization, path))
    suspend fun stream(path: String, metadata: ArtifactMetadata, limit: Long, onChunk: suspend (ByteArray, Long) -> Unit) {
        if (metadata.size > limit) throw ArtifactPreviewException(
            ArtifactPreviewFailure(ArtifactPreviewFailure.Kind.TOO_LARGE, metadata.size, limit), "This file is too large to preview.")
        check(metadata.size >= 0) { "Invalid file size from your Mac." }
        if (rpc.streamNative(authorization, path, metadata.size, onChunk)) return
        var offset = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val response = rpc.fetch(authorization, path, offset, CHUNK_BYTES)
            currentCoroutineContext().ensureActive()
            val (bytes, eof) = withContext(Dispatchers.Default) {
                if (response.getLong("total_size") != metadata.size) throw ArtifactPreviewException(
                    ArtifactPreviewFailure(ArtifactPreviewFailure.Kind.FILE_CHANGED), "The file changed on your Mac. Reload its preview.")
                requireArtifactResponse(response.getLong("offset") == offset, "Out-of-order file content.")
                val encoded = response.getString("data_b64")
                requireArtifactResponse(encoded.length <= ((CHUNK_BYTES + 2) / 3) * 4, "Oversized file chunk.")
                val decoded = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
                    throw ArtifactPreviewException(ArtifactPreviewFailure(ArtifactPreviewFailure.Kind.INVALID_RESPONSE), "Invalid file encoding.")
                }
                requireArtifactResponse(decoded.size <= CHUNK_BYTES && decoded.size.toLong() <= metadata.size - offset, "Invalid file chunk size.")
                val eof = response.getBoolean("eof")
                val end = offset + decoded.size
                requireArtifactResponse(if (eof) end == metadata.size else decoded.isNotEmpty() && end < metadata.size, "Incomplete file content.")
                decoded to eof
            }
            currentCoroutineContext().ensureActive()
            offset += bytes.size
            onChunk(bytes, offset)
            if (eof) return
        }
    }
    companion object { const val CHUNK_BYTES = 3 * 1024 * 1024 }
}

/** A selection owns its private download. Export actions make an independent narrowly shared copy. */
internal class ArtifactPreviewFiles(root: File, private val transfer: ArtifactContentTransfer) : AutoCloseable {
    private val directory = File(root, UUID.randomUUID().toString())
    suspend fun download(path: String, metadata: ArtifactMetadata, byteLimit: Long? = null, filename: String = changesPreviewName(path),
        progress: suspend (Long, Long) -> Unit): LocalFilePreview = withContext(Dispatchers.IO) {
        val route = filePreviewRoute(metadata.kind.name.lowercase(), metadata.mime, path)
        val limit = byteLimit ?: if (route == ChangesPreviewRoute.MEDIA) ChangesContentTransfer.MEDIA_BYTES else ChangesContentTransfer.PREVIEW_BYTES
        if (metadata.size > limit) throw ArtifactPreviewException(
            ArtifactPreviewFailure(ArtifactPreviewFailure.Kind.TOO_LARGE, metadata.size, limit),
            "This file is too large to preview (${metadata.size} bytes; limit $limit bytes).")
        if (!directory.mkdirs()) throw ArtifactPreviewException(
            ArtifactPreviewFailure(ArtifactPreviewFailure.Kind.LOCAL_STORAGE_UNAVAILABLE), "Could not create the preview folder.")
        val partial = File(directory, "download.partial")
        val destination = File(directory, changesPreviewName(filename).let { if (it == "download.partial") "file-download.partial" else it })
        try {
            ArtifactLocalOutput(partial).use { output ->
                transfer.stream(path, metadata, limit) { bytes, received -> output.write(bytes); progress(received, metadata.size) }
                output.sync()
            }
            currentCoroutineContext().ensureActive()
            if (!partial.renameTo(destination)) throw ArtifactPreviewException(
                ArtifactPreviewFailure(ArtifactPreviewFailure.Kind.LOCAL_STORAGE_UNAVAILABLE), "Could not finish the preview download.")
            LocalFilePreview(destination, metadata.size, metadata.mime, route)
        } catch (failure: Throwable) { directory.deleteRecursively(); throw failure }
    }
    override fun close() { directory.deleteRecursively() }
}

private fun requireArtifactResponse(condition: Boolean, message: String) {
    if (!condition) throw ArtifactPreviewException(ArtifactPreviewFailure(ArtifactPreviewFailure.Kind.INVALID_RESPONSE), message)
}
