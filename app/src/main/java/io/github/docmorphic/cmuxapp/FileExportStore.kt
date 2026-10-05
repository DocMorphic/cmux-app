package io.github.docmorphic.cmuxapp

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject

internal enum class FileExportReceiptPhase { PREPARING, READY, PRESENTING, HANDED_OFF, CANCELLED }
internal data class FileExportReceipt(val id: String, val title: String, val action: FileExportAction,
    val phase: FileExportReceiptPhase, val artifact: LocalFilePreview? = null, val sha256: String? = null)

/** Metadata is outside FileProvider roots. Only the prepared payload is grantable to another app. */
internal class FileExportStore(private val records: File, private val payloads: File) {
    private val owners = FileSaveFiles(File(records, "owners"))
    fun claim(id: String) = owners.claimUi(id)
    private fun validId(id: String): String = id.also { require(UUID.fromString(it).toString() == it) }
    private fun recordFile(id: String) = File(records, "${validId(id)}.json")
    private fun directory(id: String) = File(payloads, validId(id)).also {
        require(it.canonicalFile.parentFile == payloads.canonicalFile)
    }
    private fun artifactFile(id: String, name: String): File {
        require(name.isNotBlank() && name.length <= 255 && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() })
        return File(directory(id), name).also { require(it.canonicalFile.parentFile == directory(id).canonicalFile) }
    }
    private fun write(value: FileExportReceipt) {
        records.mkdirs(); check(records.isDirectory)
        val json = JSONObject().put("id", value.id).put("title", value.title).put("action", value.action.name)
            .put("phase", value.phase.name)
        value.artifact?.let { artifact ->
            json.put("filename", artifact.file.name).put("size", artifact.size).put("mime", artifact.mime ?: JSONObject.NULL)
                .put("route", artifact.route.name).put("sha256", value.sha256)
        }
        val target = recordFile(value.id); val partial = File(records, "${value.id}.partial")
        try {
            FileOutputStream(partial).use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { partial.delete() }
    }
    fun create(id: String, title: String, action: FileExportAction) {
        require(title.length <= 512)
        check(!recordFile(id).exists())
        write(FileExportReceipt(id, title, action, FileExportReceiptPhase.PREPARING))
    }
    fun read(id: String): FileExportReceipt? {
        val path = recordFile(id)
        if (!path.exists()) return null
        check(path.length() in 1..8192)
        val json = JSONObject(path.readText())
        check(json.getString("id") == id)
        val title = json.getString("title").also { require(it.length <= 512) }
        val phase = FileExportReceiptPhase.valueOf(json.getString("phase"))
        val hash = (json.opt("sha256") as? String)?.also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        val artifact = if (json.has("filename")) LocalFilePreview(artifactFile(id, json.getString("filename")),
            json.getLong("size").also { require(it >= 0) }, (json.opt("mime") as? String)?.also { require(it.length <= 255) },
            ChangesPreviewRoute.valueOf(json.getString("route"))) else null
        check(phase !in setOf(FileExportReceiptPhase.READY, FileExportReceiptPhase.PRESENTING, FileExportReceiptPhase.HANDED_OFF) ||
            artifact != null && hash != null)
        return FileExportReceipt(id, title, FileExportAction.valueOf(json.getString("action")), phase, artifact, hash)
    }
    suspend fun adopt(id: String, source: LocalFilePreview): LocalFilePreview = withContext(Dispatchers.IO) {
        val previous = checkNotNull(read(id)); check(previous.phase == FileExportReceiptPhase.PREPARING)
        val target = artifactFile(id, source.file.name)
        payloads.mkdirs(); check(!directory(id).exists())
        // Both app directories normally share a filesystem; moving avoids a second full payload copy.
        Files.move(checkNotNull(source.file.parentFile).toPath(), directory(id).toPath())
        val artifact = source.copy(file = target)
        check(target.length() == artifact.size)
        FileOutputStream(target, true).use { it.fd.sync() }
        val digest = digest(artifact)
        ensureActive()
        write(previous.copy(phase = FileExportReceiptPhase.READY, artifact = artifact, sha256 = digest))
        artifact
    }
    suspend fun restore(id: String): FileExportReceipt = withContext(Dispatchers.IO) {
        val value = checkNotNull(read(id)) { "The prepared file is no longer available" }
        if (value.phase == FileExportReceiptPhase.READY) {
            val artifact = checkNotNull(value.artifact)
            check(artifact.file.isFile && artifact.file.length() == artifact.size && digest(artifact) == value.sha256) {
                "The prepared file is unavailable or incomplete"
            }
        }
        value
    }
    fun presenting(id: String) {
        val value = checkNotNull(read(id)); check(value.phase == FileExportReceiptPhase.READY)
        write(value.copy(phase = FileExportReceiptPhase.PRESENTING))
    }
    fun handedOff(id: String) {
        val value = checkNotNull(read(id)); check(value.phase == FileExportReceiptPhase.PRESENTING)
        write(value.copy(phase = FileExportReceiptPhase.HANDED_OFF))
    }
    fun abandon(id: String, explicit: Boolean) {
        val value = read(id) ?: return
        if (value.phase in setOf(FileExportReceiptPhase.HANDED_OFF, FileExportReceiptPhase.PRESENTING)) return
        // An interrupted system call may already have granted the receiver this payload.
        if (!explicit && value.phase != FileExportReceiptPhase.PREPARING) return
        write(value.copy(phase = FileExportReceiptPhase.CANCELLED))
        directory(id).deleteRecursively()
    }
    suspend fun prune(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val ids = (records.listFiles().orEmpty().map { it.name.removeSuffix(".json").removeSuffix(".partial") } +
            payloads.listFiles().orEmpty().map { it.name }).filter(PrivateFileReclamation::id).toSet()
        for (id in ids) {
            ensureActive()
            val lease = claim(id) ?: continue
            lease.use {
                val path = recordFile(id)
                val partial = File(records, "$id.partial")
                val payload = File(payloads, id) // Never resolve paths from malformed metadata.
                val value = PrivateFileReclamation.journal { read(id) }
                if (value == null) {
                    // Includes interrupted atomic writes and payload adoption before READY.
                    if (listOf(path, partial, payload).all { PrivateFileReclamation.old(it, now, 7 * DAY) })
                        listOf(payload, partial, path).forEach(PrivateFileReclamation::remove)
                } else {
                    val limit = if (value.phase in setOf(FileExportReceiptPhase.HANDED_OFF, FileExportReceiptPhase.PRESENTING)) DAY else 7 * DAY
                    val modified = path.lastModified()
                    if (modified > 0 && modified <= now && now - modified >= limit) {
                        // Remove the receipt last so a failed payload deletion remains discoverable.
                        listOf(payload, partial, path).forEach(PrivateFileReclamation::remove)
                    } else if (PrivateFileReclamation.old(partial, now, 7 * DAY)) PrivateFileReclamation.remove(partial)
                }
            }
        }
    }
    private suspend fun digest(artifact: LocalFilePreview): String {
        val hash = MessageDigest.getInstance("SHA-256")
        artifact.file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { currentCoroutineContext().ensureActive(); val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    companion object { const val DAY = 24 * 60 * 60 * 1000L }
}
