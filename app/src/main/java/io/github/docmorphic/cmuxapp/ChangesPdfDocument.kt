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

internal class ChangesPdfDocument(private val file: File, nativeText: Boolean = Build.VERSION.SDK_INT >= 35) : AutoCloseable {
    private val useNativeText = nativeText && Build.VERSION.SDK_INT >= 35
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(descriptor) } catch (error: Throwable) { descriptor.close(); throw error }
    private var closed = false
    private var linkIndex: PdfAnnotationLinks? = null
    private var attemptedLinkIndex = false
    var incompleteLinks = false
        private set
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
    val supportsText get() = true
    @Synchronized fun resolveRetained(target: PdfLinkTarget.Page, sourcePage: Int, x: Float, y: Float): PdfLinkTarget.Page {
        check(!closed)
        val retained = target.retained ?: return target
        val current = checkNotNull(compatibility().coordinates(sourcePage).userPoint(x, y))
        val point = checkNotNull(retained.resolve(current))
        return target.copy(x = point.first, y = point.second, retained = null)
    }
    @Synchronized fun resolveDestination(target: PdfLinkTarget.Page, sourcePage: Int, x: Float, y: Float): PdfLinkTarget.Page {
        val resolved = resolveRetained(target, sourcePage, x, y)
        if (resolved.fit !in setOf(PdfDestinationFit.CONTENT, PdfDestinationFit.CONTENT_WIDTH, PdfDestinationFit.CONTENT_HEIGHT))
            return resolved
        // Resolve lazily on link activation, not for every annotation on every visible page.
        val bounds = compatibility().contentBounds(target.index)
            ?: PdfTextBounds(0f, 0f, pageSizes[target.index].first.toFloat(), pageSizes[target.index].second.toFloat())
        return resolved.copy(rectangle = bounds)
    }
    private fun compatibility(): PdfAnnotationLinks {
        if (linkIndex == null) linkIndex = PdfAnnotationLinks(file, pageSizes)
        return checkNotNull(linkIndex)
    }
    @Synchronized fun selectionText(index: Int): PdfCompatibilityTextPage {
        check(!closed)
        return compatibility().text(index)
    }
    @Synchronized fun selectedText(selection: PdfTextSelection, checkActive: () -> Unit = {}): String {
        check(!closed)
        require(selection.start.page in pageSizes.indices && selection.end.page in pageSizes.indices)
        val result = StringBuilder()
        for (page in selection.start.page..selection.end.page) {
            checkActive()
            val text = selectionText(page).text
            val offsets = selection.offsets(page, text.length) ?: continue
            if (result.isNotEmpty() && result.last() != '\n' && text[offsets.first] != '\n') result.append('\n')
            // Leave room for Android's clipboard Binder envelope. Never silently truncate copied text.
            require(result.length + offsets.count() <= 250_000) { "PDF selection is too large for the clipboard" }
            result.append(text, offsets.first, offsets.last + 1)
        }
        return result.toString()
    }
    @Synchronized fun text(index: Int): String {
        check(!closed); if (!useNativeText) return compatibility().text(index).text
        return renderer.openPage(index).use { it.textContents.joinToString("\n") { content -> content.text } }
    }
    @Synchronized fun search(index: Int, query: String): List<PdfTextMatch> {
        check(!closed); if (query.isBlank()) return emptyList()
        if (!useNativeText) return compatibility().text(index).search(index, query)
        return renderer.openPage(index).use { page -> page.searchText(query).map { match ->
            PdfTextMatch(index, match.textStartIndex, match.bounds.map { it.pdfBounds() })
        } }
    }
    @Synchronized fun links(index: Int): List<PdfDocumentLink> {
        check(!closed)
        // Android's native extractor skips direct /Dest annotations. Parse annotations,
        // including named destinations, before falling back to platform extraction.
        var parsed: List<PdfDocumentLink>? = null
        try {
            if (!attemptedLinkIndex) {
                attemptedLinkIndex = true
                compatibility()
            }
            parsed = linkIndex?.links(index)
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException) throw error
            incompleteLinks = true
        }
        if (Build.VERSION.SDK_INT < 35) return parsed.orEmpty()
        val native = try { renderer.openPage(index).use { page ->
            page.linkContents.mapNotNull { link -> PdfLinkTarget.external(link.uri.toString())?.let {
                PdfDocumentLink(link.bounds.map { it.pdfBounds() }, it)
            } } + (if (parsed != null) emptyList() else page.gotoLinks.mapNotNull { link ->
                val target = link.destination
                target.pageNumber.takeIf { it in pageSizes.indices }?.let {
                    PdfDocumentLink(link.bounds.map { it.pdfBounds() }, PdfLinkTarget.Page(it, target.yCoordinate, target.xCoordinate, target.zoom))
                }
            })
        } } catch (error: Exception) {
            if (parsed == null || error is java.util.concurrent.CancellationException) throw error
            incompleteLinks = true
            emptyList()
        }
        // Preserve platform-inferred URLs in ordinary text. Explicit annotations win
        // when they cover the same area, including native destinations with wrong crop coordinates.
        val annotated = parsed ?: return native
        return annotated + native.filter { link -> annotated.none { explicit -> explicit.bounds.any { a ->
            link.bounds.any { b -> a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top }
        } } }
    }
    @Synchronized fun word(index: Int, x: Float, y: Float): String? {
        check(!closed)
        if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return null
        if (!useNativeText) return compatibility().text(index).word(x * pageSizes[index].first, y * pageSizes[index].second)
        return renderer.openPage(index).use { page ->
            val boundary = SelectionBoundary(Point((x * page.width).toInt().coerceIn(0, page.width - 1),
                (y * page.height).toInt().coerceIn(0, page.height - 1)))
            page.selectContent(boundary, boundary)?.selectedTextContents?.joinToString("\n") { it.text }?.takeIf { it.isNotBlank() }
        }
    }
    private fun RectF.pdfBounds() = PdfTextBounds(left, top, right, bottom)
    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            try { linkIndex?.close() } finally { try { renderer.close() } finally { descriptor.close() } }
        }
    }
}
