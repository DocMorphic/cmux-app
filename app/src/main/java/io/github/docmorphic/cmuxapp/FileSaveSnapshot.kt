package io.github.docmorphic.cmuxapp

import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject

internal enum class FileSavePhase { PREPARING, READY, WAITING, WRITING, FAILED }

/** Only a private cache identity and picker state enter the Activity's saved state. */
internal data class FileSaveSnapshot(val id: String, val filename: String, val mime: String,
    val phase: FileSavePhase, val destination: String? = null, val ownsGrant: Boolean = false) {
    init {
        require(UUID.fromString(id).toString() == id)
        require(filename.isNotBlank() && filename.length <= 255 && filename.none { it == '/' || it == '\\' || it.isISOControl() })
        require(filename != "." && filename != "..")
        require(mime.length in 1..255 && '\u0000' !in mime)
        require(destination == null || destination.length <= 8192 && destination.startsWith("content://"))
        require(phase != FileSavePhase.WRITING || destination != null)
        require(!ownsGrant || destination != null)
    }
    fun encode() = JSONObject().put("id", id).put("filename", filename).put("mime", mime)
        .put("phase", phase.name).put("destination", destination ?: JSONObject.NULL).put("owns_grant", ownsGrant).toString()
    companion object {
        fun decode(value: String?): FileSaveSnapshot? = runCatching {
            require(value != null && value.length <= 12_288)
            val json = JSONObject(value)
            FileSaveSnapshot(json.getString("id"), json.getString("filename"), json.getString("mime"),
                FileSavePhase.valueOf(json.getString("phase")), json.opt("destination") as? String, json.optBoolean("owns_grant"))
        }.getOrNull()
    }
}

/** Each pending Save owns an independent immutable snapshot; no global expiry deletes live pickers. */
internal class FileSaveFiles(private val root: File) {
    // Keep the suggested picker name independent of filesystem byte-length limits.
    fun file(value: FileSaveSnapshot): File = File(File(root, value.id), "content").also {
        require(it.canonicalFile.parentFile?.parentFile == root.canonicalFile) { "Invalid save snapshot" }
    }
    suspend fun prepare(value: FileSaveSnapshot, source: File, expectedSize: Long): File = withContext(Dispatchers.IO) {
        val target = file(value)
        check(target.parentFile!!.mkdirs()) { "Could not prepare file for saving." }
        try {
            source.inputStream().use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    ensureActive(); val count = input.read(buffer); if (count < 0) break
                    output.write(buffer, 0, count); total += count
                }
                check(total == expectedSize) { "The preview changed while preparing its save." }
                output.fd.sync()
            } }
            ensureActive(); target
        } catch (failure: Throwable) { remove(value); throw failure }
    }
    suspend fun write(value: FileSaveSnapshot, open: () -> OutputStream) = withContext(Dispatchers.IO) {
        file(value).inputStream().use { input -> open().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                ensureActive(); val count = input.read(buffer); if (count < 0) break
                output.write(buffer, 0, count)
            }
        } }
    }
    fun remove(value: FileSaveSnapshot) { file(value).parentFile?.deleteRecursively() }
}
