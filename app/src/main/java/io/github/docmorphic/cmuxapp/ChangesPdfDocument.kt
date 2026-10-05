package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.sqrt

internal class ChangesPdfDocument(file: File) : AutoCloseable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(descriptor) } catch (error: Throwable) { descriptor.close(); throw error }
    private var closed = false
    val pageSizes: List<Pair<Int, Int>> = try { (0 until renderer.pageCount).map { index -> renderer.openPage(index).use { it.width to it.height } } }
        catch (error: Throwable) { renderer.close(); descriptor.close(); throw error }
    @Synchronized fun render(index: Int, width: Int): Bitmap {
        check(!closed)
        return renderer.openPage(index).use { page ->
            val scale = minOf(width.coerceIn(1, 2560).toDouble() / page.width,
                sqrt(8_000_000.0 / (page.width.toDouble() * page.height)))
            val bitmap = Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            try { page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); bitmap }
            catch (error: Throwable) { bitmap.recycle(); throw error }
        }
    }
    val supportsText get() = Build.VERSION.SDK_INT >= 35
    @Synchronized fun text(index: Int): String {
        check(!closed); if (!supportsText) return ""
        return renderer.openPage(index).use { it.textContents.joinToString("\n") { content -> content.text } }
    }
    @Synchronized fun search(index: Int, query: String): List<PdfTextMatch> {
        check(!closed); if (!supportsText || query.isBlank()) return emptyList()
        return renderer.openPage(index).use { page -> page.searchText(query).map { match ->
            PdfTextMatch(index, match.textStartIndex, match.bounds.map { it.pdfBounds() })
        } }
    }
    @Synchronized fun links(index: Int): List<PdfDocumentLink> {
        check(!closed); if (!supportsText) return emptyList()
        return renderer.openPage(index).use { page ->
            page.linkContents.mapNotNull { link -> PdfLinkTarget.external(link.uri.toString())?.let {
                PdfDocumentLink(link.bounds.map { it.pdfBounds() }, it)
            } } + page.gotoLinks.mapNotNull { link ->
                val target = link.destination
                target.pageNumber.takeIf { it in pageSizes.indices }?.let {
                    PdfDocumentLink(link.bounds.map { it.pdfBounds() }, PdfLinkTarget.Page(it, target.yCoordinate))
                }
            }
        }
    }
    @Synchronized fun word(index: Int, x: Float, y: Float): String? {
        check(!closed); if (!supportsText) return null
        return renderer.openPage(index).use { page ->
            val boundary = SelectionBoundary(Point((x * page.width).toInt().coerceIn(0, page.width - 1),
                (y * page.height).toInt().coerceIn(0, page.height - 1)))
            page.selectContent(boundary, boundary)?.selectedTextContents?.joinToString("\n") { it.text }?.takeIf { it.isNotBlank() }
        }
    }
    private fun RectF.pdfBounds() = PdfTextBounds(left, top, right, bottom)
    @Synchronized override fun close() { if (!closed) { closed = true; renderer.close(); descriptor.close() } }
}

