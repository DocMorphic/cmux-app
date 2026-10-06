package io.github.docmorphic.cmuxapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun ChangesPdfPreview(file: File) {
    var failure by remember(file) { mutableStateOf<String?>(null) }
    val document by produceState<ChangesPdfDocument?>(null, file) {
        var owned: ChangesPdfDocument? = null
        try {
            withContext(Dispatchers.IO) { owned = ChangesPdfDocument(file) }
            value = owned; awaitCancellation()
        } catch (error: Exception) { currentCoroutineContext().ensureActive(); failure = "This PDF can’t be read. Reopen its preview, or use Open to try another app." }
        finally { withContext(NonCancellable + Dispatchers.IO) { owned?.close() } }
    }
    val pdf = document
    if (failure != null) ChangesNotice("Preview unavailable", failure.orEmpty())
    else if (pdf == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    else PdfDocumentContent(pdf)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PdfDocumentContent(pdf: ChangesPdfDocument) {
    val context = LocalContext.current
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val textSelection = rememberPdfSelection(pdf)
    fun copySelection() = textSelection.copy { text ->
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PDF text", text))
    }
    BackHandler(textSelection.range != null) { textSelection.clear() }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var matchIndex by rememberSaveable { mutableIntStateOf(0) }
    var searchNavigationPending by rememberSaveable { mutableStateOf(false) }
    var matches by remember(pdf) { mutableStateOf<List<PdfTextMatch>>(emptyList()) }
    var matchesQuery by remember(pdf) { mutableStateOf("") }
    var scanned by remember(pdf) { mutableIntStateOf(0) }
    var searching by remember(pdf) { mutableStateOf(false) }
    var searchFailure by remember(pdf) { mutableStateOf<String?>(null) }
    var actionFailure by remember(pdf) { mutableStateOf<String?>(null) }
    var textPage by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectionX by rememberSaveable { mutableFloatStateOf(-1f) }
    var selectionY by rememberSaveable { mutableFloatStateOf(-1f) }
    var pageWidth by remember { mutableFloatStateOf(0f) }
    var previousLayoutWidth by rememberSaveable { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    val documentWidth = remember(pdf) { pdf.pageSizes.maxOf { it.first } }
    var zoom by rememberSaveable(stateSaver = listSaver<PreviewZoomTransform, Float>(
        save = { listOf(it.scale, it.x) }, restore = { PreviewZoomTransform(it[0], it[1]) }
    )) { mutableStateOf(PreviewZoomTransform()) }
    var linkJob by remember { mutableStateOf<Job?>(null) }
    // Compact saved bookmarks: page, offset/width, scale, X, reserved Y, top inset/width.
    var history by rememberSaveable { mutableStateOf(arrayListOf<Float>()) }
    var destination by rememberSaveable { mutableStateOf(arrayListOf<Float>()) }
    val density = LocalDensity.current.density
    val gesture = remember(pdf) { PdfGestureAnchorState() }
    val selected = matches.getOrNull(matchIndex)
    fun inset(page: Int): Float = if (destination.firstOrNull()?.toInt() == page) destination.getOrElse(5) { 0f } else 0f
    fun navigate(page: Int, offsetPerWidth: Float, transform: PreviewZoomTransform = zoom, topInset: Float = 0f) {
        linkJob?.cancel()
        textSelection.clear()
        destination = arrayListOf(page.toFloat(), offsetPerWidth, transform.scale, transform.x, 0f, topInset)
        zoom = transform
        scroll.requestScrollToItem(page, (offsetPerWidth * pageWidth).toInt().coerceAtLeast(0))
    }
    fun rememberLocation() {
        val page = scroll.firstVisibleItemIndex
        history = ArrayList((history + listOf(page.toFloat(), scroll.firstVisibleItemScrollOffset / pageWidth.coerceAtLeast(1f),
            zoom.scale, zoom.x, 0f, inset(page))).takeLast(32 * 6))
    }
    fun returnToLocation() {
        if (history.size < 6) return
        val saved = history.takeLast(6)
        searchNavigationPending = false
        history = ArrayList(history.dropLast(6))
        navigate(saved[0].toInt(), saved[1], PreviewZoomTransform(saved[2], saved[3]), saved[5])
    }
    fun jump(page: Int, y: Float = 0f) {
        if (page !in pdf.pageSizes.indices) return
        navigate(page, if (y.isFinite()) y.coerceIn(0f, pdf.pageSizes[page].second.toFloat()) * zoom.scale / documentWidth else 0f)
    }
    fun transform(factor: Float, pan: Offset, centroid: Offset) {
        if (pageWidth <= 0f || !factor.isFinite() || factor <= 0f || !pan.x.isFinite() || !pan.y.isFinite() ||
            !centroid.x.isFinite() || !centroid.y.isFinite()) return
        linkJob?.cancel()
        val before = zoom
        val next = before.transform(factor, pan.x / pageWidth, 0f, centroid.x / pageWidth - .5f, 0f, minimumScale = .125f)
        val anchor = (gesture.anchor ?: run {
            val visible = scroll.layoutInfo.visibleItemsInfo
            val item = visible.firstOrNull { centroid.y >= it.offset && centroid.y < it.offset + it.size }
                ?: visible.lastOrNull { it.offset <= centroid.y } ?: visible.firstOrNull() ?: return
            val within = (centroid.y - item.offset - inset(item.index) * pageWidth).coerceAtLeast(0f)
            PdfGestureAnchor(item.index, within / before.scale, centroid.y)
        }).move(pan.y)
        gesture.anchor = anchor
        destination = arrayListOf() // Gesture scrolling owns the viewport after a destination jump.
        zoom = next
        scroll.requestScrollToItem(anchor.page, anchor.scrollOffset(next.scale).toInt())
    }
    fun open(link: PdfLinkTarget) {
        when (link) {
            is PdfLinkTarget.Page -> {
                if (link.index !in pdf.pageSizes.indices || pageWidth <= 0f || viewportHeight <= 0f) return
                linkJob?.cancel()
                val origin = scroll.firstVisibleItemIndex
                val originSize = pdf.pageSizes[origin]
                val factor = pageWidth / documentWidth * zoom.scale
                val currentX = originSize.first / 2f + (-.5f - zoom.x) * pageWidth / factor
                val currentY = (scroll.firstVisibleItemScrollOffset - inset(origin) * pageWidth) / factor
                searchNavigationPending = false
                linkJob = scope.launch {
                    try {
                        val resolved = withContext(Dispatchers.IO) { pdf.resolveDestination(link, origin, currentX, currentY) }
                        ensureActive()
                        val size = pdf.pageSizes[link.index]
                        val target = PdfDestinationViewport.resolve(resolved, size.first, size.second, pageWidth, density,
                            zoom.scale, viewportHeight, documentWidth)
                        rememberLocation()
                        linkJob = null
                        navigate(link.index, target.scrollFraction * size.second / documentWidth, target.transform,
                            target.topInsetFraction * size.second / documentWidth)
                    } catch (error: Exception) { ensureActive(); actionFailure = "This document link couldn't be opened." }
                }
            }
            is PdfLinkTarget.External -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) }
                .onFailure { actionFailure = "No app could open this link." }
        }
    }
    LaunchedEffect(pdf, query, searchOpen) {
        matches = emptyList(); matchesQuery = ""; scanned = 0; searchFailure = null; searching = false
        if (!searchOpen || query.isBlank() || !pdf.supportsText) return@LaunchedEffect
        searching = true
        try {
            delay(250)
            for (index in pdf.pageSizes.indices) {
                val found = withContext(Dispatchers.IO) { pdf.search(index, query) }
                ensureActive(); matches = matches + found; matchesQuery = query; scanned = index + 1
            }
            if (matchIndex !in matches.indices) matchIndex = 0
        } catch (error: Exception) { ensureActive(); searchFailure = "This document couldn't be searched. Try another query or reopen it." }
        finally { searching = false }
    }
    LaunchedEffect(selected, pageWidth, searchNavigationPending, query, matchesQuery) {
        if (searchNavigationPending && pageWidth > 0f && matchesQuery == query) selected?.let {
            jump(it.page, it.bounds.firstOrNull()?.top ?: 0f); searchNavigationPending = false
        }
    }
    textPage?.let { index ->
        PdfPageTextDialog(pdf, index, if (selectionX >= 0 && selectionY >= 0) Offset(selectionX, selectionY) else null,
            onDismiss = { textPage = null; selectionX = -1f; selectionY = -1f }, onWholePage = { selectionX = -1f; selectionY = -1f })
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconButton(enabled = history.isNotEmpty(), onClick = ::returnToLocation,
                modifier = Modifier.semantics { contentDescription = "Back to previous location" }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
            }
            TextButton(enabled = scroll.firstVisibleItemIndex > 0, onClick = { jump(scroll.firstVisibleItemIndex - 1) }) { Text("Previous page") }
            Text("${scroll.firstVisibleItemIndex + 1} / ${pdf.pageSizes.size}", fontSize = 12.sp)
            TextButton(enabled = scroll.firstVisibleItemIndex < pdf.pageSizes.lastIndex, onClick = { jump(scroll.firstVisibleItemIndex + 1) }) { Text("Next page") }
        }
        if (pdf.supportsText) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { textSelection.clear(); searchOpen = !searchOpen }) { Text(if (searchOpen) "Close search" else "Search document") }
            TextButton(onClick = { textSelection.clear(); selectionX = -1f; selectionY = -1f; textPage = scroll.firstVisibleItemIndex }) { Text("Page text") }
        }
        if (searchOpen && pdf.supportsText) {
            OutlinedTextField(query, { query = it.take(1024); matchIndex = 0; searchNavigationPending = query.isNotBlank() }, singleLine = true,
                label = { Text("Search PDF") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                TextButton(enabled = matches.isNotEmpty(), onClick = { matchIndex = pdfMatchStep(matchIndex, -1, matches.size); searchNavigationPending = true }) { Text("Previous match") }
                Text(if (matches.isNotEmpty()) "${matchIndex.coerceAtMost(matches.lastIndex) + 1} / ${matches.size}" else if (query.isBlank()) "" else if (searching) "Searching…" else "No matches", fontSize = 12.sp,
                    modifier = Modifier.semantics { contentDescription = "PDF search results"; liveRegion = LiveRegionMode.Polite })
                TextButton(enabled = matches.isNotEmpty(), onClick = { matchIndex = pdfMatchStep(matchIndex, 1, matches.size); searchNavigationPending = true }) { Text("Next match") }
            }
            if (searching) LinearProgressIndicator(progress = { scanned.toFloat() / pdf.pageSizes.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            searchFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        textSelection.failure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        actionFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        BoxWithConstraints(Modifier.weight(1f)) {
            val lastSize = pdf.pageSizes.lastOrNull()
            val lastHeight = lastSize?.let { maxWidth * (it.second.toFloat() / documentWidth) * zoom.scale } ?: maxHeight
            val destinationTail = if (destination.firstOrNull()?.toInt() == pdf.pageSizes.lastIndex)
                maxWidth * (destination[1] - inset(pdf.pageSizes.lastIndex)) else 0.dp
            val endPadding = maxOf(0.dp, maxHeight - lastHeight + destinationTail)
            val width = with(LocalDensity.current) { maxWidth.toPx() }
            val height = with(LocalDensity.current) { maxHeight.toPx() }
            val viewportWidthDp = maxWidth
            SideEffect {
                if (previousLayoutWidth > 0f && previousLayoutWidth != width)
                    scroll.requestScrollToItem(scroll.firstVisibleItemIndex,
                        (scroll.firstVisibleItemScrollOffset * width / previousLayoutWidth).toInt())
                previousLayoutWidth = width; pageWidth = width; viewportHeight = height
            }
            LazyColumn(Modifier.fillMaxSize().pdfPanZoomGestures(zoom.scale, { gesture.anchor = null }, ::transform), state = scroll,
                contentPadding = PaddingValues(bottom = endPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pdf.pageSizes.size, key = { it }) { index ->
                    Column {
                        if (inset(index) > 0f) Spacer(Modifier.height(viewportWidthDp * inset(index)))
                        PdfDocumentPage(pdf, index, matches.filter { it.page == index }, selected, zoom, documentWidth, textSelection.range,
                            viewportHeight = height,
                            pageTop = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.let { it.offset + inset(index) * width },
                            onClearSelection = { textSelection.clear() }, onSelectPage = { textSelection.selectPage(index) },
                            onCopySelection = ::copySelection, onSelectAll = { textSelection.selectAll() },
                            onDoubleTap = { point ->
                                val item = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                                if (item != null) {
                                    val focus = Offset(point.x, point.y + item.offset + inset(index) * pageWidth)
                                    gesture.anchor = null
                                    transform((if (zoom.atMinimum) 2f else 1f) / zoom.scale,
                                        Offset(pageWidth / 2f, viewportHeight / 2f) - focus, focus)
                                    gesture.anchor = null
                                }
                            }, onLink = ::open, onText = { point -> textSelection.selectWord(index, point.x, point.y) },
                            onPageText = { textSelection.clear(); selectionX = -1f; selectionY = -1f; textPage = index })
                    }
                }
            }
            if (textSelection.range != null) PdfSelectionHandles(textSelection, pdf, scroll, zoom,
                width, height, documentWidth, ::inset, ::copySelection)
            if (textSelection.copying) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .semantics { contentDescription = "Copying PDF selection" })
        }
    }
}

@Composable
private fun PdfDocumentPage(pdf: ChangesPdfDocument, index: Int, matches: List<PdfTextMatch>, selected: PdfTextMatch?, zoom: PreviewZoomTransform,
    documentWidth: Int, selection: PdfTextSelection?, viewportHeight: Float, pageTop: Float?,
    onClearSelection: () -> Unit, onSelectPage: () -> Unit, onDoubleTap: (Offset) -> Unit,
    onLink: (PdfLinkTarget) -> Unit, onText: (Offset) -> Unit, onPageText: () -> Unit,
    onCopySelection: () -> Unit, onSelectAll: () -> Unit) {
    val pageSize = pdf.pageSizes[index]
    val selectionText by produceState<PdfCompatibilityTextPage?>(null, pdf, index, selection != null) {
        value = null
        if (selection != null) try { value = withContext(Dispatchers.IO) { pdf.selectionText(index) } }
        catch (error: Exception) { currentCoroutineContext().ensureActive() }
    }
    val highlighted = remember(selectionText, selection, index) {
        selection?.let { selectionText?.selectionBounds(it, index) }.orEmpty()
    }
    var pageFailure by remember(pdf, index) { mutableStateOf<String?>(null) }
    var linkFailure by remember(pdf, index) { mutableStateOf(false) }
    val links by produceState<List<PdfDocumentLink>>(emptyList(), pdf, index) {
        try { value = withContext(Dispatchers.IO) { pdf.links(index) }; linkFailure = pdf.incompleteLinks }
        catch (error: Exception) { currentCoroutineContext().ensureActive(); linkFailure = true }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val pageHeight = maxWidth * (pageSize.second.toFloat() / documentWidth) * zoom.scale
        val imageWidth = maxWidth * (pageSize.first.toFloat() / documentWidth) * zoom.scale
        val viewportPixels = with(LocalDensity.current) { maxWidth.toPx() }
        val pagePixels = with(LocalDensity.current) { pageHeight.toPx() }
        val rasterWidth = with(LocalDensity.current) { (maxWidth * (pageSize.first.toFloat() / documentWidth)).roundToPx() }
        fun point(position: Offset): Offset? {
            val x = .5f + (position.x / viewportPixels - .5f - zoom.x) * documentWidth / (pageSize.first * zoom.scale)
            val y = position.y / pagePixels
            return Offset(x, y).takeIf { x in 0f..1f && y in 0f..1f }
        }
        Box(Modifier.fillMaxWidth().height(pageHeight).clipToBounds().pointerInput(links, zoom, viewportPixels, pagePixels, selection != null) {
            detectTapGestures(onDoubleTap = onDoubleTap,
                onTap = { position -> if (selection != null) onClearSelection() else point(position)?.let { p -> links.firstOrNull { link -> link.bounds.any {
                    it.contains(p.x * pageSize.first, p.y * pageSize.second) } }?.let { onLink(it.target) } } },
                onLongPress = { position -> if (pdf.supportsText) point(position)?.let(onText) })
        }.semantics {
            contentDescription = "PDF page ${index + 1} of ${pdf.pageSizes.size}"
            customActions = (if (pdf.supportsText) listOf(CustomAccessibilityAction("Read or copy page text") { onPageText(); true },
                CustomAccessibilityAction("Select page text") { onSelectPage(); true }) else emptyList()) +
                (if (selection != null) listOf(CustomAccessibilityAction("Copy selection") { onCopySelection(); true },
                    CustomAccessibilityAction("Select all text") { onSelectAll(); true },
                    CustomAccessibilityAction("Clear selection") { onClearSelection(); true }) else emptyList()) +
                links.map { link -> CustomAccessibilityAction(when (val target = link.target) {
                    is PdfLinkTarget.Page -> "Go to page ${target.index + 1}"
                    is PdfLinkTarget.External -> "Open ${target.url}"
                }) { onLink(link.target); true } }
        }) {
            // Raster dimensions stay bounded independently of layout magnification.
            val width = rasterWidth
            val bitmap by produceState<Bitmap?>(null, pdf, index, width) {
                try { value = withContext(Dispatchers.IO) { pdf.render(index, width * 2) } }
                catch (error: Exception) { currentCoroutineContext().ensureActive(); pageFailure = "Could not render this PDF page." }
            }
            val pixelsPerPoint = viewportPixels / documentWidth * zoom.scale
            val detailRequest = if (bitmap != null && pageTop != null) pdfDetailRegion(pageSize.first, pageSize.second,
                viewportPixels, viewportHeight, (viewportPixels - pageSize.first * pixelsPerPoint) / 2 + zoom.x * viewportPixels,
                pageTop, pixelsPerPoint, bitmap!!.width.toFloat() / pageSize.first) else null
            val detail = rememberPdfDetail(pdf, index, detailRequest)
            if (bitmap != null) {
                Box(Modifier.requiredSize(imageWidth, pageHeight).align(Alignment.Center)
                    .graphicsLayer { translationX = zoom.x * viewportPixels }) {
                    Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        detail?.let { rendered ->
                            val ratio = size.width / pageSize.first / rendered.region.scale
                            drawImage(rendered.bitmap.asImageBitmap(),
                                dstOffset = IntOffset((rendered.region.left * ratio).roundToInt(), (rendered.region.top * ratio).roundToInt()),
                                dstSize = IntSize((rendered.bitmap.width * ratio).roundToInt().coerceAtLeast(1),
                                    (rendered.bitmap.height * ratio).roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.High)
                        }
                        highlighted.forEach { b ->
                            drawRect(Color(0x665090FF), Offset(b.left / pageSize.first * size.width, b.top / pageSize.second * size.height),
                                Size((b.right - b.left) / pageSize.first * size.width, (b.bottom - b.top) / pageSize.second * size.height))
                        }
                        matches.forEach { match -> match.bounds.forEach { b ->
                            if (b.contains(b.left, b.top)) drawRect(if (match == selected) Color(0xAAFF9800) else Color(0x66FFE600),
                                Offset(b.left / pageSize.first * size.width, b.top / pageSize.second * size.height),
                                Size((b.right - b.left) / pageSize.first * size.width, (b.bottom - b.top) / pageSize.second * size.height))
                        } }
                    }
                }
            } else if (pageFailure != null) Text(pageFailure!!) else LinearProgressIndicator(Modifier.fillMaxWidth())
            if (linkFailure) Text("Links couldn’t be read on this page.", color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun PdfPageTextDialog(pdf: ChangesPdfDocument, index: Int, point: Offset?, onDismiss: () -> Unit, onWholePage: () -> Unit) {
    val context = LocalContext.current
    var failure by remember(pdf, index, point) { mutableStateOf(false) }
    val content by produceState<Pair<String, Boolean>?>(null, pdf, index, point) {
        value = null
        try { value = withContext(Dispatchers.IO) {
            val word = point?.let { pdf.word(index, it.x, it.y) }
            (word ?: pdf.text(index)) to (word != null)
        } } catch (error: Exception) { currentCoroutineContext().ensureActive(); failure = true }
    }
    val text = content?.first
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (content?.second == true) "Selected text" else "Page ${index + 1} text") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            when {
                failure -> Text("Text couldn't be read. Reopen the document and try again.")
                text == null -> CircularProgressIndicator()
                text!!.isBlank() -> Text("This page has no selectable text.")
                else -> SelectionContainer { Text(text!!) }
            }
            if (content?.second == true) TextButton(onClick = onWholePage) { Text("Select from whole page") }
        } }, confirmButton = { TextButton(enabled = !text.isNullOrBlank(), onClick = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PDF text", text))
            onDismiss()
        }) { Text("Copy") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
