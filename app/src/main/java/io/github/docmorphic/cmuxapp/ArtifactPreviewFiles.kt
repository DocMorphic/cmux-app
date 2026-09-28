package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.UUID

internal data class ArtifactMetadata(val size: Long, val kind: ArtifactKind, val mime: String?) {
    companion object {
        fun read(value: JSONObject): ArtifactMetadata {
            check(value.getBoolean("exists")) { "This file no longer exists on your Mac." }
            check(!value.getBoolean("is_directory")) { "This path is a folder." }
            val size = value.getLong("size")
            check(size >= 0) { "Invalid file size from your Mac." }
            return ArtifactMetadata(size, ArtifactKind.read(value.opt("kind")), value.opt("mime_type") as? String)
        }
    }
}

/** Standard artifact contract: ordered, stat-size-pinned chunks. Changes retains its separate fingerprint check. */
internal class ArtifactContentTransfer(private val rpc: ArtifactRpc, private val authorization: ArtifactAuthorization) {
    suspend fun metadata(path: String) = ArtifactMetadata.read(rpc.stat(authorization, path))
    suspend fun stream(path: String, metadata: ArtifactMetadata, limit: Long, onChunk: suspend (ByteArray, Long) -> Unit) {
        check(metadata.size in 0..limit) { "This file is too large to preview." }
        var offset = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val response = rpc.fetch(authorization, path, offset, CHUNK_BYTES)
            currentCoroutineContext().ensureActive()
            val (bytes, eof) = withContext(Dispatchers.Default) {
                check(response.getLong("total_size") == metadata.size) { "The file changed on your Mac. Reload its preview." }
                check(response.getLong("offset") == offset) { "Out-of-order file content." }
                val encoded = response.getString("data_b64")
                check(encoded.length <= ((CHUNK_BYTES + 2) / 3) * 4) { "Oversized file chunk." }
                val decoded = Base64.getDecoder().decode(encoded)
                check(decoded.size <= CHUNK_BYTES && decoded.size.toLong() <= metadata.size - offset) { "Invalid file chunk size." }
                val eof = response.getBoolean("eof")
                val end = offset + decoded.size
                check(if (eof) end == metadata.size else decoded.isNotEmpty() && end < metadata.size) { "Incomplete file content." }
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
        check(metadata.size <= limit) { "This file is too large to preview (${metadata.size} bytes; limit $limit bytes)." }
        check(directory.mkdirs()) { "Could not create the preview folder." }
        val partial = File(directory, "download.partial")
        val destination = File(directory, changesPreviewName(filename).let { if (it == "download.partial") "file-download.partial" else it })
        try {
            partial.outputStream().use { output ->
                transfer.stream(path, metadata, limit) { bytes, received -> output.write(bytes); progress(received, metadata.size) }
                output.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            check(partial.renameTo(destination)) { "Could not finish the preview download." }
            LocalFilePreview(destination, metadata.size, metadata.mime, route)
        } catch (failure: Throwable) { directory.deleteRecursively(); throw failure }
    }
    override fun close() { directory.deleteRecursively() }
}
