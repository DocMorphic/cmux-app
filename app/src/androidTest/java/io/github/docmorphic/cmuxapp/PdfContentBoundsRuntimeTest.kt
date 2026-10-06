package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PdfContentBoundsRuntimeTest {
    private fun page(document: PDDocument, content: String): PDPage = PDPage(PDRectangle(300f, 400f)).apply {
        resources = PDResources()
        resources.put(COSName.getPDFName("F1"), PDType1Font.HELVETICA)
        setContents(PDStream(document).apply { createOutputStream().use { it.write(content.toByteArray(Charsets.US_ASCII)) } })
        document.addPage(this)
    }
    private fun bounds(page: PDPage) = PdfContentBounds(page).read(PdfPageCoordinates(0f, 0f, 300f, 400f, 0, 300, 400))
    private fun assertBounds(expected: PdfTextBounds, actual: PdfTextBounds?) {
        assertNotNull(actual)
        assertEquals(expected.left, actual!!.left, .05f); assertEquals(expected.top, actual.top, .05f)
        assertEquals(expected.right, actual.right, .05f); assertEquals(expected.bottom, actual.bottom, .05f)
    }
    @Test fun whiteVectorsImagesAndTextAllContributeToContentBounds() {
        PDDocument().use { document ->
            val page = page(document, "1 1 1 rg 40 80 20 30 re f q 30 0 0 40 190 180 cm /Im Do Q BT /F1 18 Tf 80 150 Td (CMUX) Tj ET")
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            try { page.resources.put(COSName.getPDFName("Im"), LosslessFactory.createFromImage(document, bitmap)) }
            finally { bitmap.recycle() }
            assertBounds(PdfTextBounds(40f, 180f, 220f, 320f), bounds(page))
            val textOnly = page(document, "BT /F1 18 Tf 80 150 Td (CMUX) Tj ET")
            val textBounds = checkNotNull(bounds(textOnly))
            assertTrue(textBounds.left in 79f..83f); assertTrue(textBounds.right in 120f..145f)
            assertTrue(textBounds.top in 230f..240f); assertTrue(textBounds.bottom in 249f..252f)
        }
    }
    @Test fun nestedFormsRespectClippingAndPreserveTheOuterStrokePath() {
        PDDocument().use { document ->
            val page = page(document, "q 100 100 50 60 re W n /Form Do Q 10 w 20 30 m /Form Do 80 30 l S")
            val form = PDFormXObject(document).apply {
                bBox = PDRectangle(100f, 100f, 100f, 100f)
                resources = PDResources()
                contentStream.createOutputStream().use { it.write("0 0 300 400 re f".toByteArray()) }
            }
            page.resources.put(COSName.getPDFName("Form"), form)
            // The second unclipped form paints [100,200]². The outer stroke remains [20,80]×[25,35].
            assertBounds(PdfTextBounds(20f, 200f, 200f, 375f), bounds(page))
            val clippedOnly = page(document, "q 100 100 50 60 re W n /Form Do Q")
            clippedOnly.resources.put(COSName.getPDFName("Form"), form)
            assertBounds(PdfTextBounds(100f, 240f, 150f, 300f), bounds(clippedOnly))
        }
    }
    @Test fun cropRotationBlankPagesAndBrokenStreamsDoNotYieldInventedBounds() {
        PDDocument().use { document ->
            val page = page(document, "30 40 100 160 re f")
            page.cropBox = PDRectangle(10f, 20f, 200f, 300f); page.rotation = 90
            assertBounds(PdfTextBounds(20f, 20f, 180f, 120f),
                PdfContentBounds(page).read(PdfPageCoordinates(10f, 20f, 200f, 300f, 90, 300, 200)))
            assertNull(bounds(page(document, "")))
            assertThrows(IOException::class.java) { bounds(page(document, "10 10 20 20 re f /Missing Do")) }
        }
    }
    @Test fun contentDestinationsResolveLazilyAndRetainTheirExplicitAxis() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "pdf-content-bounds.pdf")
        try {
            PDDocument().use { document -> page(document, "150 200 100 160 re f"); document.save(file) }
            ChangesPdfDocument(file).use { pdf ->
                for (fit in listOf(PdfDestinationFit.CONTENT, PdfDestinationFit.CONTENT_WIDTH, PdfDestinationFit.CONTENT_HEIGHT)) {
                    val requested = PdfLinkTarget.Page(0, 33f, 44f, fit = fit)
                    val resolved = pdf.resolveDestination(requested, 0, 0f, 0f)
                    assertEquals(33f, resolved.y, 0f); assertEquals(44f, resolved.x, 0f)
                    assertBounds(PdfTextBounds(150f, 40f, 250f, 200f), resolved.rectangle)
                    assertEquals(resolved, pdf.resolveDestination(requested, 0, 0f, 0f))
                }
            }
            PDDocument().use { document -> page(document, ""); document.save(file) }
            ChangesPdfDocument(file).use { pdf ->
                assertBounds(PdfTextBounds(0f, 0f, 300f, 400f),
                    pdf.resolveDestination(PdfLinkTarget.Page(0, 0f, fit = PdfDestinationFit.CONTENT), 0, 0f, 0f).rectangle)
            }
        } finally { file.delete() }
    }
}
