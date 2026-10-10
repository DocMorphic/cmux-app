package io.github.docmorphic.cmuxapp

import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer

internal sealed interface ArtifactCaptionLookup {
    data class Cue(val text: String?) : ArtifactCaptionLookup
    data object Unsupported : ArtifactCaptionLookup
}

/** Independent file reader: never starts or seeks the audible/video player. */
internal object ArtifactPausedCaption {
    suspend fun read(file: File, track: ArtifactMediaTrack, positionMs: Int, durationMs: Int): ArtifactCaptionLookup {
        if (!track.caption || !ArtifactTx3gText.supports(track.mime)) return ArtifactCaptionLookup.Unsupported
        val context = currentCoroutineContext()
        context.ensureActive()
        val extractor = MediaExtractor()
        try {
            file.inputStream().use { input ->
                extractor.setDataSource(input.fd)
                context.ensureActive()
                val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                fun matches(index: Int): Boolean {
                    val format = formats[index]
                    return ArtifactTx3gText.supports(format.getString(MediaFormat.KEY_MIME).orEmpty()) &&
                        ArtifactMediaTracks.language(format.getString(MediaFormat.KEY_LANGUAGE)) == track.language
                }
                // Container indices normally match MediaPlayer's in-band indices.
                // If they differ, only a unique language/format match is safe.
                val index = track.index.takeIf { it in formats.indices && matches(it) }
                    ?: formats.indices.filter(::matches).singleOrNull()
                    ?: return ArtifactCaptionLookup.Unsupported
                val format = formats[index]
                val positionUs = positionMs.coerceAtLeast(0).toLong() * 1000L
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION)
                    else durationMs.toLong() * 1000L
                if (durationUs > 0 && positionUs >= durationUs) return ArtifactCaptionLookup.Cue(null)
                extractor.selectTrack(index)
                extractor.seekTo(positionUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val buffer = ByteBuffer.allocate(1024 * 1024)
                var cue: String? = null
                // Seeking normally lands on the active sample. Bound malformed
                // indexes without retaining an entire subtitle track in memory.
                repeat(4096) {
                    context.ensureActive()
                    val timeUs = extractor.sampleTime
                    if (timeUs < 0 || timeUs > positionUs) return ArtifactCaptionLookup.Cue(cue)
                    check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted caption sample" }
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    check(size in 2..buffer.capacity()) { "Invalid caption sample size" }
                    val bytes = ByteArray(size)
                    buffer.position(0); buffer.get(bytes)
                    cue = ArtifactTx3gText.decode(bytes)
                    if (!extractor.advance()) return ArtifactCaptionLookup.Cue(cue)
                }
                error("Caption seek exceeded sample limit")
            }
        } finally { extractor.release() }
    }
}
