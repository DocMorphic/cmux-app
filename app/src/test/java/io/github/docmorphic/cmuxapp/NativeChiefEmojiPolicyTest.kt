package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeChiefEmojiPolicyTest {
    private val vectors = JSONObject(javaClass.getResourceAsStream("/parity/chief-emoji.json")!!.bufferedReader().use { it.readText() })
    private val unicode = NativeChiefTestUnicode

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
        val editor = NativeChiefRichEditing(emojiFont = { NativeChiefEmojiPolicy.composerFontSize(it, unicode) })
        editor.commitText("🙂"); assertEquals(72.0, editor.typography.textPoints, 0.0)
        editor.select(androidx.compose.ui.text.TextRange(0, 2))
        editor.commitText("body", composing = true)
        assertEquals(72.0, editor.typography.textPoints, 0.0)
        editor.composition(null)
        assertEquals(17.0, editor.typography.textPoints, 0.0); assertNull(editor.typography.emojiSize)
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
