package io.github.docmorphic.cmuxapp

import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.ceil
import kotlin.math.floor

internal data class PdfSelectionViewportPage(val index: Int, val bounds: PdfTextBounds)

/** Visible selected region in viewport pixels. Hidden endpoints must not pin a menu to an unrelated page. */
internal fun pdfSelectionToolbarBounds(range: PdfTextSelection, start: Offset?, end: Offset?,
    pages: List<PdfSelectionViewportPage>, width: Float, height: Float, linePadding: Float): PdfTextBounds? {
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f || range.start == range.end) return null
    val rectangles = pages.mapNotNull { page ->
        if (page.index !in range.start.page..range.end.page || page.index == range.end.page && range.end.offset == 0) return@mapNotNull null
        var left = page.bounds.left; var right = page.bounds.right
        var top = page.bounds.top; var bottom = page.bounds.bottom
        if (page.index == range.start.page && start != null) top = start.y - linePadding
        if (page.index == range.end.page && end != null) bottom = end.y + linePadding
        if (range.start.page == range.end.page && start != null && end != null) {
            top = minOf(start.y, end.y) - linePadding; bottom = maxOf(start.y, end.y) + linePadding
            if (kotlin.math.abs(start.y - end.y) <= linePadding) {
                left = minOf(start.x, end.x) - linePadding / 2; right = maxOf(start.x, end.x) + linePadding / 2
            }
        }
        left = left.coerceAtLeast(0f); right = right.coerceAtMost(width)
        top = top.coerceAtLeast(0f); bottom = bottom.coerceAtMost(height)
        if (listOf(left, top, right, bottom).any { !it.isFinite() } || left >= right || top >= bottom) null
        else PdfTextBounds(left, top, right, bottom)
    }
    if (rectangles.isEmpty()) return null
    return PdfTextBounds(rectangles.minOf { it.left }, rectangles.minOf { it.top },
        rectangles.maxOf { it.right }, rectangles.maxOf { it.bottom })
}

/** Native floating mode keeps selection controls out of document measurement and scroll anchoring. */
private class PdfSelectionActionMode(private val view: View) {
    private var mode: ActionMode? = null
    private var rect = Rect()
    private var actions: List<() -> Unit> = emptyList()
    fun show(bounds: PdfTextBounds, copy: () -> Unit, page: () -> Unit, all: () -> Unit, clear: () -> Unit) {
        rect = Rect(floor(bounds.left).toInt(), floor(bounds.top).toInt(), ceil(bounds.right).toInt(), ceil(bounds.bottom).toInt())
        actions = listOf(copy, all, page, clear)
        if (mode == null) mode = view.startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val titles = listOf(view.context.getString(android.R.string.copy),
                    view.context.getString(android.R.string.selectAll), "Select page", "Clear selection")
                titles.forEachIndexed { index, title ->
                    menu.add(Menu.NONE, index + 1, index, title).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val action = actions.getOrNull(item.itemId - 1) ?: return false
                action(); return true
            }
            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) { outRect.set(rect) }
            override fun onDestroyActionMode(mode: ActionMode) {
                if (this@PdfSelectionActionMode.mode === mode) this@PdfSelectionActionMode.mode = null
                // Framework dismissal during rotation/backgrounding must not erase the saved selection.
            }
        }, ActionMode.TYPE_FLOATING)
        else mode?.invalidateContentRect()
    }
    fun hide() {
        val current = mode; mode = null; current?.finish(); actions = emptyList()
    }
}

@Composable
internal fun PdfSelectionToolbar(bounds: PdfTextBounds?, visible: Boolean,
    onCopy: () -> Unit, onPage: () -> Unit, onAll: () -> Unit, onClear: () -> Unit) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val toolbar = remember(view) { PdfSelectionActionMode(view) }
    DisposableEffect(lifecycle, toolbar) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); toolbar.hide() }
    }
    SideEffect {
        if (visible && resumed && bounds != null) toolbar.show(bounds, onCopy, onPage, onAll, onClear)
        else toolbar.hide()
    }
}
