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
    private fun fixture(form: String = "direct", sourceRotation: Int = 0, targetHeight: Int = 400, crop: Boolean = false, targetX: Int = 0, targetZoom: Float = 0f, targetDestination: String? = null, targetRotation: Int = 0, filledRectangle: Boolean = false, targetText: Boolean = true, detailPattern: Boolean = false): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = listOf("CMUX first needle", "CMUX second needle")
        val streams = text.mapIndexed { index, value -> (if (index == 0 || targetText) "BT /F1 18 Tf 30 340 Td ($value) Tj ET" else "") +
            if (index == 1 && detailPattern) buildString {
                append(" q 0 0 1 rg 150 350 40 40 re f 1 1 1 rg 151 351 38 38 re f 0 0 0 rg")
                repeat(95) { append(" %.2f 352 0.2 36 re f".format(java.util.Locale.US, 151.0 + it * .4)) }
                append(" Q")
            } else if (index == 1 && filledRectangle) " q 0 1 0 rg 150 200 100 200 re f Q"
            else if (index == 1 && targetZoom > 0f) " q 0 1 0 rg $targetX 370 20 30 re f Q" else "" }
        val destination = targetDestination ?: "/XYZ $targetX 400 $targetZoom"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R /Names << /Dests << /Names [(target) [4 0 R $destination]] >> >> >>",
            "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] ${if (crop) "/CropBox [10 20 290 380]" else ""} /Rotate $sourceRotation /Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R /Annots [8 0 R 9 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 $targetHeight] /Rotate $targetRotation /Resources << /Font << /F1 5 0 R >> >> /Contents 7 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length ${streams[0].length} >>\nstream\n${streams[0]}\nendstream",
            "<< /Length ${streams[1].length} >>\nstream\n${streams[1]}\nendstream",
            "<< /Type /Annot /Subtype /Link /Rect [30 250 180 280] /A << /S /URI /URI (https://cmux.com/docs) >> >>",
            "<< /Type /Annot /Subtype /Link /Rect [30 200 180 230] " + when (form) {
                "named" -> "/Dest (target)"
                "action" -> "/A << /S /GoTo /D [4 0 R $destination] >>"
                else -> "/Dest [4 0 R $destination]"
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
    @Test fun selectionAcrossPagesUsesReadingOrderAndRejectsRetiredDocuments() {
        val file = fixture()
        try {
            val pdf = ChangesPdfDocument(file)
            val first = pdf.selectionText(0); val second = pdf.selectionText(1)
            val selection = PdfTextSelection(PdfTextPosition(0, first.text.indexOf("first")),
                PdfTextPosition(1, second.text.indexOf("needle") + "needle".length))
            val expected = "first needle\nCMUX second needle"
            assertEquals(expected, pdf.selectedText(selection).trim())
            assertEquals(expected, pdf.selectedText(PdfTextSelection(selection.focus, selection.anchor)).trim())
            assertTrue(first.selectionBounds(selection, 0).isNotEmpty())
            assertTrue(second.selectionBounds(selection, 1).isNotEmpty())
            pdf.close()
            assertThrows(IllegalStateException::class.java) { pdf.selectedText(selection) }
        } finally { file.delete() }
    }
    @Test fun detailRegionsMatchRenderedGlyphPixelsAcrossCropAndRotation() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val file = fixture(sourceRotation = rotation, crop = true)
            try { ChangesPdfDocument(file).use { pdf ->
                val full = pdf.render(0, pdf.pageSizes[0].first * 3)
                try {
                    val glyph = pdf.selectionText(0).search(0, "CMUX").single().bounds.first()
                    val left = ((glyph.left - 4) * 3).toInt().coerceAtLeast(0)
                    val top = ((glyph.top - 4) * 3).toInt().coerceAtLeast(0)
                    val region = PdfDetailRegion(3f, left, top, minOf(120, full.width - left), minOf(120, full.height - top))
                    val detail = pdf.renderRegion(0, region)
                    try {
                        var ink = 0; var different = 0
                        for (y in 0 until detail.height) for (x in 0 until detail.width) {
                            val actual = detail.getPixel(x, y); val expected = full.getPixel(left + x, top + y)
                            if (android.graphics.Color.red(actual) < 100) ink++
                            if (kotlin.math.abs(android.graphics.Color.red(actual) - android.graphics.Color.red(expected)) > 3) different++
                        }
                        assertTrue("Empty glyph region at rotation $rotation", ink > 20)
                        assertTrue("Region differs from independently rendered full page at rotation $rotation", different < detail.width * detail.height / 200)
                    } finally { detail.recycle() }
                } finally { full.recycle() }
            } } finally { file.delete() }
        }
    }
    @Test fun detailRenderingRejectsOversizedRetiredAndCancelledRequests() {
        val file = fixture(); val pdf = ChangesPdfDocument(file)
        try {
            val region = PdfDetailRegion(8f, 128, 256, 128, 128)
            assertThrows(IllegalArgumentException::class.java) { pdf.renderRegion(0, region.copy(width = 4096, height = 4096)) }
            var checks = 0
            assertThrows(java.util.concurrent.CancellationException::class.java) { pdf.renderRegion(0, region) {
                if (++checks == 2) throw java.util.concurrent.CancellationException()
            } }
            assertEquals(2, checks)
            pdf.renderRegion(0, region).recycle() // Cancellation closed its page; renderer remains usable.
            pdf.close()
            assertThrows(IllegalStateException::class.java) { pdf.renderRegion(0, region) }
        } finally { pdf.close(); file.delete() }
    }
    @Test fun zoomedViewerShowsMoreDetailThanTheFittedPreviewAfterRecreation() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val file = fixture(targetHeight = 600, targetX = 150, targetZoom = 4f, detailPattern = true)
        val evidence = File(context.getExternalFilesDir(null), "pdf-detail").apply { mkdirs() }
        val preview = ChangesPdfDocument(file).use { it.render(1, device.displayWidth * 2) }
        fun follow(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            node.actionList.firstOrNull { it.label?.toString() == "Go to page 2" }?.let { return node.performAction(it.id) }
            return (0 until node.childCount).any { follow(node.getChild(it)) }
        }
        fun await(message: String, condition: () -> Boolean) {
            val until = android.os.SystemClock.uptimeMillis() + 15_000
            while (!condition()) { check(android.os.SystemClock.uptimeMillis() < until) { message }; android.os.SystemClock.sleep(150) }
        }
        fun deviation(values: List<Int>): Double {
            val mean = values.average()
            return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        }
        fun checkDetail(name: String) {
            await("Visible PDF detail did not improve on the magnified fitted preview") {
                val screenshot = File(evidence, "$name.png"); device.takeScreenshot(screenshot)
                val actual = android.graphics.BitmapFactory.decodeFile(screenshot.path) ?: return@await false
                try {
                    var left = actual.width; var right = -1; var top = actual.height; var bottom = -1
                    for (y in 0 until actual.height step 2) for (x in 0 until actual.width step 2) {
                        val color = actual.getPixel(x, y)
                        if (android.graphics.Color.blue(color) > 230 && android.graphics.Color.red(color) < 30 && android.graphics.Color.green(color) < 30) {
                            left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                        }
                    }
                    val expected = 40 * 4 * context.resources.displayMetrics.density
                    if (left > 20 || kotlin.math.abs(right - left - expected) > 15 || kotlin.math.abs(bottom - top - expected) > 15) return@await false
                    val scale = (right - left + 1) / 40f; val inset = ((right - left + 1) * .1f).toInt()
                    val start = left + inset; val end = right - inset; val count = end - start + 1
                    val sample = android.graphics.Bitmap.createBitmap(count, 1, android.graphics.Bitmap.Config.ARGB_8888)
                    try {
                        // Independently magnify only a thin strip of the old fitted raster, without allocating a full zoomed page.
                        val previewScale = preview.width / 300f
                        val source = android.graphics.RectF((150 + inset / scale) * previewScale, (230 - .5f / scale) * previewScale,
                            (150 + (inset + count) / scale) * previewScale, (230 + .5f / scale) * previewScale)
                        val matrix = android.graphics.Matrix().apply {
                            setRectToRect(source, android.graphics.RectF(0f, 0f, count.toFloat(), 1f), android.graphics.Matrix.ScaleToFit.FILL)
                        }
                        android.graphics.Canvas(sample).drawBitmap(preview, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                        val baseline = deviation((0 until count).map { android.graphics.Color.red(sample.getPixel(it, 0)) })
                        val visible = deviation((start..end).map { android.graphics.Color.red(actual.getPixel(it, (top + bottom) / 2)) })
                        File(evidence, "$name.json").writeText(org.json.JSONObject().put("fittedPreviewDeviation", baseline)
                            .put("visibleDeviation", visible).put("patternWidth", right - left + 1).put("patternHeight", bottom - top + 1).toString())
                        baseline > 10 && visible > baseline * 1.1
                    } finally { sample.recycle() }
                } finally { actual.recycle() }
            }
        }
        try { ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
            .putExtra("path", file.absolutePath).putExtra("route", ChangesPreviewRoute.PDF.name).putExtra("mime", "application/pdf")).use { scenario ->
            await("No internal link action") { follow(instrumentation.uiAutomation.rootInActiveWindow) }
            checkDetail("magnified"); scenario.recreate(); checkDetail("restored")
            checkNotNull(device.wait(Until.findObject(By.desc("Back to previous location")), 10_000)).click()
            checkNotNull(device.wait(Until.findObject(By.text("1 / 2")), 10_000))
        } } finally { preview.recycle(); file.delete() }
    }
    @Test fun draggedPdfSelectionSpansPagesAndSurvivesRecreationBeforeCopy() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation); val file = fixture()
        val evidence = File(context.getExternalFilesDir(null), "pdf-selection").apply { mkdirs() }
        fun find(selector: BySelector): UiObject2 {
            return device.wait(Until.findObject(selector), 10_000) ?: run {
                device.takeScreenshot(File(evidence, "failure.png"))
                device.dumpWindowHierarchy(File(evidence, "failure.xml"))
                error(selector.toString())
            }
        }
        val firstWord = ChangesPdfDocument(file).use { it.selectionText(0).search(0, "CMUX").single().bounds }
        val firstX = (firstWord.first().left + firstWord.last().right) / 2
        val firstY = (firstWord.first().top + firstWord.first().bottom) / 2
        val end = ChangesPdfDocument(file).use { pdf ->
            val text = pdf.selectionText(1)
            checkNotNull(text.caret(PdfTextPosition(1, text.text.indexOf("needle") + 6), false))
        }
        try { ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
            .putExtra("path", file.absolutePath).putExtra("route", ChangesPreviewRoute.PDF.name)
            .putExtra("mime", "application/pdf")).use { scenario ->
            val first = find(By.desc("PDF page 1 of 2")).visibleBounds
            val factor = first.width() / 300f
            val x = (first.left + firstX * factor).toInt(); val y = (first.top + firstY * factor).toInt()
            device.swipe(x, y, x, y, 140)
            find(By.text(context.getString(android.R.string.copy))); find(By.desc("PDF selection start"))
            assertEquals("Selecting text must not move or resize the PDF viewport", first, find(By.desc("PDF page 1 of 2")).visibleBounds)
            val focus = find(By.desc("PDF selection end")).visibleBounds
            val second = find(By.desc("PDF page 2 of 2")).visibleBounds
            // Handles track the glyph baseline, while the touch target extends below it.
            val targetX = (second.left + end.x * factor + 2).toInt()
            val targetY = device.displayHeight - 80
            val down = android.os.SystemClock.uptimeMillis()
            fun touch(action: Int, x: Float, y: Float) {
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            touch(android.view.MotionEvent.ACTION_DOWN, focus.exactCenterX(), focus.exactCenterY())
            try {
                repeat(40) { step ->
                    val progress = (step + 1) / 40f
                    touch(android.view.MotionEvent.ACTION_MOVE, focus.centerX() + (targetX - focus.centerX()) * progress,
                        focus.centerY() + (targetY - focus.centerY()) * progress)
                    android.os.SystemClock.sleep(16)
                }
                // Keep the finger at the edge so the second page's selected line scrolls into view.
                repeat(65) { touch(android.view.MotionEvent.ACTION_MOVE, targetX.toFloat(), targetY.toFloat()); android.os.SystemClock.sleep(16) }
            } finally { touch(android.view.MotionEvent.ACTION_UP, targetX.toFloat(), targetY.toFloat()) }
            find(By.desc("PDF selection end"))
            device.takeScreenshot(File(evidence, "before-recreation.png"))
            device.dumpWindowHierarchy(File(evidence, "before-recreation.xml"))
            scenario.recreate()
            find(By.text(context.getString(android.R.string.copy))); find(By.desc("PDF selection end"))
            val screenshot = File(evidence, "cross-page-restored.png")
            device.takeScreenshot(screenshot)
            val bitmap = android.graphics.BitmapFactory.decodeFile(screenshot.path)
            try {
                var selectedPixels = 0
                for (yy in 0 until bitmap.height step 3) for (xx in 0 until bitmap.width step 3) {
                    val pixel = bitmap.getPixel(xx, yy)
                    if (android.graphics.Color.blue(pixel) > 245 && android.graphics.Color.red(pixel) in 175..205 &&
                        android.graphics.Color.green(pixel) in 195..225) selectedPixels++
                }
                assertTrue("Selection has no visible blue highlights", selectedPixels > 100)
            } finally { bitmap.recycle() }
            find(By.text(context.getString(android.R.string.copy))).click()
            check(device.wait(Until.gone(By.text(context.getString(android.R.string.copy))), 10_000))
            scenario.onActivity {
                val copied = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString()
                assertEquals("CMUX first needle\nCMUX second needle", copied.trim())
            }
        } } finally { file.delete() }
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
    @Test fun floatingPdfSelectionMenuSelectsAllAndClearsWithoutChangingViewport() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation); val file = fixture()
        val evidence = File(context.getExternalFilesDir(null), "pdf-selection-toolbar").apply { mkdirs() }
        fun find(selector: BySelector): UiObject2 = device.wait(Until.findObject(selector), 10_000) ?: run {
            device.takeScreenshot(File(evidence, "failure.png")); device.dumpWindowHierarchy(File(evidence, "failure.xml"))
            error(selector.toString())
        }
        val word = ChangesPdfDocument(file).use { it.selectionText(0).search(0, "CMUX").single().bounds }
        try { ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
            .putExtra("path", file.absolutePath).putExtra("route", ChangesPreviewRoute.PDF.name)
            .putExtra("mime", "application/pdf")).use { scenario ->
            val page = find(By.desc("PDF page 1 of 2")).visibleBounds; val factor = page.width() / 300f
            val target = android.graphics.Rect((page.left + word.first().left * factor).toInt(),
                (page.top + word.minOf { it.top } * factor).toInt(), (page.left + word.last().right * factor).toInt(),
                (page.top + word.maxOf { it.bottom } * factor).toInt())
            fun selectWord() {
                device.swipe(target.centerX(), target.centerY(), target.centerX(), target.centerY(), 140)
                val copy = find(By.text(context.getString(android.R.string.copy)))
                assertEquals(page, find(By.desc("PDF page 1 of 2")).visibleBounds)
                assertFalse("Floating Copy occludes selected text", android.graphics.Rect.intersects(copy.visibleBounds, target))
            }
            selectWord()
            device.takeScreenshot(File(evidence, "word-menu.png"))
            find(By.text(context.getString(android.R.string.selectAll))).click()
            find(By.text(context.getString(android.R.string.copy)))
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            find(By.text(context.getString(android.R.string.copy)))
            assertEquals(page, find(By.desc("PDF page 1 of 2")).visibleBounds)
            device.takeScreenshot(File(evidence, "select-all-resumed.png"))
            find(By.text(context.getString(android.R.string.copy))).click()
            check(device.wait(Until.gone(By.desc("PDF selection start")), 10_000))
            scenario.onActivity {
                val copied = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString()
                assertEquals("CMUX first needle\nCMUX second needle", copied.trim())
            }
            selectWord()
            if (device.findObject(By.text("Clear selection")) == null) find(By.descContains("More options")).click()
            device.takeScreenshot(File(evidence, "overflow-menu.png"))
            find(By.text("Clear selection")).click()
            check(device.wait(Until.gone(By.desc("PDF selection start")), 10_000))
            check(device.wait(Until.gone(By.text(context.getString(android.R.string.copy))), 10_000))
            assertEquals(page, find(By.desc("PDF page 1 of 2")).visibleBounds)
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
    @Test fun internalDestinationsRetainHorizontalCoordinatesAndZoom() {
        for (form in listOf("direct", "named", "action")) {
            val file = fixture(form, targetHeight = 600, targetX = 150, targetZoom = 4f)
            try { ChangesPdfDocument(file).use { pdf ->
                assertEquals(PdfLinkTarget.Page(1, 200f, 150f, 4f),
                    pdf.links(0).single { it.target is PdfLinkTarget.Page }.target)
            } } finally { file.delete() }
        }
    }
    @Test fun fitModesAndNullCoordinatesSurviveAnnotationParsing() {
        val modes = mapOf("/Fit" to PdfDestinationFit.PAGE, "/FitH 350" to PdfDestinationFit.WIDTH,
            "/FitV 120" to PdfDestinationFit.HEIGHT, "/FitR 100 200 250 400" to PdfDestinationFit.RECTANGLE,
            "/FitB" to PdfDestinationFit.CONTENT, "/FitBH 350" to PdfDestinationFit.CONTENT_WIDTH,
            "/FitBV 120" to PdfDestinationFit.CONTENT_HEIGHT)
        for ((expression, mode) in modes) {
            val file = fixture(targetHeight = 600, targetDestination = expression)
            try { ChangesPdfDocument(file).use { pdf ->
                val target = pdf.links(0).single { it.target is PdfLinkTarget.Page }.target as PdfLinkTarget.Page
                assertEquals(mode, target.fit)
                if (mode == PdfDestinationFit.RECTANGLE) assertEquals(PdfTextBounds(100f, 200f, 250f, 400f), target.rectangle)
            } } finally { file.delete() }
        }
        val file = fixture(sourceRotation = 90, crop = true, targetHeight = 600, targetRotation = 270,
            targetDestination = "/XYZ null null 0")
        try { ChangesPdfDocument(file).use { pdf ->
            val target = pdf.links(0).single { it.target is PdfLinkTarget.Page }.target as PdfLinkTarget.Page
            assertNotNull(target.retained)
            // Source renderer (350,30) = PDF (40,370). Rotated target maps that to (230,260).
            val resolved = pdf.resolveRetained(target, 0, 350f, 30f)
            assertEquals(230f, resolved.x, .01f); assertEquals(260f, resolved.y, .01f)
            assertNull(resolved.retained)
        } } finally { file.delete() }
    }
    @Test fun xyzLinkRendersRequestedZoomAndReturnsAfterRecreation() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val file = fixture(targetHeight = 600, targetX = 150, targetZoom = 4f)
        val evidence = File(context.getExternalFilesDir(null), "pdf-destinations").apply { mkdirs() }
        fun follow(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            node.actionList.firstOrNull { it.label?.toString() == "Go to page 2" }?.let { return node.performAction(it.id) }
            return (0 until node.childCount).any { follow(node.getChild(it)) }
        }
        fun waitFor(label: String, condition: () -> Boolean) {
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000
            while (!condition()) {
                check(android.os.SystemClock.uptimeMillis() < deadline) { label }
                android.os.SystemClock.sleep(100)
            }
        }
        fun checkPixels(name: String) {
            val expectedWidth = 20f * 4f * context.resources.displayMetrics.density
            waitFor("XYZ destination did not render its requested position and zoom") {
                val screenshot = File(evidence, name)
                device.takeScreenshot(screenshot)
                val bitmap = android.graphics.BitmapFactory.decodeFile(screenshot.path) ?: return@waitFor false
                try {
                    var left = bitmap.width; var right = -1
                    for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 2) {
                        val pixel = bitmap.getPixel(x, y)
                        if (android.graphics.Color.green(pixel) > 230 && android.graphics.Color.red(pixel) < 30 &&
                            android.graphics.Color.blue(pixel) < 30) { left = minOf(left, x); right = maxOf(right, x) }
                    }
                    right >= left && kotlin.math.abs(right - left - expectedWidth) < 15f && left < 20
                } finally { bitmap.recycle() }
            }
        }
        try { ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
            .putExtra("path", file.absolutePath).putExtra("route", ChangesPreviewRoute.PDF.name)
            .putExtra("mime", "application/pdf")).use { scenario ->
            waitFor("No internal link action") { follow(instrumentation.uiAutomation.rootInActiveWindow) }
            checkPixels("xyz.png")
            scenario.recreate()
            checkPixels("xyz-restored.png")
            checkNotNull(device.wait(Until.findObject(By.desc("Back to previous location")), 10_000)).click()
            checkNotNull(device.wait(Until.findObject(By.text("1 / 2")), 10_000))
            assertFalse(checkNotNull(device.findObject(By.desc("Back to previous location"))).isEnabled)
        } } finally { file.delete() }
    }
    @Test fun contentFitDisplaysGraphicsBoundsAndPreservesHistoryAfterRecreation() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val file = fixture(targetHeight = 600, targetDestination = "/FitB", filledRectangle = true, targetText = false)
        val evidence = File(context.getExternalFilesDir(null), "pdf-content-fit").apply { mkdirs() }
        fun follow(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            node.actionList.firstOrNull { it.label?.toString() == "Go to page 2" }?.let { return node.performAction(it.id) }
            return (0 until node.childCount).any { follow(node.getChild(it)) }
        }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000
            while (!condition()) { check(android.os.SystemClock.uptimeMillis() < deadline) { message }; android.os.SystemClock.sleep(100) }
        }
        fun checkPixels(name: String) {
            await("Content fit did not magnify/center the complete graphics rectangle") {
                val screenshot = File(evidence, name); device.takeScreenshot(screenshot)
                val bitmap = android.graphics.BitmapFactory.decodeFile(screenshot.path) ?: return@await false
                try {
                    var left = bitmap.width; var right = -1; var top = bitmap.height; var bottom = -1
                    for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                        val color = bitmap.getPixel(x, y)
                        if (android.graphics.Color.green(color) > 230 && android.graphics.Color.red(color) < 30 && android.graphics.Color.blue(color) < 30) {
                            left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                        }
                    }
                    right - left > 400 && bottom - top > 1000 && kotlin.math.abs((bottom - top).toFloat() / (right - left) - 2f) < .03f &&
                        kotlin.math.abs((left + right) / 2f - bitmap.width / 2f) < 12f
                } finally { bitmap.recycle() }
            }
        }
        try { ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
            .putExtra("path", file.absolutePath).putExtra("route", ChangesPreviewRoute.PDF.name).putExtra("mime", "application/pdf")).use { scenario ->
            await("Content-fit link unavailable") { follow(instrumentation.uiAutomation.rootInActiveWindow) }
            checkPixels("content-fit.png"); scenario.recreate(); checkPixels("content-fit-restored.png")
            checkNotNull(device.wait(Until.findObject(By.desc("Back to previous location")), 10_000)).click()
            checkNotNull(device.wait(Until.findObject(By.text("1 / 2")), 10_000))
        } } finally { file.delete() }
    }
    @Test fun fitRectangleShowsTheWholeMagnifiedRegion() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val file = fixture(targetHeight = 600, targetDestination = "/FitR 150 200 250 400", filledRectangle = true)
        val evidence = File(context.getExternalFilesDir(null), "pdf-destinations").apply { mkdirs() }
        fun follow(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            node.actionList.firstOrNull { it.label?.toString() == "Go to page 2" }?.let { return node.performAction(it.id) }
            return (0 until node.childCount).any { follow(node.getChild(it)) }
        }
        fun await(message: String, condition: () -> Boolean) {
            val until = android.os.SystemClock.uptimeMillis() + 15_000
            while (!condition()) {
                check(android.os.SystemClock.uptimeMillis() < until) { message }
                android.os.SystemClock.sleep(100)
            }
        }
        fun awaitStable(message: String, condition: () -> Boolean) {
            var stableSince = 0L
            await(message) {
                val now = android.os.SystemClock.uptimeMillis()
                if (!condition()) { stableSince = 0L; false }
                else { if (stableSince == 0L) stableSince = now; now - stableSince >= 350 }
            }
        }
        fun greenBounds(name: String): android.graphics.Rect? {
            val screenshot = File(evidence, name); device.takeScreenshot(screenshot)
            val bitmap = android.graphics.BitmapFactory.decodeFile(screenshot.path) ?: return null
            return try {
                var left = bitmap.width; var right = -1; var top = bitmap.height; var bottom = -1
                for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                    val color = bitmap.getPixel(x, y)
                    if (android.graphics.Color.green(color) > 230 && android.graphics.Color.red(color) < 30 && android.graphics.Color.blue(color) < 30) {
                        left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                    }
                }
                if (right >= left && bottom >= top) android.graphics.Rect(left, top, right, bottom) else null
            } finally { bitmap.recycle() }
        }
        fun visibleRegion(name: String): Boolean = greenBounds(name)?.let { bounds ->
            bounds.width() > 400 && bounds.height() > 1000 && kotlin.math.abs(bounds.height().toFloat() / bounds.width() - 2f) < .03f &&
                kotlin.math.abs(bounds.exactCenterX() - device.displayWidth / 2f) < 12f
        } ?: false
        try { ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
            .putExtra("path", file.absolutePath).putExtra("route", ChangesPreviewRoute.PDF.name)
            .putExtra("mime", "application/pdf")).use { scenario ->
            await("No internal rectangle link") { follow(instrumentation.uiAutomation.rootInActiveWindow) }
            awaitStable("Magnified rectangle was clipped or not centered") { visibleRegion("fit-rectangle.png") }
            scenario.recreate()
            awaitStable("Rectangle fitting did not survive recreation") { visibleRegion("fit-rectangle-restored.png") }
            val fittedBounds = checkNotNull(greenBounds("fit-rectangle-restored.png"))
            val fittedWidth = fittedBounds.width()
            checkNotNull(device.findObject(By.desc("PDF page 2 of 2"))).pinchClose(.4f)
            var reducedWidth = fittedWidth
            awaitStable("Pinch did not reduce the document scale") {
                val bounds = greenBounds("pinch-close.png")
                reducedWidth = bounds?.width() ?: 0
                reducedWidth in 100 until (fittedWidth * .8f).toInt() && bounds != null &&
                    kotlin.math.abs(bounds.exactCenterY() - fittedBounds.exactCenterY()) < 60f
            }
            checkNotNull(device.findObject(By.desc("PDF page 2 of 2"))).pinchOpen(.4f)
            awaitStable("Pinch did not magnify the document") { (greenBounds("pinch-open.png")?.width() ?: 0) > reducedWidth * 1.2f }
            val bottom = checkNotNull(greenBounds("pinch-open.png")).bottom
            repeat(2) { device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 30) }
            awaitStable("One-finger vertical scrolling was trapped by zoom") {
                (greenBounds("zoomed-scroll.png")?.bottom ?: 0) < bottom - 100
            }
        } } finally { file.delete() }
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
            // A link bookmark survives recreation and returns to the reading location.
            scenario.recreate()
            find(By.desc("Back to previous location")).click()
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
