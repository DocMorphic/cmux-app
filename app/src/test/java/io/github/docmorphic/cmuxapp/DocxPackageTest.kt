package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class DocxPackageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val parts get() = linkedMapOf(
        "[Content_Types].xml" to "<Types/>", "_rels/.rels" to "<Relationships/>",
        "word/document.xml" to "<document><p>Résumé &amp; 日本語</p></document>",
    )
    private fun source(values: Map<String, String> = parts): File = temporary.newFile().apply {
        ZipOutputStream(outputStream()).use { zip -> values.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }
    private fun rejected(values: Map<String, String> = parts, limits: DocxLimits = DocxLimits()) {
        val root = temporary.newFolder()
        assertThrows(Exception::class.java) { DocxPackage.prepare(source(values), root, limits) }
        assertTrue(root.walkTopDown().none { it.name == "document.docx" })
        assertTrue(root.listFiles().orEmpty().none { it.isDirectory && it.name.matches(Regex("[a-f0-9-]{36}")) })
    }
    @Test fun ownedSnapshotPreservesPartsWithVerifiedStoredLengthsAndSurvivesSourceRemoval() {
        val root = temporary.newFolder(); val original = source()
        val prepared = DocxPackage.prepare(original, root)
        try {
            assertTrue(original.delete())
            ZipFile(prepared.file).use { zip -> parts.forEach { (name, text) ->
                val entry = zip.getEntry(name)
                assertEquals(ZipEntry.STORED, entry.method)
                assertEquals(text, zip.getInputStream(entry).bufferedReader().readText())
                assertEquals(text.toByteArray().size.toLong(), entry.size)
            } }
        } finally { prepared.close() }
        assertFalse(prepared.file.exists())
    }
    @Test fun compressedBombTotalAndIndividualPartLimitsAreEnforcedAndCleaned() {
        val inflated = parts + ("word/media/image.bin" to "x".repeat(50_000))
        rejected(inflated, DocxLimits(expandedBytes = 1024))
        rejected(inflated, DocxLimits(entryBytes = 1024))
        rejected(parts, DocxLimits(archiveBytes = 1))
        rejected(parts, DocxLimits(entries = 2))
        rejected(parts, DocxLimits(xmlBytes = 8))
    }
    @Test fun xmlDtdsExternalEntitiesMalformedMarkupAndComplexityAreRejected() {
        for (xml in listOf(
            "<!DOCTYPE doc [<!ENTITY a 'expanded'>]><doc>&a;</doc>",
            "<!DOCTYPE doc SYSTEM 'file:///not-readable-by-a-preview'><doc/>",
            "<doc><unclosed></doc>",
        )) rejected(parts + ("word/document.xml" to xml))
        rejected(parts, DocxLimits(depth = 1))
        rejected(parts, DocxLimits(elements = 3))
    }
    @Test fun traversalAbsoluteAndAmbiguousNamesAndNonWordArchivesAreRejected() {
        for (name in listOf("../escape", "/absolute", "word/../alias", "word//alias", "word\\alias", "word/./alias"))
            rejected(parts + (name to "unsafe"))
        rejected(mapOf("readme.txt" to "not a document"))
    }
    @Test fun cancellationDuringExpansionDeletesTheOwnedSnapshot() {
        val root = temporary.newFolder(); var checks = 0
        assertThrows(CancellationException::class.java) {
            DocxPackage.prepare(source(parts + ("word/media/image.bin" to "x".repeat(50_000))), root) {
                if (++checks == 7) throw CancellationException()
            }
        }
        assertTrue(root.walkTopDown().none { it.name == "document.docx" })
    }
    @Test fun docxRoutingRespectsWireImagesTextAndExistingPdfRoutes() {
        assertEquals(ChangesPreviewRoute.DOCX, filePreviewRoute("binary", null, "/work/Report.DOCX"))
        assertEquals(ChangesPreviewRoute.DOCX, filePreviewRoute("binary", "${DocxPreviewPolicy.MIME}; charset=utf-8", "download"))
        assertEquals(ChangesPreviewRoute.IMAGE, filePreviewRoute("image", null, "report.docx"))
        assertEquals(ChangesPreviewRoute.TEXT, filePreviewRoute("text", null, "report.docx"))
        assertEquals(ChangesPreviewRoute.PDF, filePreviewRoute("binary", "application/pdf", "report.docx"))
        assertEquals(ChangesPreviewRoute.EXTERNAL, filePreviewRoute("binary", "application/msword", "report.doc"))
        assertEquals(ChangesPreviewRoute.EXTERNAL, filePreviewRoute("binary", null, "/work.docx/report"))
    }
}
