package io.github.docmorphic.cmuxapp

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/** App-private, per-install metadata. The UI owner supplies a directory in noBackupFilesDir. */
internal class NativeWhatsNewFileStore(
    directory: File,
    private val replace: (Path, Path) -> Unit = { source, destination ->
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
) : WhatsNewStorage {
    private val file = File(directory, "notices-v1.json").canonicalFile
    private val lock = locks.getOrPut(file.path) { Any() }
    override fun read(key: String): String? = synchronized(lock) { runCatching { readAll()[key] }.getOrNull() }
    override fun write(updates: Map<String, String?>): Boolean = synchronized(lock) {
        var pending: Path? = null
        try {
            val values = readAll().toMutableMap()
            updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            val bytes = JSONObject(values as Map<*, *>).toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_BYTES)
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            pending = Files.createTempFile(file.parentFile!!.toPath(), "notices-", ".tmp")
            FileOutputStream(pending.toFile()).use { stream -> stream.write(bytes); stream.fd.sync() }
            // Publish only a complete generation. No SharedPreferences in-memory commit on failure.
            replace(pending, file.toPath())
            true
        } catch (_: Exception) { false }
        finally { pending?.let { runCatching { Files.deleteIfExists(it) } } }
    }
    private fun readAll(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        require(file.length() <= MAX_BYTES)
        // I/O failures abort a write; they must not turn a temporarily unreadable ledger into an empty one.
        val raw = file.readText(Charsets.UTF_8)
        return try {
            val root = JSONObject(raw)
            root.keys().asSequence().associateWith { root.get(it) as String }
        } catch (_: Exception) { emptyMap() }
    }
    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
        private val locks = ConcurrentHashMap<String, Any>()
    }
}
