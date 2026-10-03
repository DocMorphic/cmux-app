package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64

internal enum class ChangesRevision(val wire: String) { BASE("base"), CURRENT("current") }
internal class ChangesRevisionChanged : Exception("The file changed on your Mac. Refresh to load its latest revision.")
internal class ChangesContentTooLarge : Exception("Too large to expand")
internal data class ChangesFileMetadata(val size: Long, val kind: String, val mime: String?, val fingerprint: String)
internal data class ChangesCurrentFile(val lines: List<String>, val fingerprint: String)

internal fun changesFingerprintValid(value: String?, currentOnly: Boolean = false): Boolean {
    val parts = value?.split(':') ?: return false
    if (!currentOnly && parts.size == 3 && parts[0] == "blob") return parts[1].isNotEmpty() && parts[2].isNotEmpty()
    return parts.size == 6 && parts[0] == "stat" && parts[1].toLongOrNull()?.let { it >= 0 } == true &&
        parts[2].toLongOrNull() != null && parts[3].toULongOrNull() != null &&
        parts[4].toULongOrNull() != null && parts[5].toLongOrNull() != null
}

/** Ordered, fingerprint-pinned content transfer shared by expansion and revision previews. */
internal class ChangesContentTransfer(
    private val stat: suspend (String, ChangesRevision) -> JSONObject,
    private val fetch: suspend (String, ChangesRevision, Long, Int) -> JSONObject,
) {
    suspend fun metadata(path: String, revision: ChangesRevision): ChangesFileMetadata {
        val response = stat(path, revision)
        currentCoroutineContext().ensureActive()
        check(response.getBoolean("exists")) { "This file no longer exists on your Mac." }
        check(!response.getBoolean("is_directory")) { "Directories cannot be previewed." }
        val size = response.getLong("size")
        val fingerprint = response.opt("content_fingerprint") as? String
        check(size >= 0 && changesFingerprintValid(fingerprint)) { "Invalid file metadata from your Mac." }
        return ChangesFileMetadata(size, response.getString("kind"), response.opt("mime_type") as? String, checkNotNull(fingerprint))
    }
    suspend fun stream(path: String, revision: ChangesRevision, metadata: ChangesFileMetadata, limit: Long,
        onChunk: suspend (ByteArray, Long, Long) -> Unit) {
        if (metadata.size > limit) throw ChangesContentTooLarge()
        var offset = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val value = fetch(path, revision, offset, CHUNK_BYTES)
            currentCoroutineContext().ensureActive()
            val chunk = withContext(Dispatchers.Default) {
                val fingerprint = value.opt("content_fingerprint") as? String
                check(changesFingerprintValid(fingerprint)) { "Invalid content fingerprint from your Mac." }
                if (fingerprint != metadata.fingerprint) throw ChangesRevisionChanged()
                val total = value.getLong("total_size")
                if (total != metadata.size) throw ChangesRevisionChanged()
                check(value.getLong("offset") == offset && total >= offset) { "Out-of-order file content." }
                val encoded = value.getString("data_b64")
                check(encoded.length <= ((CHUNK_BYTES + 2) / 3) * 4) { "Oversized file chunk." }
                val bytes = Base64.getDecoder().decode(encoded)
                check(bytes.size <= CHUNK_BYTES && bytes.size.toLong() <= total - offset) { "Invalid file chunk size." }
                val eof = value.getBoolean("eof")
                val end = offset + bytes.size
                check(if (eof) end == total else bytes.isNotEmpty() && end < total) { "Incomplete file content." }
                bytes to eof
            }
            currentCoroutineContext().ensureActive()
            offset += chunk.first.size
            onChunk(chunk.first, offset, metadata.size)
            if (chunk.second) return
        }
    }
    suspend fun currentLines(path: String): ChangesCurrentFile {
        val metadata = metadata(path, ChangesRevision.CURRENT)
        if (metadata.size > EXPANSION_BYTES) throw ChangesContentTooLarge()
        check(changesFingerprintValid(metadata.fingerprint, currentOnly = true)) { "Invalid current-file identity." }
        val bytes = ByteArrayOutputStream(metadata.size.toInt())
        stream(path, ChangesRevision.CURRENT, metadata, EXPANSION_BYTES) { chunk, _, _ -> bytes.write(chunk) }
        val lines = withContext(Dispatchers.Default) { splitLines(bytes.toByteArray()) }
        return ChangesCurrentFile(lines, metadata.fingerprint)
    }
    companion object {
        const val CHUNK_BYTES = 3 * 1024 * 1024
        const val EXPANSION_BYTES = 5L * 1024 * 1024
        const val EXPANSION_LINES = 200_000
        const val PREVIEW_BYTES = 64L * 1024 * 1024
        const val MEDIA_BYTES = 512L * 1024 * 1024
        fun splitLines(bytes: ByteArray): List<String> = buildList {
            var start = 0
            fun append(end: Int) {
                if (size >= EXPANSION_LINES) throw ChangesContentTooLarge()
                add(String(bytes, start, end - start, Charsets.UTF_8))
            }
            bytes.indices.forEach { index -> if (bytes[index] == 10.toByte()) { append(index); start = index + 1 } }
            if (start < bytes.size) append(bytes.size)
        }
    }
}
