package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.*

@Stable
internal class PdfSelectionState(private val pdf: ChangesPdfDocument, private val scope: CoroutineScope, initial: PdfTextSelection?) {
    var range by mutableStateOf(initial)
        private set
    var anchorCaret by mutableStateOf<PdfTextCaret?>(null)
        private set
    var focusCaret by mutableStateOf<PdfTextCaret?>(null)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var copying by mutableStateOf(false)
        private set
    private var mutation: Job? = null
    private var copyJob: Job? = null
    private var revision = 0L

    fun clear() {
        revision++; mutation?.cancel(); copyJob?.cancel(); copying = false
        range = null; anchorCaret = null; focusCaret = null; failure = null
    }
    private suspend fun publish(value: PdfTextSelection) {
        val carets = withContext(Dispatchers.IO) {
            require(value.anchor.page in pdf.pageSizes.indices && value.focus.page in pdf.pageSizes.indices)
            pdf.selectionText(value.anchor.page).caret(value.anchor, value.anchor <= value.focus) to
                pdf.selectionText(value.focus.page).caret(value.focus, value.focus < value.anchor)
        }
        currentCoroutineContext().ensureActive()
        range = value; anchorCaret = carets.first; focusCaret = carets.second; failure = null
    }
    fun restore() {
        val saved = range ?: return
        mutation = scope.launch { try { publish(saved) } catch (error: Exception) {
            ensureActive(); clear(); failure = "The saved selection couldn't be restored. Select the text again."
        } }
    }
    fun selectWord(page: Int, x: Float, y: Float) {
        clear()
        mutation = scope.launch {
            try {
                val offsets = withContext(Dispatchers.IO) {
                    val size = pdf.pageSizes[page]
                    pdf.selectionText(page).wordOffsets(x * size.first, y * size.second)
                }
                ensureActive()
                if (offsets == null) { failure = "No selectable text here."; return@launch }
                publish(PdfTextSelection(PdfTextPosition(page, offsets.first), PdfTextPosition(page, offsets.last + 1)))
            } catch (error: Exception) { ensureActive(); failure = "Text couldn't be selected. Try Page text or reopen the document." }
        }
    }
    fun move(anchor: Boolean, fixed: PdfTextPosition, page: Int, x: Float, y: Float) {
        mutation?.cancel(); revision++
        mutation = scope.launch {
            try {
                val offset = withContext(Dispatchers.IO) { pdf.selectionText(page).nearestOffset(x, y) } ?: return@launch
                ensureActive()
                val moving = PdfTextPosition(page, offset)
                publish(if (anchor) PdfTextSelection(moving, fixed) else PdfTextSelection(fixed, moving))
            } catch (error: Exception) { ensureActive(); failure = "This page's text couldn't be selected." }
        }
    }
    fun selectPage(page: Int) {
        clear()
        mutation = scope.launch {
            try {
                val length = withContext(Dispatchers.IO) { pdf.selectionText(page).text.length }
                ensureActive()
                if (length == 0) { failure = "This page has no selectable text."; return@launch }
                publish(PdfTextSelection(PdfTextPosition(page, 0), PdfTextPosition(page, length)))
            } catch (error: Exception) { ensureActive(); failure = "This page's text couldn't be selected." }
        }
    }
    fun selectAll() {
        clear()
        mutation = scope.launch {
            try {
                val endpoints = withContext(Dispatchers.IO) {
                    var first = 0; var last = pdf.pageSizes.lastIndex
                    while (first <= last) { ensureActive(); if (pdf.selectionText(first).text.isNotBlank()) break; first++ }
                    while (last >= first) { ensureActive(); if (pdf.selectionText(last).text.isNotBlank()) break; last-- }
                    if (first > last) null else PdfTextSelection(PdfTextPosition(first, 0), PdfTextPosition(last, pdf.selectionText(last).text.length))
                }
                ensureActive()
                if (endpoints == null) { failure = "This document has no selectable text."; return@launch }
                publish(endpoints)
            } catch (error: Exception) { ensureActive(); failure = "The document's text couldn't be selected." }
        }
    }
    fun adjust(anchor: Boolean, forward: Boolean) {
        val saved = range ?: return
        mutation?.cancel(); revision++
        mutation = scope.launch {
            try {
                val position = if (anchor) saved.anchor else saved.focus
                val next = withContext(Dispatchers.IO) {
                    val text = pdf.selectionText(position.page).text
                    val breaks = java.text.BreakIterator.getWordInstance(java.util.Locale.ROOT).apply { setText(text) }
                    val offset = if (forward) breaks.following(position.offset.coerceAtMost(text.length))
                        else breaks.preceding(position.offset.coerceAtMost(text.length))
                    when {
                        offset != java.text.BreakIterator.DONE -> PdfTextPosition(position.page, offset)
                        forward && position.page < pdf.pageSizes.lastIndex -> PdfTextPosition(position.page + 1, 0)
                        !forward && position.page > 0 -> PdfTextPosition(position.page - 1, pdf.selectionText(position.page - 1).text.length)
                        else -> position
                    }
                }
                ensureActive()
                publish(if (anchor) saved.copy(anchor = next) else saved.copy(focus = next))
            } catch (error: Exception) { ensureActive(); failure = "The selection couldn't be adjusted." }
        }
    }
    fun copy(write: (String) -> Unit) {
        val saved = range ?: return
        copyJob?.cancel(); val capturedRevision = revision; copying = true
        copyJob = scope.launch {
            try {
                val value = withContext(Dispatchers.IO) { pdf.selectedText(saved) { ensureActive() } }
                ensureActive()
                if (range != saved || revision != capturedRevision) return@launch
                if (value.isBlank()) { failure = "This selection has no text."; return@launch }
                write(value); clear()
            } catch (error: Exception) { ensureActive(); failure = "Couldn't copy this selection. Try a smaller range or Page text." }
            finally { copying = false }
        }
    }
}

@Composable
internal fun rememberPdfSelection(pdf: ChangesPdfDocument): PdfSelectionState {
    val scope = rememberCoroutineScope()
    val saver = remember(pdf, scope) { listSaver<PdfSelectionState, Int>(save = { state ->
        state.range?.let { listOf(it.anchor.page, it.anchor.offset, it.focus.page, it.focus.offset) } ?: emptyList()
    }, restore = { data -> PdfSelectionState(pdf, scope, if (data.size == 4 && data.all { it >= 0 } &&
        data[0] in pdf.pageSizes.indices && data[2] in pdf.pageSizes.indices)
        PdfTextSelection(PdfTextPosition(data[0], data[1]), PdfTextPosition(data[2], data[3])) else null) }) }
    val state = rememberSaveable(pdf, saver = saver) { PdfSelectionState(pdf, scope, null) }
    LaunchedEffect(state) { state.restore() }
    DisposableEffect(state) { onDispose { state.clear() } }
    return state
}
