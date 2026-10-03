package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

internal interface ArtifactLane : AutoCloseable {
    suspend fun read(maximumBytes: Int): ByteArray?
}

/** The final chunk is withheld until EOF proves there are no trailing bytes. */
internal object ArtifactLaneTransfer {
    class BeforeData(cause: Throwable? = null) : IOException("Artifact lane failed before data", cause)
    class Interrupted(cause: Throwable? = null) : IOException("File transfer interrupted. Reload the file to retry.", cause)

    suspend fun stream(lane: ArtifactLane, size: Long, consume: suspend (ByteArray, Long) -> Unit) {
        require(size >= 0)
        var offset = 0L
        var final: ByteArray? = null
        fun failure(cause: Throwable? = null): IOException = if (offset == 0L) BeforeData(cause) else Interrupted(cause)
        while (true) {
            currentCoroutineContext().ensureActive()
            val bytes = try { lane.read(64 * 1024) }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); throw failure(error) }
            currentCoroutineContext().ensureActive()
            if (bytes == null) {
                if (offset != size) throw failure()
                if (size == 0L) consume(byteArrayOf(), 0)
                else consume(checkNotNull(final), offset)
                return
            }
            if (final != null || bytes.isEmpty() || bytes.size > 64 * 1024 || bytes.size.toLong() > size - offset)
                throw failure()
            offset += bytes.size
            if (offset == size) final = bytes
            else consume(bytes, offset)
        }
    }
}
