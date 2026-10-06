package io.github.docmorphic.cmuxapp

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Installation identity, not an account credential or a terminal participant ID. */
internal class TerminalDeviceIdentity(model: String?, val deviceId: String? = null) {
    val name: String = sanitize(model) ?: "Android"

    init { require(deviceId == null || canonicalId(deviceId) == deviceId) }

    companion object {
        internal fun canonicalId(raw: String): String? = runCatching {
            UUID.fromString(raw).toString().takeIf { it.equals(raw, ignoreCase = true) }
        }.getOrNull()

        private fun sanitize(raw: String?): String? {
            val result = StringBuilder()
            var pendingSpace = false
            var count = 0
            for (point in raw.orEmpty().codePoints().toArray()) {
                if (Character.isWhitespace(point) || Character.isSpaceChar(point) || Character.isISOControl(point)) {
                    pendingSpace = result.isNotEmpty()
                    continue
                }
                if (pendingSpace && count < 64) { result.append(' '); count++; pendingSpace = false }
                if (count >= 64) break
                result.appendCodePoint(point); count++
            }
            return result.toString().trim().takeIf { it.isNotEmpty() }
        }
    }
}

/** Caller supplies app-private noBackupFilesDir. Survives logout and updates, never restored to another phone. */
internal class TerminalDeviceIdentityStore(private val directory: File) {
    fun loadOrCreate(): String = synchronized(lock) {
        val file = File(directory, "terminal-device-id")
        if (file.exists()) {
            require(file.length() == 36L) { "Invalid terminal installation identity" }
            return@synchronized requireNotNull(TerminalDeviceIdentity.canonicalId(file.readText(Charsets.US_ASCII)))
        }
        check(directory.isDirectory || directory.mkdirs()) { "Could not store terminal installation identity" }
        val id = UUID.randomUUID().toString()
        val temporary = Files.createTempFile(directory.toPath(), "terminal-device-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(id.toByteArray(Charsets.US_ASCII)); output.fd.sync()
            }
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            id
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object { private val lock = Any() }
}
