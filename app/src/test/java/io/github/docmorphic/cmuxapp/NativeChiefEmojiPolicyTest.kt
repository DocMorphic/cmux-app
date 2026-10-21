package io.github.docmorphic.cmuxapp

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UProperty
import com.ibm.icu.text.BreakIterator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class NativeChiefEmojiPolicyTest {
    private val vectors = JSONObject(javaClass.getResourceAsStream("/parity/chief-emoji.json")!!.bufferedReader().use { it.readText() })
    private val unicode = object : NativeChiefEmojiPolicy.Unicode {
        override fun clusters(text: String) = sequence {
            val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
            var start = iterator.first(); var end = iterator.next()
            while (end != BreakIterator.DONE) {
                yield(text.substring(start, end)); start = end; end = iterator.next()
            }
        }
        override fun emoji(scalar: Int) = UCharacter.hasBinaryProperty(scalar, UProperty.EMOJI)
        override fun presentation(scalar: Int) = UCharacter.hasBinaryProperty(scalar, UProperty.EMOJI_PRESENTATION)
        override fun whitespace(scalar: Int) = UCharacter.isUWhiteSpace(scalar)
    }

    @Test fun classificationFontAndGraphemeCountsMatchActualPinnedSwift() {
        val cases = vectors.getJSONArray("cases")
        repeat(cases.length()) { index ->
            val row = cases.getJSONObject(index); val text = row.getString("text")
            assertEquals("prefilter $index", row.getBoolean("prefilter"), NativeChiefEmojiPolicy.mayBeEmojiOnly(text))
            assertEquals("emoji predicate $index", row.getBoolean("emojiOnly"), NativeChiefEmojiPolicy.isEmojiOnly(text, unicode))
            assertEquals("count $index", row.getInt("count"), NativeChiefEmojiPolicy.count(text, unicode))
            assertEquals("font $index", row.getInt("fontSize").takeIf { row.getBoolean("emojiOnly") }, NativeChiefEmojiPolicy.fontSize(text, unicode))
            assertEquals("composer $index", if (row.isNull("composerPointSize")) null else row.getInt("composerPointSize"), NativeChiefEmojiPolicy.composerFontSize(text, unicode))
            for ((key, input) in listOf("clusters" to text, "trimmedClusters" to row.getString("trimmed"))) {
                val expected = row.getJSONArray(key).let { array -> List(array.length()) { array.getString(it) } }
                assertEquals("grapheme boundaries $key $index", expected, unicode.clusters(input).toList())
            }
        }
    }

    @Test fun markedTextRetainsThePriorFontUntilCompositionFinishes() {
        val style = NativeChiefEmojiDraftStyle()
        assertEquals(72, style.update(false) { 72 })
        assertEquals(72, style.update(true) { error("Do not inspect or restyle marked text") })
        assertNull(style.update(false) { null })
        assertNull(style.update(true) { error("Do not inspect or restyle marked text") })
        assertEquals(48, style.update(false) { 48 })
    }

    @Test fun checkedUnicodePropertiesAgreeWithTheSwiftRuntime() {
        val scalars = vectors.getJSONArray("scalars")
        repeat(scalars.length()) { index ->
            val row = scalars.getJSONObject(index); val scalar = row.getInt("scalar")
            assertEquals("emoji U+${scalar.toString(16)}", row.getBoolean("emoji"), unicode.emoji(scalar))
            assertEquals("presentation U+${scalar.toString(16)}", row.getBoolean("presentation"), unicode.presentation(scalar))
            assertEquals("whitespace U+${scalar.toString(16)}", row.getBoolean("whitespace"), unicode.whitespace(scalar))
        }
    }
}
