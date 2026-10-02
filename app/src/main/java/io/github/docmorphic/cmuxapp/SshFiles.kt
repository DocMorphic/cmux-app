package io.github.docmorphic.cmuxapp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.util.UUID

/** POSIX paths belong to the remote host, regardless of the development OS. */
internal object SshFilePaths {
    fun validName(name: String) = name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\u0000' !in name
    fun path(path: String): String { require(path.startsWith('/') && '\u0000' !in path); return path }
    fun canInsert(path: String) = path.startsWith('/') && path.none { it.isISOControl() }
    fun shellWord(path: String): String { require(canInsert(path)); return SshTmuxEncoding.shellQuote(path) }
    fun join(directory: String, name: String): String { require(validName(name)); return path(directory).trimEnd('/') + "/" + name }
    fun parent(path: String) = path(path).trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
    // JSch interprets these characters as globs even in stat/get/remove/rename.
    // mkdir and realpath accept raw paths and must NOT receive this escaping.
    fun literal(path: String) = path(path).flatMap { if (it in "\\*?") listOf('\\', it) else listOf(it) }.joinToString("")
    fun trail(home: String, target: String): List<String> {
        path(home); path(target)
        val base = home.trimEnd('/').ifEmpty { "/" }
        val root = if (target == base || target.startsWith(if (base == "/") "/" else "$base/")) base else "/"
        val result = mutableListOf(root)
        for (component in target.removePrefix(root).split('/').filter { it.isNotEmpty() }) result += join(result.last(), component)
        return result
    }
    fun unique(name: String, taken: Set<String>): String {
        require(validName(name)); if (name !in taken) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        for (index in 2..9999) {
            val candidate = name.take(dot) + " $index" + name.drop(dot)
            if (candidate !in taken) return candidate
        }
        error("Too many files have this name. Choose another name.")
    }
}
internal data class SshFileEntry(val name: String, val directory: Boolean, val symlink: Boolean, val size: Long, val modified: Long) {
    companion object {
        fun of(name: String, attrs: SftpATTRS) = SshFileEntry(name, attrs.isDir, attrs.isLink, attrs.size, attrs.mTime.toLong() and 0xffffffffL)
    }
}
internal data class SshFileProgress(val name: String, val upload: Boolean, val bytes: Long, val total: Long?)

/** Each operation owns a cancellable SFTP channel on the account's SSH transport.
 * No shell commands and no Mac RPC fallback. Mutations are never replayed after
 * an uncertain reply. A later explicit action may open a fresh SSH connection. */
internal class SshFiles(private val connection: suspend () -> SshTransport) {
    private suspend fun <T> run(block: (ChannelSftp) -> T): T = withTimeout(10 * 60_000L) {
        connection().withSftp(block)
    }
    suspend fun start(requested: String?): List<String> = run { channel ->
        val home = channel.realpath(".")
        val target = requested?.takeIf { it.startsWith('/') && '\u0000' !in it }?.let {
            try { channel.realpath(it).takeIf { resolved -> channel.stat(SshFilePaths.literal(resolved)).isDir } }
            catch (_: SftpException) { null }
        } ?: home
        SshFilePaths.trail(home, target)
    }
    suspend fun list(path: String): List<SshFileEntry> = run { channel -> list(channel, path) }
    private fun list(channel: ChannelSftp, path: String): List<SshFileEntry> {
        val result = mutableListOf<SshFileEntry>()
        channel.ls(SshFilePaths.literal(path), ChannelSftp.LsEntrySelector { row ->
            if (row.filename != "." && row.filename != "..") {
                check(SshFilePaths.validName(row.filename)) { "The server returned an invalid file name" }
                check(result.size < 100_000) { "This folder contains too many entries to display" }
                result += SshFileEntry.of(row.filename, row.attrs)
            }
            ChannelSftp.LsEntrySelector.CONTINUE
        })
        return result.sortedWith(SshFilePresentation.order())
    }
    suspend fun directory(path: String): Boolean = run { it.stat(SshFilePaths.literal(path)).isDir }
    suspend fun mkdir(directory: String, name: String) = run { it.mkdir(SshFilePaths.join(directory, name)) }
    private fun absent(channel: ChannelSftp, path: String): Boolean = try { channel.lstat(SshFilePaths.literal(path)); false }
        catch (failure: SftpException) { if (failure.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) true else throw failure }
    suspend fun rename(directory: String, entry: SshFileEntry, name: String) = run { channel ->
        val from = SshFilePaths.join(directory, entry.name); val to = SshFilePaths.join(directory, name)
        check(from != to) { "Choose a different name" }
        check(absent(channel, to)) { "A file or folder with that name already exists" }
        channel.rename(SshFilePaths.literal(from), SshFilePaths.literal(to))
    }
    suspend fun delete(directory: String, entry: SshFileEntry) = run { channel ->
        val path = SshFilePaths.literal(SshFilePaths.join(directory, entry.name))
        // lstat distinguishes links from directories, including a target swapped
        // since the confirmation. Never traverse or recursively delete a link.
        val current = channel.lstat(path)
        check(current.isDir == entry.directory && current.isLink == entry.symlink) { "The file type changed. Refresh before deleting it." }
        if (current.isDir) {
            try { channel.rmdir(path) }
            catch (failure: SftpException) {
                if (failure.id == ChannelSftp.SSH_FX_FAILURE) error("This folder may not be empty. Delete what's inside it first.")
                throw failure
            }
        } else channel.rm(path)
    }
    suspend fun download(path: String, destination: File, progress: (Long, Long) -> Unit): Long {
        val context = currentCoroutineContext()
        try { return run { channel ->
          var complete = false
          try {
            context.ensureActive()
            val remote = SshFilePaths.literal(path); val before = channel.stat(remote)
            check(before.isReg) { "This item is not a regular file" }
            val expected = before.size; check(expected >= 0)
            check(destination.parentFile!!.let { it.isDirectory || it.mkdirs() }) { "Could not create download folder" }
            check(expected < destination.parentFile!!.usableSpace - 32L * 1024 * 1024) { "Not enough space to download this file" }
            var received = 0L; progress(0, expected)
            channel.get(remote).use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    context.ensureActive(); val count = input.read(buffer); if (count < 0) break
                    check(count.toLong() <= expected - received) { "The file changed. Download it again." }
                    output.write(buffer, 0, count); received += count; progress(received, expected)
                }
                output.fd.sync()
            } }
            val after = channel.stat(remote)
            check(received == expected && after.size == expected && after.mTime == before.mTime) { "The file changed. Download it again." }
            context.ensureActive(); complete = true; received
          } finally { if (!complete || !context.isActive) { destination.delete(); destination.parentFile?.delete() } }
        } } catch (failure: Throwable) { destination.delete(); throw failure }
    }
    /** Source is opened on the network worker and always closed there. */
    suspend fun upload(directory: String, name: String, total: Long?, source: () -> InputStream, progress: (Long, Long?) -> Unit): String {
        require(SshFilePaths.validName(name)); val context = currentCoroutineContext()
        var publishing = false
        try { return run { channel ->
            val chosen = SshFilePaths.unique(name, list(channel, directory).map { it.name }.toSet())
            val destination = SshFilePaths.join(directory, chosen)
            val temporary = SshFilePaths.join(directory, ".cmux-upload-${UUID.randomUUID()}")
            try {
                var sent = 0L; progress(0, total)
                source().use { input -> channel.put(SshFilePaths.literal(temporary)).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        context.ensureActive(); val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count); sent += count; progress(sent, total)
                    }
                } }
                check(total == null || sent == total) { "The upload size changed. Select the file again." }
                context.ensureActive(); check(absent(channel, destination)) { "A file with this name appeared during upload. Try again." }
                // OpenSSH's hardlink extension publishes without replacing a
                // concurrently created destination. Other servers use rename
                // after the existence check; v3 has no portable atomic CAS.
                publishing = true
                if (channel.getExtension("hardlink@openssh.com") == "1") channel.hardlink(SshFilePaths.literal(temporary), SshFilePaths.literal(destination))
                else channel.rename(SshFilePaths.literal(temporary), SshFilePaths.literal(destination))
                chosen
            } finally {
                // Best effort on this live channel. Transport loss may leave the
                // uniquely named partial file; never replay the upload itself.
                try { channel.rm(SshFilePaths.literal(temporary)) } catch (_: Exception) { }
            }
        } } catch (failure: Exception) {
            context.ensureActive()
            if (publishing) throw SshUploadUnconfirmed(failure)
            throw failure
        }
    }
}
