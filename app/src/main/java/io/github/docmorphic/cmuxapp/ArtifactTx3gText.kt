package io.github.docmorphic.cmuxapp

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Text portion of an ISO/3GPP timed-text sample; trailing style boxes are not text. */
internal object ArtifactTx3gText {
    fun supports(mime: String) = mime == "text/3gpp" || mime == "text/3gpp-tt"
    fun decode(sample: ByteArray): String? {
        require(sample.size >= 2) { "Truncated timed-text sample" }
        val length = ((sample[0].toInt() and 255) shl 8) or (sample[1].toInt() and 255)
        require(length <= sample.size - 2) { "Truncated timed-text payload" }
        if (length == 0) return null
        val utf16 = length >= 2 && ((sample[2] == 0xfe.toByte() && sample[3] == 0xff.toByte()) ||
            (sample[2] == 0xff.toByte() && sample[3] == 0xfe.toByte()))
        val charset = if (utf16) Charsets.UTF_16 else Charsets.UTF_8
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(sample, 2, length)).toString().removePrefix("\uFEFF").takeIf { it.isNotBlank() }
    }
}
