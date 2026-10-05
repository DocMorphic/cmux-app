package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class PdfCompatibilityTextPageTest {
    private fun page(text: String) = PdfCompatibilityTextPage(text, text.indices.filter { !text[it].isWhitespace() }
        .map { PdfTextRun(it, it + 1, listOf(PdfTextBounds(it * 10f, 0f, it * 10f + 9f, 10f))) })

    @Test fun phrasesCrossInferredLineBreaksWithoutLosingOriginalOffsets() {
        val value = page("first\n  second needle\nNEEDLE")
        val phrase = value.search(2, "FIRST second").single()
        assertEquals(2, phrase.page); assertEquals(0, phrase.start)
        assertEquals(11, phrase.bounds.size)
        assertEquals(listOf(15, 22), value.search(2, "needle").map { it.start })
        assertTrue(value.search(2, " \n ").isEmpty())
    }
    @Test fun noSelectableBoundsCannotCreatePhantomSearchMatches() {
        val value = PdfCompatibilityTextPage("hidden", listOf(PdfTextRun(0, 6, emptyList())))
        assertTrue(value.search(0, "hidden").isEmpty()); assertNull(value.word(0f, 0f))
    }
    @Test fun wordLookupUsesUnicodeBoundariesAndRejectsOutsidePoints() {
        val value = page("hello, café!")
        assertEquals("hello", value.word(21f, 5f))
        assertEquals("café", value.word(91f, 5f))
        assertEquals(",", value.word(51f, 5f))
        assertNull(value.word(500f, 5f)); assertNull(value.word(Float.NaN, 0f))
    }
    @Test fun normalizedLigaturesAndBidiWordsKeepTheirWholeGlyphBounds() {
        val bounds = listOf(PdfTextBounds(0f, 0f, 20f, 10f), PdfTextBounds(20f, 0f, 40f, 10f))
        val value = PdfCompatibilityTextPage("office שלום", listOf(PdfTextRun(0, 6, bounds), PdfTextRun(7, 11, bounds)))
        assertEquals(bounds, value.search(0, "ffi").single().bounds)
        assertEquals(7, value.search(0, "שלום").single().start)
    }
    @Test fun supplementaryCharactersDoNotShiftLaterUtf16Matches() {
        val value = page("😀 needle")
        assertEquals(3, value.search(0, "needle").single().start)
        assertEquals("needle", value.word(41f, 5f))
    }
    @Test fun largeRepeatedPageSearchIsBounded() {
        val value = page("needle ".repeat(3000))
        assertEquals(3000, value.search(0, "needle").size)
        assertThrows(IllegalStateException::class.java) { page("a ".repeat(10_001)).search(0, "a") }
    }
}
