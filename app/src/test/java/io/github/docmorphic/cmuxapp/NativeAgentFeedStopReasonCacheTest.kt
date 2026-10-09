package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeAgentFeedStopReasonCacheTest {
    @Test fun unchangedSnapshotsReuseNormalizationAndRemovedReasonsAreDiscarded() {
        val normalized = mutableListOf<String>()
        val cache = NativeAgentFeedStopReasonCache { normalized += it; normalizeAgentFeedStopReason(it) }
        val preview = "Completed\nthis…"; val full = "Completed this task"
        repeat(100) {
            cache.retain(setOf(preview, full))
            assertTrue(cache.matches(preview, full))
        }
        assertEquals(listOf(preview, full), normalized)
        cache.retain(setOf(preview, "A different result"))
        assertFalse(cache.matches(preview, "A different result"))
        assertEquals(3, normalized.size)
        cache.retain(emptySet())
        assertTrue(cache.matches(preview, full))
        assertEquals(5, normalized.size)
    }

    @Test fun unicodeWhitespaceTruncationAndCompleteResponsesMatchIosCases() {
        val cases = listOf(
            Triple("Done\twith\nthis", "Done with this", true),
            Triple("Done\u00a0with\u2003this…", "Done with this task", true),
            Triple("Done", "Done with this task", false),
            Triple("Done with A", "Done with B", false),
            Triple("👨‍👩‍👧‍👦 Fixed…", "👨‍👩‍👧‍👦 Fixed the feed", true),
            Triple("  \n", "\t", false),
            Triple("Done…", "Done", false)
        )
        val cache = NativeAgentFeedStopReasonCache()
        for ((first, second, expected) in cases) {
            assertEquals(first, expected, cache.matches(first, second))
            assertEquals(second, expected, cache.matches(second, first))
        }
        assertFalse(cache.matches(null, "Done")); assertFalse(cache.matches("Done", null))
    }

    @Test fun whitespaceNormalizationPreservesAuthoredNonspaceContent() {
        val spaces = "\t\n\u000b\u000c\r \u0085\u00a0\u1680" +
            (0x2000..0x200a).map { it.toChar() }.joinToString("") + "\u2028\u2029\u202f\u205f\u3000"
        assertEquals("Done with this", normalizeAgentFeedStopReason(spaces + "Done" + spaces + "with" + spaces + "this" + spaces))
        assertEquals("👩🏽‍💻 e\u0301\u200btext\u001c", normalizeAgentFeedStopReason("👩🏽‍💻 e\u0301\u200btext\u001c"))
        assertEquals("", normalizeAgentFeedStopReason(spaces))
    }
}
