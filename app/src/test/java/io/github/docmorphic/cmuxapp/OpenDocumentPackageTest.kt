package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class OpenDocumentPackageTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun source(body: String, mime: String = WorkbookPreviewPolicy.ODS_MIME): File = temporary.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            val parts = mapOf("mimetype" to mime, "META-INF/manifest.xml" to "<manifest/>", "content.xml" to """
                <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                    xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
                    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
                <office:body><office:spreadsheet><table:table table:name="Test">$body</table:table></office:spreadsheet></office:body>
                </office:document-content>
            """.trimIndent())
            for ((name, content) in parts) { zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
        }
    }
    private fun reject(body: String, limits: OfficePackageLimits = OfficePackageLimits()) {
        val root = temporary.newFolder()
        assertThrows(Exception::class.java) { OfficePreviewPackage.prepare(source(body), root, limits) }
        assertFalse(root.walkTopDown().any { it.name == "document.zip" })
    }
    @Test fun odsFixtureHasOwnedVerifiedContentsAndCleanup() {
        for (name in listOf("open-document.ods", "open-document-styled.ods")) {
        val original = File("src/androidTest/assets/workbook/$name")
        val prepared = OfficePreviewPackage.prepare(original, temporary.newFolder())
        val snapshot = prepared.file
        prepared.use {
            ZipFile(original).use { from -> ZipFile(snapshot).use { to ->
                for (entry in from.entries()) {
                    val copy = to.getEntry(entry.name)
                    assertEquals(ZipEntry.STORED, copy.method)
                    assertArrayEquals(from.getInputStream(entry).readBytes(), to.getInputStream(copy).readBytes())
                }
            } }
        }
        assertFalse(snapshot.exists())
        }
    }
    @Test fun repeatedPopulatedCellsAndTextHaveIndependentBudgets() {
        reject("""<table:table-row table:number-rows-repeated="1000"><table:table-cell table:number-columns-repeated="1000"><text:p>x</text:p></table:table-cell></table:table-row>""")
        reject("""<table:table-row table:number-rows-repeated="100"><table:table-cell table:number-columns-repeated="100"><text:p><text:s text:c="100000"/></text:p></table:table-cell></table:table-row>""")
        reject("""<table:table-row><table:table-cell><text:p><text:s text:c="99999999999999999999"/></text:p></table:table-cell></table:table-row>""")
        reject("""<table:table-row table:number-rows-repeated="1000"><table:table-cell table:formula="${"x".repeat(2000)}"/></table:table-row>""", OfficePackageLimits(expandedBytes = 100_000))
    }
    @Test fun blankRepeatedTailCoordinatesDoNotAllocateCells() {
        OfficePreviewPackage.prepare(source("""<table:table-row table:number-rows-repeated="1048576"><table:table-cell table:number-columns-repeated="16384"/></table:table-row>"""), temporary.newFolder()).close()
        reject("""<table:table-row table:number-rows-repeated="1048576"/><table:table-row/>""")
        reject("""<table:table-row><table:table-cell table:number-columns-repeated="16384"/><table:table-cell/></table:table-row>""")
    }
    @Test fun invalidRepeatCountsAndSpansAreRejectedBeforeDecoder() {
        for (count in listOf("0", "-1", "Infinity", "1e6", "1.5", "99999999999999999999"))
            reject("""<table:table-row table:number-rows-repeated="$count"/>""")
        reject("""<table:table-row><table:table-cell table:number-columns-spanned="16385"/></table:table-row>""")
        reject("""<table:行 table:number-rows-repeated="999999999"/>""")
        reject("""<table:table-row table:行号="999999999"/>""")
        reject("""<table:table-row><table:table-cell xmlns:other="urn:other" table:number-columns-repeated="1" other:number-columns-repeated="999999999"/></table:table-row>""")
    }
    @Test fun onlySpreadsheetMimeIsAdmittedWithoutOoxmlParts() {
        assertThrows(Exception::class.java) {
            OfficePreviewPackage.prepare(source("<table:table-row/>", "application/vnd.oasis.opendocument.text"), temporary.newFolder())
        }
    }
    @Test fun odsRoutingRetainsExistingWireAndMimePrecedence() {
        assertEquals(ChangesPreviewRoute.WORKBOOK, filePreviewRoute("binary", null, "/work/BOOK.ODS"))
        assertEquals(ChangesPreviewRoute.WORKBOOK, filePreviewRoute("binary", WorkbookPreviewPolicy.ODS_MIME + "; charset=utf-8", "download"))
        assertEquals(ChangesPreviewRoute.TEXT, filePreviewRoute("text", null, "book.ods"))
        assertEquals(ChangesPreviewRoute.IMAGE, filePreviewRoute("image", null, "book.ods"))
        assertEquals(ChangesPreviewRoute.PDF, filePreviewRoute("binary", "application/pdf", "book.ods"))
        assertEquals(ChangesPreviewRoute.EXTERNAL, filePreviewRoute("binary", null, "book.odt"))
    }
}
