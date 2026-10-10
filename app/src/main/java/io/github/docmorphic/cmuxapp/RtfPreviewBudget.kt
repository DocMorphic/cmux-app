package io.github.docmorphic.cmuxapp

internal object RtfPreviewPolicy {
    const val MIME = "application/rtf"
    fun matches(path: String, mime: String?) = path.substringAfterLast('/').substringAfterLast('.', "").equals("rtf", true) ||
        mime?.substringBefore(';')?.trim()?.lowercase() in setOf(MIME, "text/rtf", "application/x-rtf")
}

internal data class RtfPreviewLimits(
    val bytes: Int = 16 * 1024 * 1024,
    val depth: Int = 128,
    val controls: Int = 100_000,
    val groups: Int = 100_000,
    val pictures: Int = 256,
    val binaryBytes: Int = 8 * 1024 * 1024,
)

/** Validate the actual owned bytes before the browser decoder. Binary payloads
 * and escaped braces do not participate in group nesting or control parsing.
 * This is a resource/structure budget, not a replacement RTF renderer.
 */
internal object RtfPreviewBudget {
    fun validate(bytes: ByteArray, limits: RtfPreviewLimits = RtfPreviewLimits(), checkActive: () -> Unit = {}) {
        check(bytes.size in 7..limits.bytes && bytes.copyOfRange(0, 6).contentEquals("{\\rtf1".toByteArray())) {
            "This file is not a supported rich text document."
        }
        fun at(index: Int) = bytes[index].toInt() and 255
        fun alpha(value: Int) = value in 65..90 || value in 97..122
        fun digit(value: Int) = value in 48..57
        fun hex(value: Int) = digit(value) || value in 65..70 || value in 97..102
        check(!digit(at(6)) && !alpha(at(6))) { "Unsupported rich text version." }
        var index = 0; var depth = 0; var groups = 0; var controls = 0; var pictures = 0; var binary = 0L
        var ended = false; var steps = 0
        while (index < bytes.size) {
            if (steps++ % 4096 == 0) checkActive()
            val value = at(index++)
            if (ended) {
                check(value in listOf(9, 10, 13, 32)) { "Unexpected rich text trailing content." }
                continue
            }
            when (value) {
                123 -> { check(++depth <= limits.depth && ++groups <= limits.groups) { "This rich text document is too complex to preview." } }
                125 -> { check(depth > 0) { "Invalid rich text groups." }; if (--depth == 0) ended = true }
                92 -> {
                    check(++controls <= limits.controls && index < bytes.size) { "This rich text document is too complex or truncated." }
                    val first = at(index++)
                    if (!alpha(first)) {
                        if (first == 39) {
                            check(index + 2 <= bytes.size && hex(at(index)) && hex(at(index + 1))) { "Invalid rich text character escape." }
                            index += 2
                        }
                        continue
                    }
                    val start = index - 1
                    while (index < bytes.size && alpha(at(index))) index++
                    check(index - start <= 32) { "Invalid rich text control." }
                    val word = bytes.copyOfRange(start, index).toString(Charsets.US_ASCII)
                    val numberStart = index
                    if (index < bytes.size && at(index) == 45) index++
                    val digits = index
                    while (index < bytes.size && digit(at(index))) index++
                    check(index - digits <= 10 && (digits == numberStart || index > digits)) { "Invalid rich text parameter." }
                    val parameter = if (index > digits) bytes.copyOfRange(numberStart, index).toString(Charsets.US_ASCII).toLongOrNull() else null
                    check(parameter == null || parameter in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Invalid rich text parameter." }
                    if (index < bytes.size && at(index) == 32) index++
                    if (word == "pict") check(++pictures <= limits.pictures) { "This rich text document contains too many images." }
                    if (word == "fs") check(parameter != null && parameter in 0..1920L) { "Rich text font size exceeds the preview limit." }
                    if (word == "f") check(parameter != null && parameter in 0..32767L) { "Rich text font index exceeds the preview limit." }
                    if (word in setOf("picw", "pich")) check(parameter != null && parameter in 0..16_384L) { "Rich text image dimensions exceed the preview limit." }
                    if (word in setOf("picwgoal", "pichgoal")) check(parameter != null && parameter in 0..245_760L) { "Rich text image dimensions exceed the preview limit." }
                    if (word == "bin") {
                        check(parameter != null && parameter >= 0 && parameter <= bytes.size - index) { "Invalid rich text binary data." }
                        binary += parameter
                        check(binary <= limits.binaryBytes) { "Rich text images exceed the preview limit." }
                        checkActive()
                        index += parameter.toInt()
                    }
                }
            }
        }
        check(ended && depth == 0) { "This rich text document is truncated." }
        checkActive()
    }
}
