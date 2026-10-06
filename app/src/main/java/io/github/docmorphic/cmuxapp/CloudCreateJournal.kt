package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal fun CloudMachineCreateOptions.normalized() = copy(
    provider = provider?.trim()?.takeIf { it.isNotEmpty() }, image = image?.trim()?.takeIf { it.isNotEmpty() })

internal interface CloudCreateJournal {
    suspend fun resolve(options: CloudMachineCreateOptions): String
    suspend fun complete(key: String)
}

/** One pending create per login/user/team. Root belongs in noBackupFilesDir. No credentials are stored. */
internal class CloudCreateFileJournal(root: File, owner: CloudAccountScope,
    private val replace: (Path, Path) -> Unit = { source, target -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); Unit }
) : CloudCreateJournal {
    private val file: File
    init {
        val scope = org.json.JSONArray(listOf("https://cmux.com", owner.login, owner.user, owner.team)).toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
        file = File(root, "$hash.json")
    }
    override suspend fun resolve(options: CloudMachineCreateOptions): String = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val body = CloudApiRequests().create(options.normalized(), "validation").body!!
            val old = read()
            if (old != null && TaskSubmissionIdentity.sameRequest(JSONObject(old.getString("options")), JSONObject(body)))
                return@synchronized old.getString("key")
            val key = UUID.randomUUID().toString()
            write(JSONObject().put("version", 1).put("key", key).put("options", body))
            key
        }
    }
    override suspend fun complete(key: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (read()?.getString("key") == key) check(file.delete()) { "Could not settle Cloud creation identity" }
        }
    }
    private fun read(): JSONObject? {
        if (!file.exists()) return null
        require(file.length() in 1..16384) { "Invalid saved Cloud creation" }
        val value = JSONObject(file.readText())
        require(value.getInt("version") == 1 && UUID.fromString(value.getString("key")).toString() == value.getString("key"))
        // Corrupt/unreadable state must fail closed instead of silently inventing a fresh create key.
        JSONObject(value.getString("options"))
        return value
    }
    private fun write(value: JSONObject) {
        val bytes = value.toString().toByteArray()
        require(bytes.size <= 16384) { "Cloud creation options are too large" }
        val directory = file.parentFile!!
        check(directory.isDirectory || directory.mkdirs()) { "Could not save Cloud creation" }
        val temporary = Files.createTempFile(directory.toPath(), "create-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { it.write(bytes); it.fd.sync() }
            replace(temporary, file.toPath())
        } finally { Files.deleteIfExists(temporary) }
    }
    companion object { private val lock = Any() }
}
