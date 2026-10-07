package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class AgentFeedMarkdownTest {
    @Test fun inlineFormattingPreservesWhitespaceAndLeavesBlockMarkersAsAuthored() {
        val runs = AgentFeedMarkdown.parse("  # Heading\n\n**bold** and *italic* plus `code`\n  tail  ")
        assertEquals("  # Heading\n\nbold and italic plus code\n  tail  ", runs.joinToString("") { it.text })
        assertTrue(runs.any { it.text == "bold" && it.style.bold })
        assertTrue(runs.any { it.text == "italic" && it.style.italic })
        assertTrue(runs.any { it.text == "code" && it.style.code })
        assertEquals("before  \n\t after", AgentFeedMarkdown.parse("before  \n\t after").joinToString("") { it.text })
        assertEquals("bold\n\n  still bold", AgentFeedMarkdown.parse("**bold\n\n  still bold**").joinToString("") { it.text })
    }
    @Test fun nestedStyleEscapesEntitiesAndCodeAreParsedWithoutRegexShortcuts() {
        val runs = AgentFeedMarkdown.parse("***both*** &amp; \\*literal\\* `**raw**`")
        assertEquals("both & *literal* **raw**", runs.joinToString("") { it.text })
        assertTrue(runs.any { it.text == "both" && it.style.bold && it.style.italic })
        assertTrue(runs.any { it.text == "**raw**" && it.style.code && !it.style.bold })
    }
    @Test fun strikethroughAndLinksCarryNativeAnnotationsAndUnsafeDestinationsStayInert() {
        val runs = AgentFeedMarkdown.parse("~~gone~~ [site](https://cmux.com) [unsafe](javascript:alert) ![alt](https://example.com/private.png)")
        assertEquals("gone site unsafe alt", runs.joinToString("") { it.text })
        assertTrue(runs.any { it.text == "gone" && it.style.strike })
        assertTrue(runs.any { it.text == "site" && it.style.link == "https://cmux.com" })
        assertTrue(runs.none { it.style.link?.startsWith("javascript:") == true || it.style.link?.contains("private.png") == true })
    }
    @Test fun incompleteMarkdownRemainsReadableAndCacheDoesNotLoseStyle() {
        val first = AgentFeedMarkdown.parse("**unfinished _and_ `text")
        assertTrue(first.joinToString("") { it.text }.startsWith("**unfinished"))
        assertEquals(first, AgentFeedMarkdown.parse("**unfinished _and_ `text"))
        assertEquals(" \n\n ", AgentFeedMarkdown.parse(" \n\n ").joinToString("") { it.text })
    }
}
