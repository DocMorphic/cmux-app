package io.github.docmorphic.cmuxapp

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** Confirms public extractor behavior before relying on it for paused captions. */
class ArtifactCaptionExtractorRuntimeTest {
    @Test fun selectedEmbeddedTextCanBeReadWithoutStartingPlayback() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "caption-extractor.mp4")
        instrumentation.context.assets.open("media/tracks.mp4").use { input -> file.outputStream().use(input::copyTo) }
        val evidence = File(context.getExternalFilesDir(null), "caption-extractor").apply { mkdirs() }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            File(evidence, "formats.txt").writeText(formats.joinToString("\n"))
            val subtitles = formats.indices.filter { ArtifactTx3gText.supports(formats[it].getString(MediaFormat.KEY_MIME).orEmpty()) }
            assertEquals(2, subtitles.size)
            for (index in subtitles) {
                extractor.selectTrack(index)
                extractor.seekTo(15_000_000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val buffer = ByteBuffer.allocate(131_072)
                val size = extractor.readSampleData(buffer, 0)
                val time = extractor.sampleTime
                val length = ((buffer.get(0).toInt() and 255) shl 8) or (buffer.get(1).toInt() and 255)
                val bytes = ByteArray(length).also { buffer.position(2); buffer.get(it) }
                val text = bytes.toString(Charsets.UTF_8)
                extractor.advance()
                File(evidence, "samples.txt").appendText("index=$index timeUs=$time size=$size text=$text nextTimeUs=${extractor.sampleTime}\n")
                assertEquals(0L, time)
                assertTrue(text == "CMUX ENGLISH CUE" || text == "CMUX FRENCH CUE")
                extractor.unselectTrack(index)
            }
        } finally { extractor.release(); file.delete() }
    }
}
