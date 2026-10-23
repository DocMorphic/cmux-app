package io.github.docmorphic.cmuxapp

/** Pure source block values; neither bodies nor parsed documents enter saved state. */
internal data class NativeMarkdownBlock(val index: Int, val kind: Kind, val text: String,
    val level: Int = 0, val indent: Int = 0, val marker: String = "", val language: String? = null,
    val header: Boolean = false) {
    enum class Kind { HEADING, PARAGRAPH, BULLET, ORDERED, QUOTE, RULE, CODE, TABLE_ROW }
}

internal fun interface NativeMarkdownNumbers { fun prefixEnd(text: String): Int }

internal object AndroidMarkdownNumbers : NativeMarkdownNumbers {
    override fun prefixEnd(text: String): Int {
        val characters = android.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT).apply { setText(text) }
        var start = characters.first()
        while (start < text.length) {
            if (android.icu.lang.UCharacter.getIntPropertyValue(text.codePointAt(start), android.icu.lang.UProperty.NUMERIC_TYPE) == 0) break
            start = characters.next()
        }
        return start
    }
}

/** ChatProseSegmenter -> ChatTextBlockParser -> ChatArtifactMarkdownDocument, without preview caps. */
internal object NativeMarkdownDocument {
    // Foundation .whitespaces includes zero-width space; Character.isWhitespace does not.
    private fun horizontal(c: Char) = c == '\t' || c == ' ' || c == '\u00a0' || c == '\u1680' ||
        c in '\u2000'..'\u200b' || c == '\u202f' || c == '\u205f' || c == '\u3000'
    private fun whitespace(c: Char) = c in '\t'..'\r' || c == ' ' || c == '\u0085' || c == '\u00a0' ||
        c == '\u1680' || c in '\u2000'..'\u200a' || c == '\u2028' || c == '\u2029' || c == '\u202f' || c == '\u205f' || c == '\u3000'
    private fun trimText(text: String) = text.trim { horizontal(it) || it in '\n'..'\r' || it == '\u0085' || it == '\u2028' || it == '\u2029' }

    /** Swift splits on Character LF; CRLF is a single different Character. */
    private fun lines(text: String): Sequence<String> = sequence {
        var start = 0
        text.forEachIndexed { index, c ->
            if (c == '\n' && (index == 0 || text[index - 1] != '\r')) {
                yield(text.substring(start, index)); start = index + 1
            }
        }
        yield(text.substring(start))
    }

    fun parse(markdown: String, numbers: NativeMarkdownNumbers = AndroidMarkdownNumbers,
        checkActive: () -> Unit = {}): List<NativeMarkdownBlock> {
        val result = ArrayList<NativeMarkdownBlock>()
        val prose = ArrayList<String>(); val code = ArrayList<String>()
        var inCode = false; var language: String? = null
        fun emitProse() {
            val text = trimText(prose.joinToString("\n")); prose.clear()
            if (text.isNotEmpty()) parseProse(text, result, numbers, checkActive)
        }
        fun emitCode() {
            result.add(NativeMarkdownBlock(result.size, NativeMarkdownBlock.Kind.CODE, code.joinToString("\n"), language = language))
            code.clear(); language = null
        }
        for (line in lines(markdown)) {
            checkActive()
            val stripped = line.trim(::horizontal)
            if (stripped.startsWith("```")) {
                if (inCode) { inCode = false; emitCode() }
                else { inCode = true; emitProse(); language = stripped.drop(3).trim(::horizontal).ifEmpty { null } }
            } else if (inCode) code.add(line) else prose.add(line)
        }
        if (inCode) emitCode() else emitProse()
        return result
    }

    private fun parseProse(text: String, output: MutableList<NativeMarkdownBlock>, numbers: NativeMarkdownNumbers, checkActive: () -> Unit) {
        val paragraph = ArrayList<String>()
        fun flush() {
            val joined = trimText(paragraph.joinToString("\n")); paragraph.clear()
            if (joined.isNotEmpty()) paragraphBlocks(joined, output)
        }
        for (line in lines(text)) {
            checkActive()
            val stripped = line.trim(::horizontal)
            if (stripped.isEmpty()) { flush(); continue }
            val block = structural(line, stripped, numbers)
            if (block == null) paragraph.add(line)
            else { flush(); output.add(block.copy(index = output.size)) }
        }
        flush()
    }

    private fun structural(raw: String, text: String, numbers: NativeMarkdownNumbers): NativeMarkdownBlock? {
        val indent = raw.takeWhile { it == ' ' }.length / 2
        val hashes = text.takeWhile { it == '#' }.length
        if (hashes in 1..6 && text.drop(hashes).startsWith(' '))
            return NativeMarkdownBlock(0, NativeMarkdownBlock.Kind.HEADING, text.drop(hashes).trim(::horizontal), level = hashes)
        val rule = text.filterNot(::whitespace)
        if (rule.length >= 3 && rule.first() in "-*_" && rule.all { it == rule.first() })
            return NativeMarkdownBlock(0, NativeMarkdownBlock.Kind.RULE, "")
        if (text.first() in "-*+" && text.drop(1).startsWith(' '))
            return NativeMarkdownBlock(0, NativeMarkdownBlock.Kind.BULLET, text.drop(1).trim(::horizontal), indent = indent)
        val digitsEnd = numbers.prefixEnd(text)
        if (digitsEnd > 0 && text.getOrNull(digitsEnd) in listOf('.', ')') && text.getOrNull(digitsEnd + 1) == ' ')
            return NativeMarkdownBlock(0, NativeMarkdownBlock.Kind.ORDERED, text.substring(digitsEnd + 1).trim(::horizontal),
                indent = indent, marker = text.substring(0, digitsEnd + 1))
        if (text.startsWith('>')) return NativeMarkdownBlock(0, NativeMarkdownBlock.Kind.QUOTE, text.drop(1).trim(::horizontal))
        return null
    }

    private fun cells(line: String): List<String>? = line.split('|').filter { it.isNotEmpty() }.map { it.trim(::horizontal) }
        .takeIf { it.size >= 2 && it.all(String::isNotEmpty) }

    private fun separator(line: String): Boolean = cells(line)?.all { cell ->
        val value = cell.trim(':'); value.length >= 3 && value.all { it == '-' }
    } == true

    private fun paragraphBlocks(text: String, output: MutableList<NativeMarkdownBlock>) {
        val rows = lines(text).toList(); val paragraph = ArrayList<String>(); var index = 0
        fun emit() {
            if (paragraph.isNotEmpty()) output.add(NativeMarkdownBlock(output.size, NativeMarkdownBlock.Kind.PARAGRAPH, paragraph.joinToString("\n")))
            paragraph.clear()
        }
        while (index < rows.size) {
            val header = cells(rows[index])
            if (index + 1 >= rows.size || header == null || !separator(rows[index + 1])) {
                paragraph.add(rows[index++]); continue
            }
            emit()
            output.add(NativeMarkdownBlock(output.size, NativeMarkdownBlock.Kind.TABLE_ROW, header.joinToString(" │ "), header = true))
            index += 2
            while (index < rows.size) {
                val next = cells(rows[index]) ?: break
                output.add(NativeMarkdownBlock(output.size, NativeMarkdownBlock.Kind.TABLE_ROW, next.joinToString(" │ ")))
                index++
            }
        }
        emit()
    }
}
