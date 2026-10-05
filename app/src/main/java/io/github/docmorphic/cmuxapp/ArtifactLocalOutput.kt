package io.github.docmorphic.cmuxapp

import android.system.ErrnoException
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Only local file operations cross this boundary; network IO must keep its own failure kind. */
internal class ArtifactLocalOutput(private val output: FileOutputStream) : AutoCloseable {
    constructor(file: File) : this(local { file.outputStream() })
    fun write(bytes: ByteArray) = local { output.write(bytes) }
    fun sync() = local { output.fd.sync() }
    override fun close() = local { output.close() }

    companion object {
        private inline fun <T> local(operation: () -> T): T = try { operation() } catch (error: IOException) {
            throw classify(error)
        }

        internal fun classify(error: IOException): ArtifactPreviewException {
            // Libcore wraps the OS errno in IOException for open/write/fsync/close.
            // Do not infer a full disk from localized exception message text.
            var cause: Throwable? = error
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            var full = false
            while (cause != null && seen.add(cause)) {
                if (cause is ErrnoException && (cause.errno == OsConstants.ENOSPC || cause.errno == OsConstants.EDQUOT)) { full = true; break }
                cause = cause.cause
            }
            return ArtifactPreviewException(ArtifactPreviewFailure(if (full)
                ArtifactPreviewFailure.Kind.LOCAL_STORAGE_FULL else ArtifactPreviewFailure.Kind.LOCAL_STORAGE_UNAVAILABLE),
                if (full) "Not enough device storage for this preview." else "Could not store the preview on this device.")
                .also { it.initCause(error) }
        }
    }
}
