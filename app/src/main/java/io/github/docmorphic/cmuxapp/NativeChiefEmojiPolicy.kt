package io.github.docmorphic.cmuxapp

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator
import java.util.Locale

/** ConversationRows' exact one-to-three grapheme rule, not an RGI-only emoji matcher. */
internal object NativeChiefEmojiPolicy {
    interface Unicode {
        fun clusters(text: String): Sequence<String>
        fun emoji(scalar: Int): Boolean
        fun presentation(scalar: Int): Boolean
        fun whitespace(scalar: Int): Boolean
    }

    private object AndroidUnicode : Unicode {
        override fun clusters(text: String) = sequence {
            val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                yield(text.substring(start, end))
                start = end; end = iterator.next()
            }
        }
        override fun emoji(scalar: Int) = UCharacter.hasBinaryProperty(scalar, UProperty.EMOJI)
        override fun presentation(scalar: Int) = UCharacter.hasBinaryProperty(scalar, UProperty.EMOJI_PRESENTATION)
        override fun whitespace(scalar: Int) = UCharacter.isUWhiteSpace(scalar)
    }

    fun mayBeEmojiOnly(text: String): Boolean {
        var bytes = 0
        var offset = 0
        while (offset < text.length) {
            val scalar = text.codePointAt(offset); offset += Character.charCount(scalar)
            if (scalar in 9..13 || scalar == 32) continue
            if (scalar in 0x21..0x7e && scalar != 0x23 && scalar != 0x2a && scalar !in 0x30..0x39) return false
            bytes += when { scalar < 0x80 -> 1; scalar < 0x800 -> 2; scalar < 0x10000 -> 3; else -> 4 }
            if (bytes > 192) return false
        }
        return bytes > 0
    }

    fun isEmojiOnly(text: String, unicode: Unicode = AndroidUnicode): Boolean {
        if (!mayBeEmojiOnly(text)) return false
        val trimmed = NativeChiefTextPolicy.trim(text)
        val clusters = unicode.clusters(trimmed).take(4).toList()
        if (clusters.isEmpty() || clusters.size > 3) return false
        return clusters.all { cluster ->
            val scalars = cluster.codePoints().toArray()
            scalars.any(unicode::presentation) || (scalars.size > 1 && unicode.emoji(scalars.first()))
        }
    }

    fun count(text: String, unicode: Unicode = AndroidUnicode) =
        unicode.clusters(text).count { !unicode.whitespace(it.codePointAt(0)) }

    /** Source emoji font is fixed logical points even at accessibility text sizes. */
    fun fontSize(text: String, unicode: Unicode = AndroidUnicode): Int? =
        if (!isEmojiOnly(text, unicode)) null else if (count(text, unicode) == 1) 72 else 48

    fun composerFontSize(text: String, unicode: Unicode = AndroidUnicode): Int? =
        if (text != NativeChiefTextPolicy.trim(text) || !isEmojiOnly(text, unicode)) null
        else if (unicode.clusters(text).count() == 1) 72 else 48

    fun verticalRoom(accessibility: Boolean) = 26.84 * if (accessibility) 1.2 else 1.0
}
