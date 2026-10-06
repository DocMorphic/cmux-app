package io.github.docmorphic.cmuxapp

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/** A separate inode name keeps PiP alive when its source preview is dismissed. */
internal class ArtifactPlaybackFiles(root: File, source: File,
    private val link: (File, File) -> Unit = { from, to -> Files.createLink(to.toPath(), from.toPath()); Unit },
    private val checkActive: () -> Unit = {}
) : AutoCloseable {
    val directory = File(root, UUID.randomUUID().toString())
    val file = File(directory, changesPreviewName(source.name))
    private var lease: AutoCloseable? = null
    init {
        require(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Media file unavailable" }
        require(source.length() in 1..ChangesContentTransfer.MEDIA_BYTES) { "Media file is too large" }
        try {
            checkActive()
            check(directory.mkdirs())
            lease = ArtifactExportCache.hold(directory)
            try { link(source, file) }
            catch (_: java.io.IOException) {
                // Filesystems without hard links still get an independent, size-checked copy.
                file.delete()
                source.inputStream().use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var size = 0L
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count; check(size <= ChangesContentTransfer.MEDIA_BYTES)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                } }
            }
            checkActive()
            check(file.length() == source.length() && file.length() > 0) { "Media file changed" }
        } catch (failure: Throwable) { close(); throw failure }
    }
    override fun close() {
        try { PrivateFileReclamation.remove(directory) }
        finally { lease?.close(); lease = null }
    }
}
