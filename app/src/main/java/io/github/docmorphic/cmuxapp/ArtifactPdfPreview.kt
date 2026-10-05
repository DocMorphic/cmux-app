package io.github.docmorphic.cmuxapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.io.File

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

@Composable
private fun PdfDocumentContent(pdf: ChangesPdfDocument) {
    val context = LocalContext.current
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var matchIndex by rememberSaveable { mutableIntStateOf(0) }
    var matches by remember(pdf) { mutableStateOf<List<PdfTextMatch>>(emptyList()) }
    var scanned by remember(pdf) { mutableIntStateOf(0) }
    var searching by remember(pdf) { mutableStateOf(false) }
    var searchFailure by remember(pdf) { mutableStateOf<String?>(null) }
    var actionFailure by remember(pdf) { mutableStateOf<String?>(null) }
    var textPage by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectionX by rememberSaveable { mutableFloatStateOf(-1f) }
    var selectionY by rememberSaveable { mutableFloatStateOf(-1f) }
    var navigationGeneration by rememberSaveable { mutableIntStateOf(0) }
    var pageWidth by remember { mutableFloatStateOf(0f) }
    val selected = matches.getOrNull(matchIndex)
    fun jump(page: Int, y: Float = 0f) {
        if (page !in pdf.pageSizes.indices) return
        navigationGeneration++
        scope.launch { scroll.scrollToItem(page, if (y.isFinite()) (y.coerceIn(0f, pdf.pageSizes[page].second.toFloat()) * pageWidth / pdf.pageSizes[page].first).toInt() else 0) }
    }
    fun open(link: PdfLinkTarget) {
        when (link) {
            is PdfLinkTarget.Page -> jump(link.index, link.y)
            is PdfLinkTarget.External -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) }
                .onFailure { actionFailure = "No app could open this link." }
        }
    }
    LaunchedEffect(pdf, query, searchOpen) {
        matches = emptyList(); scanned = 0; searchFailure = null; searching = false
        if (!searchOpen || query.isBlank() || !pdf.supportsText) return@LaunchedEffect
        searching = true
        try {
            delay(250)
            for (index in pdf.pageSizes.indices) {
                val found = withContext(Dispatchers.IO) { pdf.search(index, query) }
                ensureActive(); matches = matches + found; scanned = index + 1
            }
            if (matchIndex !in matches.indices) matchIndex = 0
        } catch (error: Exception) { ensureActive(); searchFailure = "This document couldn't be searched. Try another query or reopen it." }
        finally { searching = false }
    }
    LaunchedEffect(selected, pageWidth) { selected?.let { jump(it.page, it.bounds.firstOrNull()?.top ?: 0f) } }
    textPage?.let { index ->
        PdfPageTextDialog(pdf, index, if (selectionX >= 0 && selectionY >= 0) Offset(selectionX, selectionY) else null,
            onDismiss = { textPage = null; selectionX = -1f; selectionY = -1f }, onWholePage = { selectionX = -1f; selectionY = -1f })
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            TextButton(enabled = scroll.firstVisibleItemIndex > 0, onClick = { jump(scroll.firstVisibleItemIndex - 1) }) { Text("Previous page") }
            Text("${scroll.firstVisibleItemIndex + 1} / ${pdf.pageSizes.size}", fontSize = 12.sp)
            TextButton(enabled = scroll.firstVisibleItemIndex < pdf.pageSizes.lastIndex, onClick = { jump(scroll.firstVisibleItemIndex + 1) }) { Text("Next page") }
        }
        if (pdf.supportsText) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { searchOpen = !searchOpen }) { Text(if (searchOpen) "Close search" else "Search document") }
            TextButton(onClick = { selectionX = -1f; selectionY = -1f; textPage = scroll.firstVisibleItemIndex }) { Text("Page text") }
        }
        if (searchOpen && pdf.supportsText) {
            OutlinedTextField(query, { query = it.take(1024); matchIndex = 0 }, singleLine = true,
                label = { Text("Search PDF") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                TextButton(enabled = matches.isNotEmpty(), onClick = { matchIndex = pdfMatchStep(matchIndex, -1, matches.size) }) { Text("Previous match") }
                Text(if (matches.isNotEmpty()) "${matchIndex.coerceAtMost(matches.lastIndex) + 1} / ${matches.size}" else if (query.isBlank()) "" else if (searching) "Searching…" else "No matches", fontSize = 12.sp,
                    modifier = Modifier.semantics { contentDescription = "PDF search results"; liveRegion = LiveRegionMode.Polite })
                TextButton(enabled = matches.isNotEmpty(), onClick = { matchIndex = pdfMatchStep(matchIndex, 1, matches.size) }) { Text("Next match") }
            }
            if (searching) LinearProgressIndicator(progress = { scanned.toFloat() / pdf.pageSizes.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            searchFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        actionFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        BoxWithConstraints(Modifier.weight(1f)) {
            val lastSize = pdf.pageSizes.lastOrNull()
            val lastHeight = lastSize?.let { maxWidth * (it.second.toFloat() / it.first) } ?: maxHeight
            val endPadding = maxOf(0.dp, maxHeight - lastHeight)
            val width = with(LocalDensity.current) { maxWidth.toPx() }
            SideEffect { pageWidth = width }
            LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(bottom = endPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pdf.pageSizes.size, key = { it }) { index ->
                    PdfDocumentPage(pdf, index, matches.filter { it.page == index }, selected, navigationGeneration,
                        onLink = ::open, onText = { point -> selectionX = point.x; selectionY = point.y; textPage = index },
                        onPageText = { selectionX = -1f; selectionY = -1f; textPage = index })
                }
            }
        }
    }
}

@Composable
private fun PdfDocumentPage(pdf: ChangesPdfDocument, index: Int, matches: List<PdfTextMatch>, selected: PdfTextMatch?, navigationGeneration: Int,
    onLink: (PdfLinkTarget) -> Unit, onText: (Offset) -> Unit, onPageText: () -> Unit) {
    val pageSize = pdf.pageSizes[index]
    var pageFailure by remember(pdf, index) { mutableStateOf<String?>(null) }
    var linkFailure by remember(pdf, index) { mutableStateOf(false) }
    val links by produceState<List<PdfDocumentLink>>(emptyList(), pdf, index) {
        try { value = withContext(Dispatchers.IO) { pdf.links(index) } }
        catch (error: Exception) { currentCoroutineContext().ensureActive(); linkFailure = true }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(pageSize.first.toFloat() / pageSize.second).semantics {
        contentDescription = "PDF page ${index + 1} of ${pdf.pageSizes.size}"
        if (pdf.supportsText) customActions = listOf(CustomAccessibilityAction("Read or copy page text") { onPageText(); true }) +
            links.map { link -> CustomAccessibilityAction(when (val target = link.target) {
                is PdfLinkTarget.Page -> "Go to page ${target.index + 1}"
                is PdfLinkTarget.External -> "Open ${target.url}"
            }) { onLink(link.target); true } }
    }) {
        val width = with(LocalDensity.current) { maxWidth.roundToPx() }
        val bitmap by produceState<Bitmap?>(null, pdf, index, width) {
            try { value = withContext(Dispatchers.IO) { pdf.render(index, width * 2) } }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); pageFailure = "Could not render this PDF page." }
        }
        if (bitmap != null) PreviewZoom(Modifier.fillMaxSize(), doubleTapScale = 2f, resetGeneration = navigationGeneration,
            onContentTap = { point -> links.firstOrNull { link -> link.bounds.any { it.contains(point.x * pageSize.first, point.y * pageSize.second) } }?.let { onLink(it.target) } },
            onContentLongPress = if (pdf.supportsText) onText else null) { modifier ->
            Box(modifier) {
                Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
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
