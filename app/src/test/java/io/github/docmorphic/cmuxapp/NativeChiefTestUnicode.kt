package io.github.docmorphic.cmuxapp

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UProperty
import com.ibm.icu.text.BreakIterator
import java.util.Locale

/** JVM ICU adapter checked against the pinned Foundation emoji/grapheme corpus. */
internal object NativeChiefTestUnicode : NativeChiefEmojiPolicy.Unicode {
    override fun clusters(text: String) = sequence {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        var start = iterator.first(); var end = iterator.next()
        while (end != BreakIterator.DONE) { yield(text.substring(start, end)); start = end; end = iterator.next() }
    }
    override fun emoji(scalar: Int) = UCharacter.hasBinaryProperty(scalar, UProperty.EMOJI)
    override fun presentation(scalar: Int) = UCharacter.hasBinaryProperty(scalar, UProperty.EMOJI_PRESENTATION)
    override fun whitespace(scalar: Int) = UCharacter.isUWhiteSpace(scalar)
}
