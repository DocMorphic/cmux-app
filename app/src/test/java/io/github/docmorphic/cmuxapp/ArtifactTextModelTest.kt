package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class ArtifactTextModelTest {
    @Test fun utf16LinesIncludeEmptyTrailingLineAndClampJumps() {
        val document = ArtifactTextDocument("🙂first\r\n\nlast\n")
        assertArrayEquals(intArrayOf(0, 9, 10, 15), document.starts)
        assertEquals(4, document.lineCount)
        assertEquals(0, document.offset(-10)); assertEquals(15, document.offset(999))
        assertEquals(1, document.line(8)); assertEquals(2, document.line(9))
        assertEquals(3, document.line(14)); assertEquals(4, document.line(999))
        assertEquals(1, ArtifactTextDocument("").lineCount)
    }
    @Test fun literalCaseInsensitiveSearchUsesNonOverlappingUtf16Ranges() {
        val document = ArtifactTextDocument("🙂AbA aba\nA.B a.b\naba")
        assertEquals(listOf(2..4, 6..8, 18..20), document.search("ABA"))
        assertEquals(listOf(10..12, 14..16), document.search("a.b"))
        assertEquals(listOf(6..10), document.search("aba\na"))
        assertEquals(listOf(0..1, 2..3), ArtifactTextDocument("aaaaa").search("aa"))
        assertTrue(document.search("").isEmpty()); assertTrue(document.search("missing").isEmpty())
    }
    @Test fun searchCanBeCancelledDuringLargeResultCollection() {
        var visits = 0
        assertThrows(InterruptedException::class.java) {
            ArtifactTextDocument("x".repeat(100_000)).search("x") { if (++visits == 100) throw InterruptedException() }
        }
        assertEquals(100, visits)
    }
    @Test fun layoutBucketsMatchUpstreamIncludingHaskellAndLogDefaults() {
        listOf("code.hs", "code.purs", "Main.KT", "README.md").forEach { assertEquals(ArtifactTextKind.CODE, ArtifactTextKind.forPath(it)) }
        listOf("output.log", "output.OUT").forEach { assertEquals(ArtifactTextKind.LOG, ArtifactTextKind.forPath(it)) }
        assertEquals(ArtifactTextKind.PLAIN, ArtifactTextKind.forPath("README"))
        assertFalse(ArtifactTextKind.LOG.defaultWrap); assertTrue(ArtifactTextKind.CODE.defaultWrap); assertTrue(ArtifactTextKind.PLAIN.defaultWrap)
    }
    @Test fun fontLimitsCannotPersistInvalidOrUnboundedSizes() {
        assertEquals(8f, ArtifactTextKind.fontSize(-1f)); assertEquals(28f, ArtifactTextKind.fontSize(100f))
        assertEquals(15f, ArtifactTextKind.fontSize(Float.NaN)); assertEquals(15f, ArtifactTextKind.fontSize(Float.POSITIVE_INFINITY))
        assertEquals(18.5f, ArtifactTextKind.fontSize(18.5f))
    }
}
