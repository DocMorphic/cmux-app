package io.github.docmorphic.cmuxapp

import java.io.File
import java.io.OutputStream
import java.io.FileOutputStream
import java.nio.file.StandardCopyOption
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import kotlinx.coroutines.*
import org.json.JSONObject

internal enum class FileSavePhase { PREPARING, READY, WAITING, WRITING, FAILED, COMPLETED, CANCELLED }

/** Only a private snapshot identity and picker state enter the Activity's saved state. */
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

/** Immutable bytes and a durable journal. The caller confines each identity to one writer. */
internal class FileSaveFiles(private val root: File) {
    fun load(id: String): FileSaveSnapshot? {
        require(UUID.fromString(id).toString() == id)
        val directory = File(root, id)
        require(directory.canonicalFile.parentFile == root.canonicalFile)
        val state = File(directory, "state.json")
        if (!state.exists()) return null
        check(state.length() <= 12_288) { "Invalid save journal" }
        return checkNotNull(FileSaveSnapshot.decode(state.readText())).also { check(it.id == id) }
    }
    fun entries(): List<FileSaveSnapshot> = root.listFiles().orEmpty()
        .filter { it.isDirectory }.mapNotNull { runCatching { load(it.name) }.getOrNull() }
    fun recoverable(): List<FileSaveSnapshot> = entries()
        .filter { it.phase == FileSavePhase.WRITING || it.phase in terminal && it.ownsGrant }
    /** A file lock also serializes the isolated browser process and the main-process worker. */
    suspend fun <T> withWriter(value: FileSaveSnapshot, action: suspend () -> T): T =
        withLock(member(value, "writer.lock"), action)
    suspend fun <T> withGrantLock(action: suspend () -> T): T = withLock(File(root, ".grants.lock"), action)
    suspend fun <T> withDestination(value: FileSaveSnapshot, action: suspend () -> T): T {
        val identity = MessageDigest.getInstance("SHA-256").digest(checkNotNull(value.destination).toByteArray(Charsets.UTF_8)).hex()
        return withLock(File(root, ".destination-$identity.lock"), action)
    }
    private suspend fun <T> withLock(path: File, action: suspend () -> T): T = withContext(Dispatchers.IO) {
        RandomAccessFile(path, "rw").use { handle ->
            var lock: java.nio.channels.FileLock? = null
            try {
                while (lock == null) {
                    ensureActive()
                    lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock == null) delay(50)
                }
                action()
            } finally { lock?.release() }
        }
    }
    fun requestCancellation(value: FileSaveSnapshot) {
        if (file(value).parentFile!!.exists()) FileOutputStream(member(value, "cancel"), true).use { it.fd.sync() }
    }
    fun isCancellationRequested(value: FileSaveSnapshot) = member(value, "cancel").exists()
    fun reportProgress(value: FileSaveSnapshot, received: Long, total: Long) {
        require(received in 0..total)
        atomic(member(value, "progress.json"), JSONObject().put("received", received).put("total", total).toString())
    }
    fun progress(value: FileSaveSnapshot): Pair<Long, Long>? = runCatching {
        val path = member(value, "progress.json")
        check(path.length() in 1..256)
        val json = JSONObject(path.readText())
        val received = json.getLong("received"); val total = json.getLong("total")
        require(received in 0..total); received to total
    }.getOrNull()
    fun file(value: FileSaveSnapshot): File = File(File(root, value.id), "content").also {
        require(it.canonicalFile.parentFile?.parentFile == root.canonicalFile) { "Invalid save snapshot" }
    }
    private fun member(value: FileSaveSnapshot, name: String) = File(file(value).parentFile, name).also {
        require(it.canonicalFile.parentFile == file(value).parentFile!!.canonicalFile)
    }
    private fun atomic(file: File, text: String) {
        val partial = File(file.parentFile, file.name + ".partial")
        try {
            FileOutputStream(partial).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { partial.delete() }
    }
    @Synchronized
    fun record(value: FileSaveSnapshot) {
        val previous = journal(value)
        check(previous?.phase !in setOf(FileSavePhase.COMPLETED, FileSavePhase.CANCELLED) || previous?.phase == value.phase) {
            "This save has already finished"
        }
        atomic(member(value, "state.json"), value.encode())
    }
    @Synchronized
    fun latest(value: FileSaveSnapshot): FileSaveSnapshot = journal(value) ?: value
    private fun journal(value: FileSaveSnapshot): FileSaveSnapshot? {
        val path = member(value, "state.json")
        if (!path.exists()) return null
        check(path.length() <= 12_288) { "Invalid save journal" }
        return checkNotNull(FileSaveSnapshot.decode(path.readText())).also {
            check(it.id == value.id && (value.phase == FileSavePhase.PREPARING ||
                it.filename == value.filename && it.mime == value.mime)) { "Save identity changed" }
        }
    }
    /** Disk state wins over an older Activity bundle, including completed/cancelled tombstones. */
    suspend fun restore(value: FileSaveSnapshot, legacyRoot: File? = null): FileSaveSnapshot = withContext(Dispatchers.IO) {
        if (!file(value).parentFile!!.exists() && legacyRoot != null && value.phase != FileSavePhase.PREPARING) {
            val legacy = FileSaveFiles(legacyRoot)
            val source = legacy.file(value)
            check(source.isFile) { "Save snapshot is no longer available" }
            prepare(value, source, source.length()); record(value); legacy.remove(value)
        }
        val latest = journal(value) ?: value
        if (latest.phase == FileSavePhase.COMPLETED || latest.phase == FileSavePhase.CANCELLED) return@withContext latest
        verify(latest)
        if (latest.phase == FileSavePhase.PREPARING) latest.copy(phase = FileSavePhase.READY).also(::record) else latest
    }
    suspend fun prepare(value: FileSaveSnapshot, source: File, expectedSize: Long): File =
        prepareStream(value, expectedSize) { append ->
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break
                    append(buffer, count)
                }
            }
        }
    /** Also accepts a remote stream so Save does not require a second full temporary download. */
    suspend fun prepareStream(value: FileSaveSnapshot, expectedSize: Long,
        produce: suspend (suspend (ByteArray, Int) -> Unit) -> Unit): File = withContext(Dispatchers.IO) {
        require(expectedSize >= 0)
        val target = file(value)
        check(target.parentFile!!.mkdirs()) { "Could not prepare file for saving." }
        try {
            record(value.copy(phase = FileSavePhase.PREPARING))
            val digest = MessageDigest.getInstance("SHA-256")
            target.outputStream().use { output ->
                var total = 0L
                produce { bytes, count ->
                    ensureActive(); require(count in 0..bytes.size)
                    check(count.toLong() <= expectedSize - total) { "The file changed while preparing its save." }
                    output.write(bytes, 0, count); digest.update(bytes, 0, count); total += count
                }
                ensureActive(); check(total == expectedSize) { "The file changed while preparing its save." }
                output.fd.sync()
            }
            ensureActive()
            atomic(member(value, "seal.json"), JSONObject().put("size", expectedSize).put("sha256", digest.digest().hex()).toString())
            target
        } catch (failure: Throwable) { remove(value); throw failure }
    }
    private suspend fun verify(value: FileSaveSnapshot): Long {
        val seal = member(value, "seal.json")
        check(seal.isFile && seal.length() <= 256) { "Save preparation was interrupted" }
        val metadata = JSONObject(seal.readText()); val size = metadata.getLong("size")
        val hash = metadata.getString("sha256")
        check(size >= 0 && hash.matches(Regex("[0-9a-f]{64}")) && file(value).length() == size) { "Save snapshot changed" }
        val digest = MessageDigest.getInstance("SHA-256")
        file(value).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        check(digest.digest().hex() == hash) { "Save snapshot changed" }
        return size
    }
    suspend fun write(value: FileSaveSnapshot, progress: suspend (Long, Long) -> Unit = { _, _ -> },
        open: () -> OutputStream) = withContext(Dispatchers.IO) {
        // Validate before opening/truncating the user's selected destination.
        val size = verify(value)
        file(value).inputStream().use { input -> open().use { output ->
            val buffer = ByteArray(64 * 1024); var written = 0L
            progress(0, size)
            while (true) {
                ensureActive(); val count = input.read(buffer); if (count < 0) break
                output.write(buffer, 0, count); written += count; progress(written, size)
            }
            check(written == size) { "Save snapshot changed" }
            output.flush()
        } }
    }
    /** Keep a small terminal receipt so an older Activity bundle cannot repeat an export. */
    fun finish(value: FileSaveSnapshot, phase: FileSavePhase) {
        require(phase == FileSavePhase.COMPLETED || phase == FileSavePhase.CANCELLED)
        if (!file(value).parentFile!!.exists()) return
        record(value.copy(phase = phase)); file(value).delete(); member(value, "seal.json").delete()
        member(value, "progress.json").delete()
    }
    fun remove(value: FileSaveSnapshot) { file(value).parentFile?.deleteRecursively() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    companion object { val terminal = setOf(FileSavePhase.COMPLETED, FileSavePhase.CANCELLED) }
}
