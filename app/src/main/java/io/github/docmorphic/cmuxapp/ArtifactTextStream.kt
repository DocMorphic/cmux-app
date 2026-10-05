package io.github.docmorphic.cmuxapp

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal data class ArtifactStreamingText(val artifact: LocalFilePreview, val document: ArtifactTextDocument,
    val complete: Boolean = false)

/** Strict incremental UTF-8 with bounded carry and throttled immutable UI snapshots. IO-thread confined. */
internal class ArtifactTextStream(private val clock: () -> Long = System::nanoTime) {
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    private var carry = byteArrayOf()
    private val text = StringBuilder()
    private var published = false
    private var lastPublished = 0L
    var invalid = false; private set
    fun append(bytes: ByteArray, eof: Boolean): ArtifactTextDocument? {
        if (invalid) return null
        try {
            val input = ByteBuffer.wrap(if (carry.isEmpty()) bytes else carry + bytes)
            val output = CharBuffer.allocate(16 * 1024)
            while (true) {
                val result = decoder.decode(input, output, eof)
                output.flip(); text.append(output); output.clear()
                if (result.isError) result.throwException()
                if (!result.isOverflow) break
            }
            carry = ByteArray(input.remaining()).also { input.get(it) }
            if (eof) {
                val result = decoder.flush(output)
                if (result.isError) result.throwException()
                output.flip(); text.append(output)
            }
        } catch (_: CharacterCodingException) {
            invalid = true; carry = byteArrayOf(); text.setLength(0)
            return null
        }
        val now = clock()
        if (!published || eof || now - lastPublished >= 100_000_000L) {
            published = true; lastPublished = now
            return ArtifactTextDocument(text.toString())
        }
        return null
    }
}
