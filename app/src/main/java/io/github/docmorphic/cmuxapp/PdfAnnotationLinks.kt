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

/** Metadata only: no JavaScript, embedded-file extraction, launch actions, fonts or rendering. */
internal class PdfAnnotationLinks(file: File, private val pageSizes: List<Pair<Int, Int>>) : AutoCloseable {
    private val document = PDDocument.load(file, MemoryUsageSetting.setupMixed(8L * 1024 * 1024, 128L * 1024 * 1024)
        .setTempDir(file.parentFile))
    private val cache = mutableMapOf<Int, List<PdfDocumentLink>>()
    private fun coordinates(index: Int): PdfPageCoordinates {
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
        val x = if (kind == "XYZ" || kind == "FitV" || kind == "FitBV" || kind == "FitR") number(2) ?: geometry.left else geometry.left
        val top = when (kind) {
            "XYZ" -> number(3)
            "FitH", "FitBH" -> number(2)
            "FitR" -> number(5)
            else -> null
        } ?: (geometry.bottom + geometry.height)
        val point = geometry.point(x, top) ?: return null
        return PdfLinkTarget.Page(index, point.second.coerceIn(0f, pageSizes[index].second.toFloat()))
    }
    override fun close() { cache.clear(); document.close() }
}
