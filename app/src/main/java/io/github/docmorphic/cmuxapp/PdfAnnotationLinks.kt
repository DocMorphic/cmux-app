package io.github.docmorphic.cmuxapp

import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import java.io.File

/** Links and compatibility text; no JavaScript, embedded-file extraction, launch actions or rendering. */
internal class PdfAnnotationLinks(file: File, private val pageSizes: List<Pair<Int, Int>>) : AutoCloseable {
    private val document = PDDocument.load(file, MemoryUsageSetting.setupMixed(8L * 1024 * 1024, 128L * 1024 * 1024)
        .setTempDir(file.parentFile))
    private val contentBoundsCache = mutableMapOf<Int, PdfTextBounds?>()
    fun contentBounds(index: Int): PdfTextBounds? {
        require(document.numberOfPages == pageSizes.size) { "PDF parsers disagree about page count" }
        if (!contentBoundsCache.containsKey(index))
            contentBoundsCache[index] = PdfContentBounds(document.getPage(index)).read(coordinates(index))
        return contentBoundsCache[index]
    }
    private val cache = mutableMapOf<Int, List<PdfDocumentLink>>()
    private val textCache = object : LinkedHashMap<Int, PdfCompatibilityTextPage>(3, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfCompatibilityTextPage>?) = size > 2
    }
    fun coordinates(index: Int): PdfPageCoordinates {
        val page = document.getPage(index)
        val crop = page.cropBox
        val size = pageSizes[index]
        return PdfPageCoordinates(crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height, page.rotation, size.first, size.second)
    }
    fun links(index: Int): List<PdfDocumentLink> = cache.getOrPut(index) {
        require(document.numberOfPages == pageSizes.size) { "PDF parsers disagree about page count" }
        val geometry = coordinates(index)
        document.getPage(index).annotations.filterIsInstance<PDAnnotationLink>().mapNotNull { annotation ->
            val action = annotation.action
            val target = when {
                annotation.destination != null -> destination(annotation.destination)
                action is PDActionGoTo -> destination(action.destination)
                action is PDActionURI -> action.uri?.let(PdfLinkTarget::external)
                else -> null
            } ?: return@mapNotNull null
            val quads = annotation.quadPoints?.takeIf { it.isNotEmpty() && it.size % 8 == 0 }
            val bounds = if (quads != null) quads.toList().chunked(8).mapNotNull { quad ->
                geometry.bounds(quad.chunked(2).map { it[0] to it[1] })
            } else annotation.rectangle?.let { rect -> listOfNotNull(geometry.bounds(listOf(
                rect.lowerLeftX to rect.lowerLeftY, rect.lowerLeftX to rect.upperRightY,
                rect.upperRightX to rect.lowerLeftY, rect.upperRightX to rect.upperRightY))) }.orEmpty()
            if (bounds.isEmpty()) null else PdfDocumentLink(bounds, target)
        }
    }
    fun text(index: Int): PdfCompatibilityTextPage = textCache.getOrPut(index) {
        require(document.numberOfPages == pageSizes.size) { "PDF parsers disagree about page count" }
        pdfCompatibilityText(document, index, coordinates(index))
    }
    private fun destination(raw: PDDestination?): PdfLinkTarget.Page? {
        val destination = when (raw) {
            is PDNamedDestination -> document.documentCatalog.findNamedDestinationPage(raw)
            is PDPageDestination -> raw
            else -> null
        } ?: return null
        val index = destination.retrievePageNumber()
        if (index !in pageSizes.indices) return null
        val geometry = coordinates(index)
        val array = destination.cosObject
        fun number(index: Int) = if (index < array.size())
            (array.getObject(index) as? COSNumber)?.floatValue()?.takeIf(Float::isFinite) else null
        if (array.size() < 2) return null
        val kind = array.getName(1)
        val rawX = if (kind == "XYZ" || kind == "FitV" || kind == "FitBV" || kind == "FitR") number(2) else geometry.left
        val rawTop = when (kind) {
            "XYZ" -> number(3)
            "FitH", "FitBH" -> number(2)
            "FitR" -> number(5)
            else -> geometry.bottom + geometry.height
        }
        val point = geometry.point(rawX ?: geometry.left, rawTop ?: (geometry.bottom + geometry.height)) ?: return null
        val rectangle = if (kind == "FitR") {
            val left = number(2) ?: return null; val bottom = number(3) ?: return null
            val right = number(4) ?: return null; val top = number(5) ?: return null
            if (right <= left || top <= bottom) return null
            geometry.bounds(listOf(left to bottom, left to top, right to bottom, right to top)) ?: return null
        } else null
        val fit = when (kind) {
            "XYZ" -> PdfDestinationFit.XYZ
            "Fit" -> PdfDestinationFit.PAGE
            "FitH" -> PdfDestinationFit.WIDTH
            "FitV" -> PdfDestinationFit.HEIGHT
            "FitR" -> PdfDestinationFit.RECTANGLE
            "FitB" -> PdfDestinationFit.CONTENT
            "FitBH" -> PdfDestinationFit.CONTENT_WIDTH
            "FitBV" -> PdfDestinationFit.CONTENT_HEIGHT
            else -> return null
        }
        return PdfLinkTarget.Page(index, point.second.coerceIn(0f, pageSizes[index].second.toFloat()),
            point.first.coerceIn(0f, pageSizes[index].first.toFloat()),
            if (kind == "XYZ") number(4)?.coerceAtLeast(0f) ?: 0f else 0f, retainZoom = kind == "XYZ", fit = fit,
            rectangle = rectangle, retained = if (rawX == null || rawTop == null) PdfRetainedCoordinates(geometry, rawX, rawTop) else null)
    }
    override fun close() { cache.clear(); textCache.clear(); contentBoundsCache.clear(); document.close() }
}
