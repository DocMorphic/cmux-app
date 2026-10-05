package io.github.docmorphic.cmuxapp

import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ArtifactPdfTextRuntimeTest {
    /** Original two-page PDF with text plus external and internal annotations. */
    private fun fixture(form: String = "direct", sourceRotation: Int = 0, targetHeight: Int = 400, crop: Boolean = false): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = listOf("CMUX first needle", "CMUX second needle")
        val streams = text.map { "BT /F1 18 Tf 30 340 Td ($it) Tj ET" }
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R /Names << /Dests << /Names [(target) [4 0 R /XYZ 0 400 0]] >> >> >>",
            "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] ${if (crop) "/CropBox [10 20 290 380]" else ""} /Rotate $sourceRotation /Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R /Annots [8 0 R 9 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 $targetHeight] /Resources << /Font << /F1 5 0 R >> >> /Contents 7 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length ${streams[0].length} >>\nstream\n${streams[0]}\nendstream",
            "<< /Length ${streams[1].length} >>\nstream\n${streams[1]}\nendstream",
            "<< /Type /Annot /Subtype /Link /Rect [30 250 180 280] /A << /S /URI /URI (https://cmux.com/docs) >> >>",
            "<< /Type /Annot /Subtype /Link /Rect [30 200 180 230] " + when (form) {
                "named" -> "/Dest (target)"
                "action" -> "/A << /S /GoTo /D [4 0 R /XYZ 0 400 0] >>"
                else -> "/Dest [4 0 R /XYZ 0 400 0]"
            } + " >>")
        val output = ByteArrayOutputStream()
        fun write(value: String) { output.write(value.toByteArray(Charsets.US_ASCII)) }
        write("%PDF-1.4\n"); val offsets = mutableListOf<Int>()
        objects.forEachIndexed { index, value -> offsets += output.size(); write("${index + 1} 0 obj\n$value\nendobj\n") }
        val xref = output.size(); write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(java.util.Locale.US, it)) }
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return File(context.cacheDir, "pdf-text-fixture.pdf").apply { writeBytes(output.toByteArray()) }
    }
    @Test fun nativePdfTextSearchLinksAndWordSelectionUseDocumentCoordinates() {
        check(Build.VERSION.SDK_INT >= 35)
        val file = fixture()
        try { ChangesPdfDocument(file).use { pdf ->
            assertEquals(2, pdf.pageSizes.size)
            assertTrue(pdf.text(0).contains("CMUX first needle"))
            val match = pdf.search(1, "needle").single()
            assertEquals(1, match.page); assertTrue(match.bounds.isNotEmpty())
            val bounds = match.bounds.first()
            val word = pdf.word(1, (bounds.left + bounds.right) / 600f, (bounds.top + bounds.bottom) / 800f)
            assertEquals("needle", word?.trim())
            val links = pdf.links(0)
            assertTrue(links.any { it.target == PdfLinkTarget.External("https://cmux.com/docs") })
            assertTrue(links.any { (it.target as? PdfLinkTarget.Page)?.index == 1 })
        } } finally { file.delete() }
    }
    @Test fun compatibilityTextSearchAndWordSelectionRespectCropAndAllPageRotations() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val file = fixture(sourceRotation = rotation, crop = true)
            try { ChangesPdfDocument(file, nativeText = false).use { pdf ->
                assertTrue(pdf.supportsText)
                assertTrue(pdf.text(0).contains("CMUX first needle"))
                val match = pdf.search(0, "NEEDLE").single()
                val (width, height) = pdf.pageSizes[0]
                assertTrue(match.bounds.all { it.left >= 0 && it.top >= 0 && it.right <= width && it.bottom <= height })
                val first = match.bounds.first()
                assertEquals("needle", pdf.word(0, (first.left + first.right) / (2 * width), (first.top + first.bottom) / (2 * height)))
                assertEquals(1, pdf.search(0, "first needle").size)
                assertNull(pdf.word(0, Float.NaN, .5f)); assertNull(pdf.word(0, -1f, .5f))
                assertTrue(pdf.links(0).any { it.target is PdfLinkTarget.Page })
                // Rendering remains the real platform path when compatibility text is selected.
                pdf.render(0, 300).recycle()
            } } finally { file.delete() }
        }
    }
    @Test fun compatibilityTextCanBeReopenedAndIsRetiredWithItsDocument() {
        val file = fixture()
        try {
            val first = ChangesPdfDocument(file, nativeText = false)
            val text = first.text(1); val matches = first.search(1, "needle")
            first.close()
            assertThrows(IllegalStateException::class.java) { first.text(1) }
            ChangesPdfDocument(file, nativeText = false).use { reopened ->
                assertEquals(text, reopened.text(1)); assertEquals(matches, reopened.search(1, "needle"))
            }
        } finally { file.delete() }
    }
    @Test fun directNamedAndActionDestinationsUseTargetPageAndRotatedSourceCoordinates() {
        for (form in listOf("direct", "named", "action")) {
            val file = fixture(form, sourceRotation = 90, targetHeight = 600)
            try { ChangesPdfDocument(file).use { pdf ->
                val link = pdf.links(0).single { it.target is PdfLinkTarget.Page }
                assertEquals(PdfLinkTarget.Page(1, 200f), link.target)
                assertEquals(PdfTextBounds(200f, 30f, 230f, 180f), link.bounds.single())
                assertFalse(pdf.incompleteLinks)
            } } finally { file.delete() }
        }
    }
    @Test fun searchNavigatesHighlightsAndPageTextCopiesAfterRecreation() {
        check(Build.VERSION.SDK_INT >= 35 && (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")))
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation); val file = fixture()
        fun find(selector: BySelector) = checkNotNull(device.wait(Until.findObject(selector), 10_000))
        val captures = File(context.getExternalFilesDir(null), "pdf-text").apply { mkdirs() }
        try { ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
            .putExtra("path", file.absolutePath).putExtra("route", ChangesPreviewRoute.PDF.name).putExtra("mime", "application/pdf")).use { scenario ->
            // Exercise the actual document link action before opening search.
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            var followed = false
            while (!followed && android.os.SystemClock.uptimeMillis() < deadline) {
                fun follow(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    node.actionList.firstOrNull { it.label?.toString() == "Go to page 2" }?.let { return node.performAction(it.id) }
                    return (0 until node.childCount).any { follow(node.getChild(it)) }
                }
                followed = follow(instrumentation.uiAutomation.rootInActiveWindow)
                if (!followed) android.os.SystemClock.sleep(100)
            }
            assertTrue("Internal page link action unavailable", followed)
            find(By.desc("PDF page 2 of 2"))
            find(By.text("Previous page")).click()
            find(By.desc("PDF page 1 of 2"))
            find(By.text("Search document")).click(); find(By.clazz("android.widget.EditText")).text = "needle"
            assertTrue(find(By.desc("PDF search results")).wait(Until.textEquals("1 / 2"), 10_000))
            find(By.text("Next match")).click(); find(By.desc("PDF page 2 of 2"))
            val screenshot = File(captures, "second-match.png")
            device.takeScreenshot(screenshot)
            val bitmap = android.graphics.BitmapFactory.decodeFile(screenshot.path)
            try {
                var highlighted = 0
                for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                    val color = bitmap.getPixel(x, y)
                    if (android.graphics.Color.red(color) > 240 && android.graphics.Color.green(color) in 140..215 &&
                        android.graphics.Color.blue(color) < 160) highlighted++
                }
                assertTrue("Selected match has no visible highlight", highlighted > 50)
            } finally { bitmap.recycle() }
            scenario.recreate(); find(By.text("Close search")); find(By.desc("PDF page 2 of 2"))
            find(By.text("Page text")).click(); find(By.text("CMUX second needle"))
            device.takeScreenshot(File(captures, "page-text.png"))
            find(By.text("Copy")).click()
            scenario.onActivity {
                assertTrue(context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString().contains("CMUX second needle"))
            }
        } } finally { file.delete() }
    }
}
