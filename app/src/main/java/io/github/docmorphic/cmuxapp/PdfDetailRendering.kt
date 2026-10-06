package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import androidx.compose.runtime.*
import kotlinx.coroutines.*

internal data class PdfRenderedRegion(val region: PdfDetailRegion, val bitmap: Bitmap)

@Composable
internal fun rememberPdfDetail(pdf: ChangesPdfDocument, page: Int, region: PdfDetailRegion?): PdfRenderedRegion? {
    // Scope the retained image to this exact document/page, even if Compose reuses the call site.
    val retained = remember(pdf, page) { mutableStateOf<PdfRenderedRegion?>(null) }
    LaunchedEffect(pdf, page, region) {
        if (region == null) { retained.value = null; return@LaunchedEffect }
        delay(80) // Keep the existing preview responsive while a gesture is still moving.
        var pending: Bitmap? = null; var published = false
        try {
            withContext(Dispatchers.IO) {
                val coroutine = currentCoroutineContext()
                pending = pdf.renderRegion(page, region) { coroutine.ensureActive() }
            }
            ensureActive()
            retained.value = PdfRenderedRegion(region, checkNotNull(pending)); published = true
        } catch (error: Exception) {
            ensureActive() // A failed detail pass leaves the bounded page preview/previous detail available.
        } finally {
            // Only unpublished bitmaps are recycled synchronously. Published images may still be in a render-thread frame.
            if (!published) pending?.recycle()
        }
    }
    return retained.value
}
