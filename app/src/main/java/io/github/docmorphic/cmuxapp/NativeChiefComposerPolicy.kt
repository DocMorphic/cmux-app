package io.github.docmorphic.cmuxapp

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Foundation trimming shared by Chief's composer, Home search, work text and Other answers. */
internal object NativeChiefTextPolicy {
    // CharacterSet.whitespacesAndNewlines, enumerated through the pinned Swift reference.
    private fun whitespace(c: Char) = c.code in 0x09..0x0d || c.code == 0x20 || c.code == 0x85 ||
        c.code == 0xa0 || c.code == 0x1680 || c.code in 0x2000..0x200b || c.code == 0x2028 ||
        c.code == 0x2029 || c.code == 0x202f || c.code == 0x205f || c.code == 0x3000
    fun trim(text: String) = text.trim(::whitespace)
    fun hasContent(text: String) = trim(text).isNotEmpty()
}

/** Chief's text-only branch of ComposerBarGeometry and the store's plain send trimming. */
internal object NativeChiefComposerPolicy {
    fun trim(text: String) = NativeChiefTextPolicy.trim(text)
    fun hasContent(text: String) = NativeChiefTextPolicy.hasContent(text)
    fun sideInset(progress: Double, rest: Double = 28.0, keyboard: Double = 16.0) =
        rest + (keyboard - rest) * progress.coerceIn(0.0, 1.0)
    data class Rect(val x: Double, val y: Double, val width: Double, val height: Double)
    data class Row(val field: Rect, val send: Rect)
    fun row(width: Double, bottom: Double, height: Double, oneLine: Double, inset: Double, scale: Double): Row {
        val field = Rect(inset, bottom - height, max(0.0, width - 2 * inset), height)
        val y = bottom - oneLine / 2 - 14
        val rounded = if (scale > 0) ceil(y * scale - 1e-6) / scale else y
        return Row(field, Rect(field.width - 19.0 / 3 - 38, rounded - field.y, 38.0, 28.0))
    }
    fun lines(textHeight: Double, verticalPadding: Double, lineHeight: Double, leading: Double = 0.0): Double {
        val pitch = lineHeight + leading
        if (pitch <= 0) return 1.0
        val v = (textHeight - 2 * verticalPadding - lineHeight) / pitch
        val rounded = if (v >= 0) floor(v + .5) else ceil(v - .5)
        return max(1.0, rounded + 1)
    }
    fun height(lines: Double, oneLine: Double, lineHeight: Double, leading: Double = 0.0) =
        oneLine + (max(1.0, lines) - 1) * (lineHeight + leading)
}

/** UI-local editor reconciliation. Owner revisions acknowledge our edits without replacing newer
 * selection/composition state. An unpublished local edit also wins over an older owner clear. */
internal class NativeChiefDraftBridge(initialText: String, initialRevision: Long) {
    private var publishedText = initialText
    private var publishedRevision = initialRevision
    fun matches(text: String) = text == publishedText
    fun clearText() { publishedText = "" }
    fun published(text: String, revision: Long) {
        if (revision >= publishedRevision) { publishedText = text; publishedRevision = revision }
    }
    fun adopt(text: String, revision: Long, editorText: String): Boolean {
        if (revision <= publishedRevision || editorText != publishedText) return false
        published(text, revision)
        return true
    }
}
