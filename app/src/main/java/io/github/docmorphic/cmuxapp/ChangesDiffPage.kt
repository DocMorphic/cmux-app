package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun clampDiffFont(value: Float) = if (value.isFinite()) value.coerceIn(9f, 22f) else 12f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChangesDiffPage(store: ChangesStore, file: ChangedFile, fontSize: Float,
    onFont: (Float) -> Unit, onPersistFont: (Float) -> Unit) {
    val page = remember(store, file.path) { store.page(file.path) }
    val state by page.collectAsState()
    val clipboard = LocalClipboardManager.current
    val saved = remember(store, file.path) { store.scrollPositions[file.path] }
    val scroll = rememberLazyListState(saved?.first ?: 0, saved?.second ?: 0)
    LaunchedEffect(store, file.path) { store.load(file.path)?.join() }
    DisposableEffect(store, file.path, scroll) { onDispose { store.scrollPositions[file.path] = scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset } }
    Column(Modifier.fillMaxSize().semantics { contentDescription = "Diff ${file.path}" }) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(file.path, Modifier.weight(1f), color = changesMuted, fontSize = 11.sp)
            Text("${fontSize.toInt()} pt", Modifier.semantics { contentDescription = "Diff text size ${fontSize.toInt()}" }, fontSize = 11.sp, color = changesMuted)
            IconButton(onClick = { store.load(file.path, force = true) }, enabled = !state.loading,
                modifier = Modifier.semantics { contentDescription = "Refresh diff ${file.path}" }) {
                Icon(painterResource(R.drawable.ic_browser_reload), null, Modifier.size(18.dp))
            }
        }
        if (state.error != null) ChangesNotice("Couldn't load diff", state.error.orEmpty()) {
            store.load(file.path, force = !state.failedContinuation, more = state.failedContinuation)
        }
        val document = state.document
        if (document == null) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (document.binary) {
            ChangesNotice(file.filename, "Binary file")
        } else {
            val continuation = DiffContinuation(state.budget, document, state.ceiling)
            val gutter = (maxOf(2, document.maximumLineNumber.toString().length) * fontSize * .64f * LocalDensity.current.fontScale + 8).dp
            PullToRefreshBox(state.loading, { store.load(file.path, force = true) }, Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().diffFontGesture(fontSize, onFont, onPersistFont), state = scroll) {
                    if (document.hunks.isEmpty()) item { ChangesNotice("No text changes", "This file has no textual diff hunks.") }
                    document.hunks.forEachIndexed { hunkIndex, hunk ->
                        item("$hunkIndex:header") {
                            ChangesDiffRow(ChangesDiffLine(DiffKind.HEADER, hunk.header), hunk.copyText, gutter, fontSize) {
                                clipboard.setText(AnnotatedString(it))
                            }
                        }
                        items(hunk.lines.size, key = { "$hunkIndex:line:$it" }) { index ->
                            ChangesDiffRow(hunk.lines[index], hunk.copyText, gutter, fontSize) { clipboard.setText(AnnotatedString(it)) }
                        }
                        item("$hunkIndex:space") { Spacer(Modifier.height(8.dp)) }
                    }
                    if (document.truncated) item("continuation") {
                        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Showing ${continuation.shownLines}${document.totalLines?.let { " of $it" }.orEmpty()} diff lines", color = changesMuted, fontSize = 12.sp)
                            if (continuation.canGrow) TextButton(onClick = { store.load(file.path, more = true) }, enabled = !state.loading) {
                                Text(if (state.loading) "Loading…" else "Show more lines")
                            } else Text("See the remaining diff on your Mac.", color = changesMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangesDiffRow(line: ChangesDiffLine, hunk: String, gutter: androidx.compose.ui.unit.Dp,
    size: Float, copy: (String) -> Unit) {
    var menu by remember(line) { mutableStateOf(false) }
    val background = when (line.kind) {
        DiffKind.ADDITION -> Color(0x262EA043)
        DiffKind.REMOVAL -> Color(0x26F85149)
        DiffKind.HEADER -> Color(0x26388BFD)
        else -> Color.Transparent
    }
    val emphasis = if (line.kind == DiffKind.ADDITION) Color(0x662EA043) else Color(0x66F85149)
    val text = remember(line, emphasis) { buildAnnotatedString {
        append(line.text)
        line.emphasis?.let { addStyle(SpanStyle(background = emphasis), it.first, it.last + 1) }
    } }
    Box(Modifier.fillMaxWidth().background(background)) {
        val interaction = if (line.kind == DiffKind.NO_NEWLINE) Modifier else Modifier
            .combinedClickable(onClick = {}, onLongClick = { menu = true })
            .semantics { customActions = listOf(CustomAccessibilityAction("Copy Line") { copy(line.text); true },
                CustomAccessibilityAction("Copy Hunk") { copy(hunk); true }) }
        when (line.kind) {
            DiffKind.NO_NEWLINE -> Text("↳ No newline at end of file", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = changesMuted, fontSize = 10.sp)
            DiffKind.HEADER -> Text(line.text, Modifier.fillMaxWidth().then(interaction).padding(horizontal = 8.dp, vertical = 5.dp),
                color = Color(0xFF8BB5F6), fontFamily = FontFamily.Monospace, fontSize = size.sp)
            else -> Row(Modifier.fillMaxWidth().then(interaction).padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                for ((label, number) in listOf("Old" to line.oldNumber, "New" to line.newNumber)) Text(number?.toString().orEmpty(),
                    Modifier.width(gutter).padding(end = 3.dp).semantics { contentDescription = "$label line ${number ?: "none"}" },
                    color = changesMuted, fontFamily = FontFamily.Monospace, fontSize = size.sp, textAlign = TextAlign.End)
                Text(when (line.kind) { DiffKind.ADDITION -> "+"; DiffKind.REMOVAL -> "−"; else -> " " }, Modifier.width(12.dp),
                    color = if (line.kind == DiffKind.ADDITION) changesAdded else changesRemoved, fontFamily = FontFamily.Monospace, fontSize = size.sp)
                SelectionContainer(Modifier.weight(1f).padding(start = 3.dp, end = 6.dp)) {
                    Text(text.ifEmpty { AnnotatedString(" ") }, fontFamily = FontFamily.Monospace, fontSize = size.sp,
                        color = Color(0xFFD5D9E0), softWrap = true)
                }
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Copy Line") }, onClick = { copy(line.text); menu = false })
            DropdownMenuItem(text = { Text("Copy Hunk") }, onClick = { copy(hunk); menu = false })
        }
    }
}

@Composable
private fun Modifier.diffFontGesture(font: Float, update: (Float) -> Unit, persist: (Float) -> Unit): Modifier {
    val latestFont by rememberUpdatedState(font)
    val latestUpdate by rememberUpdatedState(update)
    val latestPersist by rememberUpdatedState(persist)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var start: Float? = null
            var factor = 1f
            var result = latestFont
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    if (start == null) start = latestFont
                    factor *= event.calculateZoom()
                    result = clampDiffFont(checkNotNull(start) * factor)
                    latestUpdate(result)
                    event.changes.filter { it.positionChanged() }.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (start != null) latestPersist(result)
        }
    }
}
