package io.github.docmorphic.cmuxapp

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Parcelable
import android.text.Selection
import android.text.Spannable
import android.util.SparseArray
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.PrecomputedTextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import kotlinx.coroutines.*
import java.util.Locale

/** Only an anchor, selection and horizontal coordinates are saved; no message text or spans. */
internal class NativeMarkdownPosition {
    var block = 0; var character = 0; var fraction = 0f; var rowOffsetDp = 0f; var insideText = false
    var selectedBlock = -1; var selectionStart = -1; var selectionEnd = -1
    val horizontalDp = mutableMapOf<Int, Float>()
    var capture: (() -> Unit)? = null

    companion object {
        val Saver = listSaver<NativeMarkdownPosition, Any>(save = {
            it.capture?.invoke()
            listOf(it.block, it.character, it.fraction, it.rowOffsetDp, it.insideText,
                it.selectedBlock, it.selectionStart, it.selectionEnd,
                ArrayList(it.horizontalDp.entries.flatMap { e -> listOf(e.key, e.value) }))
        }, restore = { values -> NativeMarkdownPosition().apply {
            block = values[0] as Int; character = values[1] as Int; fraction = values[2] as Float
            rowOffsetDp = values[3] as Float; insideText = values[4] as Boolean
            selectedBlock = values[5] as Int; selectionStart = values[6] as Int; selectionEnd = values[7] as Int
            (values[8] as List<*>).chunked(2).forEach { horizontalDp[it[0] as Int] = it[1] as Float }
        } })
    }
}

internal data class NativeMarkdownColors(val foreground: Int, val secondary: Int, val accent: Int)

/** Android lazy native rows avoid Compose's packed item-height limit for large paragraphs. */
internal class NativeMarkdownList(context: Context, val blocks: List<NativeMarkdownBlock>,
    private val position: NativeMarkdownPosition, internal var colors: NativeMarkdownColors) : ListView(context) {
    internal val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal var released = false
    private data class Anchor(val block: Int, val character: Int, val fraction: Float, val offset: Float, val inside: Boolean)
    private var pending: Anchor? = Anchor(position.block, position.character, position.fraction, position.rowOffsetDp, position.insideText)
    private var restoring = false
    private val capture: () -> Unit = { capturePosition() }
    internal fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()
    internal fun horizontal(index: Int) = position.horizontalDp[index]
    internal fun horizontal(index: Int, x: Int) { if (!released) position.horizontalDp[index] = x / resources.displayMetrics.density }

    init {
        tag = "AgentFeedNativeMarkdown"
        isSaveEnabled = false; divider = null; dividerHeight = 0
        selector = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        setPadding(dp(16f), dp(16f), dp(16f), dp(16f)); clipToPadding = false
        itemsCanFocus = true; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        adapter = object : BaseAdapter() {
            override fun getCount() = blocks.size
            override fun getItem(index: Int) = blocks[index]
            override fun getItemId(index: Int) = index.toLong()
            override fun hasStableIds() = true
            override fun getView(index: Int, recycled: View?, parent: ViewGroup): View =
                ((recycled as? NativeMarkdownRow) ?: NativeMarkdownRow(this@NativeMarkdownList)).also { it.bind(blocks[index]) }
        }
        setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, state: Int) {
                if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) pending = null
                capturePosition()
            }
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                // A single paragraph may be much taller than the viewport;
                // update TextView's clipped display list when its parent scrolls.
                for (i in 0 until childCount) (getChildAt(i) as? NativeMarkdownRow)?.body?.invalidate()
                capturePosition()
            }
        })
        position.capture = capture
        post { pending?.let { setSelectionFromTop(it.block.coerceIn(0, (blocks.size - 1).coerceAtLeast(0)), dp(it.offset)) } }
    }

    override fun dispatchSaveInstanceState(container: SparseArray<Parcelable>) = Unit
    override fun dispatchRestoreInstanceState(container: SparseArray<Parcelable>) = Unit
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) pending = null
        return super.onTouchEvent(event)
    }

    internal fun captureSelection(row: NativeMarkdownRow) {
        val text = row.body ?: return
        val index = row.block?.index ?: return
        if (text.hasSelection()) {
            position.selectedBlock = index; position.selectionStart = text.selectionStart; position.selectionEnd = text.selectionEnd
        }
    }

    internal fun restoreSelection(row: NativeMarkdownRow) {
        val body = row.body ?: return
        if (position.selectedBlock == row.block?.index && position.selectionStart >= 0 && position.selectionEnd >= 0) {
            (body.text as? Spannable)?.let { Selection.setSelection(it, position.selectionStart.coerceAtMost(it.length), position.selectionEnd.coerceAtMost(it.length)) }
        }
    }

    private fun capturePosition() {
        if (pending != null || restoring || released) return
        for (i in 0 until childCount) (getChildAt(i) as? NativeMarkdownRow)?.let(::captureSelection)
        val row = getChildAt(0) as? NativeMarkdownRow ?: return
        if (!row.ready) return
        position.block = firstVisiblePosition
        position.rowOffsetDp = (row.top - paddingTop) / resources.displayMetrics.density
        position.insideText = false; position.character = 0; position.fraction = 0f
        val body = row.body ?: return
        val layout = body.layout ?: return
        val rect = Rect(0, 0, body.width, body.height); offsetDescendantRectToMyCoords(body, rect)
        val y = paddingTop - rect.top - body.totalPaddingTop
        if (y > 0) {
            val line = layout.getLineForVertical(y)
            position.insideText = true; position.character = layout.getLineStart(line)
            position.fraction = (y - layout.getLineTop(line)).toFloat() / (layout.getLineBottom(line) - layout.getLineTop(line)).coerceAtLeast(1)
        }
    }

    internal fun rowLaidOut(row: NativeMarkdownRow) {
        val anchor = pending ?: return
        if (!row.ready || row.block?.index != anchor.block || restoring || released) return
        val body = row.body
        if (anchor.inside && body?.layout == null) return
        restoring = true
        postOnAnimation {
            if (!released && pending === anchor) {
                val offset = if (anchor.inside && body != null) {
                    val layout = body.layout
                    val line = layout.getLineForOffset(anchor.character.coerceIn(0, body.text.length))
                    val rect = Rect(0, 0, body.width, body.height); row.offsetDescendantRectToMyCoords(body, rect)
                    -rect.top - body.totalPaddingTop - layout.getLineTop(line) -
                        ((layout.getLineBottom(line) - layout.getLineTop(line)) * anchor.fraction).toInt()
                } else dp(anchor.offset)
                setSelectionFromTop(anchor.block, offset)
                postOnAnimation { if (pending === anchor) pending = null; restoring = false; capturePosition() }
            } else restoring = false
        }
    }

    fun updateColors(value: NativeMarkdownColors) {
        if (colors == value) return
        colors = value
        for (i in 0 until childCount) (getChildAt(i) as? NativeMarkdownRow)?.applyColors()
    }

    fun release() {
        capturePosition(); released = true
        if (position.capture === capture) position.capture = null
        work.cancel()
    }
}

internal class NativeMarkdownRow(private val reader: NativeMarkdownList) : LinearLayout(reader.context) {
    var block: NativeMarkdownBlock? = null; private set
    var body: TextView? = null; private set
    var ready = false; private set
    private var pending: Job? = null
    private var marker: TextView? = null; private var caption: TextView? = null
    private var bar: View? = null; private var card: LinearLayout? = null
    private var horizontalScroller: HorizontalScrollView? = null
    private var restoreHorizontal = false
    private fun dp(value: Float) = reader.dp(value)
    private fun typeface(family: Typeface, weight: Int): Typeface =
        if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(family, weight, false)
        else Typeface.create(family, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)

    init { orientation = VERTICAL; isSaveEnabled = false; layoutParams = AbsListView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT) }

    fun bind(value: NativeMarkdownBlock) {
        if (block?.index == value.index) { applyColors(); return }
        reader.captureSelection(this); pending?.cancel(); removeAllViews()
        block = value; body = null; marker = null; caption = null; bar = null; card = null; ready = false
        horizontalScroller = null; restoreHorizontal = false
        tag = "NativeMarkdownRow.${value.index}"
        setPadding(0, if (value.kind == NativeMarkdownBlock.Kind.HEADING && value.index != 0) dp(4f) else 0,
            0, if (value.index == reader.blocks.lastIndex) 0 else dp(12f))
        if (value.kind == NativeMarkdownBlock.Kind.RULE) {
            val rule = View(context).also { it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; bar = it }
            addView(rule, LayoutParams(LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(4f); bottomMargin = dp(4f) })
            ready = true; applyColors(); return
        }
        val text = agentFeedQuoteTextView(context).apply {
            tag = "NativeMarkdownBody.${value.index}"; isSaveEnabled = false
            // Source SwiftUI leading alignment follows the interface direction,
            // independently of the paragraph's first strong character.
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            textSize = when (value.kind) {
                NativeMarkdownBlock.Kind.HEADING -> when (value.level) { 1 -> 28f; 2 -> 22f; 3 -> 20f; else -> 17f }
                else -> 17f
            }
            setTextIsSelectable(true)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            val monospace = value.kind in setOf(NativeMarkdownBlock.Kind.CODE, NativeMarkdownBlock.Kind.TABLE_ROW)
            val weight = when { value.kind == NativeMarkdownBlock.Kind.HEADING -> if (value.level <= 2) 700 else 600; value.header -> 600; else -> 400 }
            typeface = typeface(if (monospace) Typeface.MONOSPACE else Typeface.DEFAULT, weight)
            ViewCompat.setAccessibilityHeading(this, value.kind == NativeMarkdownBlock.Kind.HEADING)
            NativeViewHaptics(this)
            text = " "
        }
        body = text
        when (value.kind) {
            NativeMarkdownBlock.Kind.BULLET, NativeMarkdownBlock.Kind.ORDERED -> {
                val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.TOP; isBaselineAligned = true
                    setPaddingRelative(dp(minOf(value.indent, 4) * 16f), 0, 0, 0) }
                marker = agentFeedQuoteTextView(context).apply {
                    this.text = if (value.kind == NativeMarkdownBlock.Kind.BULLET) "•" else value.marker
                    textSize = 17f; minWidth = dp(20f); gravity = Gravity.END; isSaveEnabled = false
                    // Numeric markers retain their authored punctuation in RTL.
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
                }
                row.addView(marker, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8f) })
                row.addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)); addView(row)
            }
            NativeMarkdownBlock.Kind.QUOTE -> {
                val row = LinearLayout(context).apply { orientation = HORIZONTAL; isBaselineAligned = false }
                bar = View(context).also { it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
                row.addView(bar, LayoutParams(dp(3f), LayoutParams.MATCH_PARENT).apply { marginEnd = dp(10f) })
                row.addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)); addView(row)
            }
            NativeMarkdownBlock.Kind.CODE, NativeMarkdownBlock.Kind.TABLE_ROW -> {
                val wrapper = LinearLayout(context).apply { orientation = VERTICAL }
                if (value.kind == NativeMarkdownBlock.Kind.CODE) {
                    wrapper.setPadding(dp(12f), dp(12f), dp(12f), dp(12f)); card = wrapper
                    value.language?.takeIf { it.isNotEmpty() }?.let { language ->
                        caption = agentFeedQuoteTextView(context).apply { this.text = language.uppercase(Locale.ROOT); textSize = 11f
                            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                            typeface = typeface(Typeface.MONOSPACE, 600); isSaveEnabled = false }
                        wrapper.addView(caption, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6f) })
                    }
                } else if (value.header) wrapper.setPadding(0, dp(4f), 0, dp(4f))
                // The scroll view supplies an unconstrained measurement. Keep
                // TextView's layout at that intrinsic width: its separate
                // horizontally-scrolling mode uses a 1,048,576-px virtual line,
                // which sends right-aligned RTL code outside the measured view.
                text.setHorizontallyScrolling(false)
                val horizontal = HorizontalScrollView(context).apply { isSaveEnabled = false
                    isHorizontalScrollBarEnabled = false; addView(text, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)) }
                horizontalScroller = horizontal; restoreHorizontal = true
                horizontal.setOnScrollChangeListener { _, x, _, _, _ -> if (!restoreHorizontal) reader.horizontal(value.index, x) }
                wrapper.addView(horizontal, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)); addView(wrapper)
            }
            else -> addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        applyColors()
        val params = TextViewCompat.getTextMetricsParams(text)
        pending = reader.work.launch {
            val styled = withContext(Dispatchers.Default) {
                val content = if (value.kind in setOf(NativeMarkdownBlock.Kind.CODE, NativeMarkdownBlock.Kind.TABLE_ROW))
                    value.text.ifEmpty { " " } else try { agentFeedSpanned(AgentFeedMarkdown.parseFull(value.text), nativeDocument = true) }
                    catch (error: Exception) { currentCoroutineContext().ensureActive(); value.text }
                PrecomputedTextCompat.create(content, params)
            }
            if (reader.released || block?.index != value.index) return@launch
            TextViewCompat.setPrecomputedText(text, styled)
            reader.restoreSelection(this@NativeMarkdownRow); ready = true; requestLayout()
        }
    }

    fun applyColors() {
        body?.setTextColor(if (block?.kind == NativeMarkdownBlock.Kind.QUOTE) reader.colors.secondary else reader.colors.foreground)
        body?.setLinkTextColor(reader.colors.accent); marker?.setTextColor(reader.colors.secondary); caption?.setTextColor(reader.colors.secondary)
        bar?.setBackgroundColor((reader.colors.secondary and 0x00ffffff) or 0x66000000)
        card?.background = GradientDrawable().apply { cornerRadius = dp(8f).toFloat(); setColor((reader.colors.secondary and 0x00ffffff) or 0x19000000) }
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (ready && restoreHorizontal) {
            horizontalScroller?.let { scroll ->
                val saved = reader.horizontal(block!!.index)
                val end = (scroll.getChildAt(0).width - scroll.width + scroll.paddingLeft + scroll.paddingRight).coerceAtLeast(0)
                scroll.scrollTo(saved?.let(::dp) ?: if (layoutDirection == View.LAYOUT_DIRECTION_RTL) end else 0, 0)
            }
            restoreHorizontal = false
        }
        reader.rowLaidOut(this)
    }
}

@Composable
internal fun NativeMarkdownReader(markdown: String, position: NativeMarkdownPosition) = key(markdown) {
    val blocks by produceState<List<NativeMarkdownBlock>?>(null, markdown) {
        value = withContext(Dispatchers.Default) {
            val owner = currentCoroutineContext()
            NativeMarkdownDocument.parse(markdown) { owner.ensureActive() }
        }
    }
    val colors = NativeMarkdownColors(NativeAppColors.text.toArgb(), NativeAppColors.muted.toArgb(), NativeAppColors.accent.toArgb())
    if (blocks == null) Box(Modifier.fillMaxSize().testTag("NativeMarkdownLoading"), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else AndroidView(factory = { NativeMarkdownList(it, blocks!!, position, colors) }, update = { it.updateColors(colors) },
        modifier = Modifier.fillMaxSize().clipToBounds(), onRelease = { it.release() })
}
