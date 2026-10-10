package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException

class RtfPreviewBudgetTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun bytes(text: String) = text.toByteArray(Charsets.ISO_8859_1)
    private fun validate(text: String, limits: RtfPreviewLimits = RtfPreviewLimits()) = RtfPreviewBudget.validate(bytes(text), limits)
    private fun reject(text: String, limits: RtfPreviewLimits = RtfPreviewLimits()) = assertThrows(IllegalStateException::class.java) { validate(text, limits) }

    @Test fun realAuthoredFixturePassesStructuralBudget() {
        RtfPreviewBudget.validate(File("src/androidTest/assets/rtf/rich.rtf").readBytes())
    }
    @Test fun escapedBracesHexUnicodeAndBinaryAreNotInterpretedAsGroups() {
        validate("{\\rtf1 \\{literal\\} \\'e9 \\u26085?}")
        validate("{\\rtf1{\\pict\\bin5 }{\\xy}}")
        validate("{\\rtf1{\\pict\\bin0 }}\r\n\t")
    }
    @Test fun wrongVersionMissingRootMalformedEscapesAndUnbalancedGroupsFail() {
        for (text in listOf("plain text", "{\\rtf10 text}", "{\\rtf2 text}", "{\\rtf1 text", "{\\rtf1}}",
            "{\\rtf1}{\\rtf1}", "{\\rtf1\\'gg}", "{\\rtf1\\'e}", "{\\rtf1\\")) reject(text)
    }
    @Test fun negativeTruncatedAndOversizedBinaryLengthsFail() {
        for (text in listOf("{\\rtf1{\\pict\\bin-1 x}}", "{\\rtf1{\\pict\\bin100 x}}", "{\\rtf1\\bin99999999999999 x}")) reject(text)
        reject("{\\rtf1{\\pict\\bin4 abcd}}", RtfPreviewLimits(binaryBytes = 3))
        reject("{\\rtf1{\\pict\\bin2 ab}{\\pict\\bin2 cd}}", RtfPreviewLimits(binaryBytes = 3))
    }
    @Test fun fileDepthGroupControlAndPictureBudgetsRejectBeforeDecode() {
        reject("{\\rtf1 text}", RtfPreviewLimits(bytes = 8))
        reject("{\\rtf1{{text}}}", RtfPreviewLimits(depth = 2))
        reject("{\\rtf1{}{}{}}", RtfPreviewLimits(groups = 3))
        reject("{\\rtf1\\b\\i text}", RtfPreviewLimits(controls = 2))
        reject("{\\rtf1{\\pict}{\\pict}}", RtfPreviewLimits(pictures = 1))
    }
    @Test fun hugeFontImageAndNumericParametersDoNotReachLayout() {
        for (control in listOf("fs2147483647", "f2147483647", "picw16385", "pich-1", "picwgoal245761", "pichgoal9999999999", "b-", "b999999999999"))
            reject("{\\rtf1\\$control text}")
        validate("{\\rtf1\\fs24\\f0\\u-32768?}")
    }
    @Test fun ownedSnapshotIsIndependentAndClosesItsDirectory() {
        val source = temporary.newFile().apply { writeBytes(bytes("{\\rtf1 Owned preview}")) }
        val root = temporary.newFolder()
        val snapshot = OfficePreviewPackage.prepareRichText(source, root)
        try {
            source.writeText("changed")
            assertEquals("document.rtf", snapshot.file.name)
            assertEquals("{\\rtf1 Owned preview}", snapshot.file.readText())
        } finally { snapshot.close() }
        assertFalse(snapshot.file.exists())
        assertTrue(root.listFiles().orEmpty().none { it.isDirectory })
    }
    @Test fun invalidAndCancelledSnapshotsAreCleanedWithoutChangingOriginal() {
        val source = temporary.newFile().apply { writeText("not rtf") }
        val root = temporary.newFolder()
        assertThrows(IllegalStateException::class.java) { OfficePreviewPackage.prepareRichText(source, root) }
        assertEquals("not rtf", source.readText())
        source.writeText("{\\rtf1 " + "a".repeat(50_000) + "}")
        var calls = 0
        assertThrows(CancellationException::class.java) {
            OfficePreviewPackage.prepareRichText(source, root) { if (++calls == 4) throw CancellationException() }
        }
        assertTrue(root.listFiles().orEmpty().none { it.isDirectory })
    }
    @Test fun scannerChecksCancellationInsideTextAndAfterBinarySkips() {
        var calls = 0
        assertThrows(CancellationException::class.java) {
            RtfPreviewBudget.validate(bytes("{\\rtf1 " + "a".repeat(20_000) + "}")) { if (++calls == 2) throw CancellationException() }
        }
    }
    @Test fun routingMatchesRtfMimeAndExtensionWhilePreservingIosWirePrecedence() {
        assertEquals(ChangesPreviewRoute.RICH_TEXT, filePreviewRoute("binary", null, "/work/Notes.RTF"))
        for (mime in listOf("application/rtf", "text/rtf; charset=ascii", "application/x-rtf"))
            assertEquals(ChangesPreviewRoute.RICH_TEXT, filePreviewRoute("binary", mime, "download"))
        assertEquals(ChangesPreviewRoute.TEXT, filePreviewRoute("text", "text/rtf", "notes.rtf"))
        assertEquals(ChangesPreviewRoute.IMAGE, filePreviewRoute("image", null, "notes.rtf"))
        assertEquals(ChangesPreviewRoute.PDF, filePreviewRoute("binary", "application/pdf", "notes.rtf"))
        assertEquals(ChangesPreviewRoute.EXTERNAL, filePreviewRoute("binary", null, "/work.rtf/notes"))
    }
}
