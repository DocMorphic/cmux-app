package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class OpenSourceLicensesTest {
    @Test fun sectionsPreserveAllTextAndBoundLongLinesWithoutSplittingSurrogates() {
        val documents = listOf("", "\n\n", "License\r\n\nAll rights reserved.\n",
            "a".repeat(2047) + "😀" + "界".repeat(9000), "short line\n".repeat(3000))
        documents.forEach { source ->
            val sections = licenseSections(source)
            assertEquals(source, sections.joinToString(""))
            assertTrue(sections.all { it.isNotEmpty() && it.length <= 2048 })
            assertTrue(sections.none { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
        }
    }
}
