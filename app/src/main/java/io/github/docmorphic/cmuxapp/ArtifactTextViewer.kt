package io.github.docmorphic.cmuxapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.BackgroundColorSpan
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*

@Stable
internal class ArtifactViewerState(context: Context, val artifact: LocalFilePreview) {
    val markdown = MarkdownPreviewPolicy.isMarkdown(artifact.file.name, artifact.mime)
    val renderedAvailable = markdown && MarkdownPreviewPolicy.renderedAvailable(artifact.size)
    var rendered by mutableStateOf(renderedAvailable)
    var failure by mutableStateOf<String?>(null)
    var document by mutableStateOf<ArtifactTextDocument?>(null)
    var syntax by mutableStateOf<ArtifactSyntaxResult?>(null)
    var highlightedDocument: ArtifactTextDocument? = null
    var searchOpen by mutableStateOf(false)
    var goToLineOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    var matches by mutableStateOf<List<IntRange>>(emptyList())
    var selected by mutableIntStateOf(0)
    var searching by mutableStateOf(false)
    var lineNumbers by mutableStateOf(true)
    private val kind = ArtifactTextKind.forPath(artifact.file.name)
    private val preferences = context.getSharedPreferences("cmux-artifact-text", Context.MODE_PRIVATE)
    var wrap by mutableStateOf(preferences.getBoolean("wrap.${kind.name}", kind.defaultWrap)); private set
    var fontSize by mutableFloatStateOf(ArtifactTextKind.fontSize(preferences.getFloat("font.${kind.name}", 15f))); private set
    var jump by mutableStateOf<Pair<Long, Int>?>(null); private set
    private var jumpSequence = 0L
    val raw get() = artifact.route == ChangesPreviewRoute.TEXT && !rendered
    fun updateWrap(value: Boolean) { wrap = value; preferences.edit().putBoolean("wrap.${kind.name}", value).apply() }
    fun setFont(value: Float) { fontSize = ArtifactTextKind.fontSize(value); preferences.edit().putFloat("font.${kind.name}", fontSize).apply() }
    fun jumpTo(offset: Int) { jump = ++jumpSequence to offset }
    fun next(delta: Int) {
        if (matches.isEmpty()) return
        selected = Math.floorMod(selected + delta, matches.size)
        jumpTo(matches[selected].first)
    }
    fun closeSearch() { searchOpen = false; query = ""; matches = emptyList(); selected = 0 }
}

@Composable
internal fun ArtifactRawTextPreview(state: ArtifactViewerState) {
    val context = LocalContext.current
    LaunchedEffect(state) {
        if (state.document == null) try {
            state.document = withContext(Dispatchers.IO) { ArtifactTextDocument(state.artifact.file.readText()) }
        } catch (error: Exception) { ensureActive(); state.failure = error.message ?: "Could not read text." }
    }
    LaunchedEffect(state, state.document) {
        val document = state.document ?: return@LaunchedEffect
        if (state.highlightedDocument === document) return@LaunchedEffect
        val decision = ArtifactSyntaxPolicy.decision(state.artifact.file.name, state.artifact.size)
        try {
            val result = if (decision.enabled) ArtifactSyntaxHighlighter.highlight(context, document.text, decision.language) else null
            ensureActive()
            if (state.document === document) { state.syntax = result; state.highlightedDocument = document }
        } catch (error: Exception) {
            ensureActive() // Cancellation cannot publish into a replaced page.
            state.highlightedDocument = document // Keep the fully usable plain-text view on engine failure.
        }
    }
    LaunchedEffect(state.document, state.query) {
        val document = state.document ?: return@LaunchedEffect
        state.searching = true
        state.matches = emptyList()
        try {
            val results = withContext(Dispatchers.Default) { val job = currentCoroutineContext(); document.search(state.query) { job.ensureActive() } }
            state.matches = results; state.selected = 0
            results.firstOrNull()?.let { state.jumpTo(it.first) }
        } finally { state.searching = false }
    }
    Column(Modifier.fillMaxSize()) {
        if (state.searchOpen) ArtifactSearchBar(state)
        if (state.goToLineOpen) ArtifactLineDialog(state)
        val document = state.document
        if (document == null && state.failure == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (document != null) AndroidView(factory = { ArtifactTextScrollView(it, state::setFont) },
            modifier = Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = "Raw text preview" },
            update = { it.update(state, document) })
    }
}

@Composable
private fun ArtifactSearchBar(state: ArtifactViewerState) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(state.query, { state.query = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
            placeholder = { Text("Find in file") }, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); state.next(1) }))
        if (state.query.isNotEmpty()) Text("${if (state.matches.isEmpty()) 0 else state.selected + 1}/${state.matches.size}", Modifier.padding(4.dp), color = filesMuted)
        IconButton(onClick = { state.next(-1) }, enabled = state.matches.isNotEmpty()) { Text("⌃", Modifier.semantics { contentDescription = "Previous match" }) }
        IconButton(onClick = { state.next(1) }, enabled = state.matches.isNotEmpty()) { Text("⌄", Modifier.semantics { contentDescription = "Next match" }) }
        IconButton(onClick = { keyboard?.hide(); state.closeSearch() }) { Text("×", Modifier.semantics { contentDescription = "Close search" }) }
    }
    if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun ArtifactLineDialog(state: ArtifactViewerState) {
    var line by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun go() {
        val target = line.toIntOrNull() ?: return
        state.document?.let { state.jumpTo(it.offset(target)) }
        keyboard?.hide(); state.goToLineOpen = false
    }
    AlertDialog(onDismissRequest = { state.goToLineOpen = false }, title = { Text("Go to line") }, text = {
        OutlinedTextField(line, { line = it.filter(Char::isDigit) }, Modifier.focusRequester(focus), label = { Text("Line number") }, singleLine = true,
            supportingText = { Text("1–${state.document?.lineCount ?: 1}") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }))
    }, confirmButton = { TextButton(onClick = { go() }, enabled = line.toIntOrNull() != null) { Text("Go") } },
        dismissButton = { TextButton(onClick = { state.goToLineOpen = false }) { Text("Cancel") } })
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** One selectable native text buffer preserves selection/copy across newlines and wrapped lines. */
internal class ArtifactTextScrollView(context: Context, private val changeFont: (Float) -> Unit) : ScrollView(context) {
    val textView = ArtifactNumberedTextView(context)
    private val horizontal = HorizontalScrollView(context).apply { isFillViewport = true }
    private var document: ArtifactTextDocument? = null
    private var syntax: ArtifactSyntaxResult? = null
    private var ranges: List<IntRange>? = null
    private var selected = -1
    private var appliedJump: Pair<Long, Int>? = null
    private var font = 15f
    private var wrapping: Boolean? = null
    private var zooming = false
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { zooming = true; parent?.requestDisallowInterceptTouchEvent(true); return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            font = ArtifactTextKind.fontSize(font * detector.scaleFactor)
            changeFont(font)
            return true
        }
    })
    init {
        isFillViewport = true
        addView(horizontal, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        horizontal.addView(textView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Supply the width on the FIRST measure, before onSizeChanged. Requesting
        // another layout from that callback can be deferred by AndroidView.
        if (wrapping == true) textView.wrapWidth = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        // The hardware display list is reused while the ancestor scrolls. Redraw
        // the visible-line gutter for the new viewport as well as the text.
        textView.invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (wrapping == true && w > 0 && textView.layoutParams.width != w) {
            textView.wrapWidth = w
            textView.layoutParams = textView.layoutParams.apply { width = w }
        }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val wasZooming = zooming
        scale.onTouchEvent(event)
        if (zooming) {
            if (!wasZooming) {
                val cancel = MotionEvent.obtain(event); cancel.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(cancel); cancel.recycle()
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) { zooming = false; parent?.requestDisallowInterceptTouchEvent(false) }
            return true
        }
        return super.dispatchTouchEvent(event)
    }
    fun update(state: ArtifactViewerState, value: ArtifactTextDocument) {
        if (document !== value) {
            document = value; textView.document = value
            textView.setText(SpannableString(value.text), TextView.BufferType.SPANNABLE)
            ranges = null; syntax = null
        }
        val highlighted = state.syntax?.takeIf { it.text == value.text }
        if (syntax !== highlighted) {
            val buffer = textView.text as Spannable
            buffer.getSpans(0, buffer.length, ArtifactSyntaxSpan::class.java).forEach(buffer::removeSpan)
            highlighted?.runs?.forEach { run ->
                buffer.setSpan(ArtifactSyntaxSpan(run.color, run.style), run.start, run.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            syntax = highlighted
        }
        font = state.fontSize
        if (textView.textSize != android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, font, resources.displayMetrics)) textView.textSize = font
        if (wrapping != state.wrap) {
            wrapping = state.wrap
            textView.setHorizontallyScrolling(!state.wrap)
            textView.wrapWidth = if (state.wrap) width.takeIf { it > 0 } else null
            textView.layoutParams = textView.layoutParams.apply { width = if (state.wrap && this@ArtifactTextScrollView.width > 0) this@ArtifactTextScrollView.width else ViewGroup.LayoutParams.WRAP_CONTENT }
            if (state.wrap) horizontal.scrollTo(0, 0)
        }
        textView.setNumbers(state.lineNumbers)
        if (ranges !== state.matches || selected != state.selected) {
            val buffer = textView.text as Spannable
            buffer.getSpans(0, buffer.length, BackgroundColorSpan::class.java).forEach(buffer::removeSpan)
            state.matches.forEachIndexed { index, range -> buffer.setSpan(BackgroundColorSpan(if (index == state.selected) 0xFF8A6320.toInt() else 0xFF41483E.toInt()), range.first, range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
            ranges = state.matches; selected = state.selected
        }
        if (state.jump != null && state.jump != appliedJump) {
            appliedJump = state.jump
            val requested = appliedJump
            post {
                if (appliedJump != requested) return@post
                val offset = requested!!.second.coerceIn(0, value.text.length)
                val layout = textView.layout ?: return@post
                val line = layout.getLineForOffset(offset)
                scrollTo(0, (layout.getLineTop(line) + textView.paddingTop - height / 3).coerceAtLeast(0))
                if (!state.wrap) horizontal.scrollTo((layout.getPrimaryHorizontal(offset).toInt() + textView.paddingLeft - width / 3).coerceAtLeast(0), 0)
            }
        }
    }
}

internal class ArtifactNumberedTextView(context: Context) : TextView(context) {
    var document: ArtifactTextDocument? = null
    var wrapWidth: Int? = null
        set(value) { if (field != value) { field = value; requestLayout() } }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // HorizontalScrollView measures children with an unbounded width, even
        // when their LayoutParams have a fixed width. Constrain wrapped text here.
        super.onMeasure(wrapWidth?.let { MeasureSpec.makeMeasureSpec(it, MeasureSpec.EXACTLY) } ?: widthMeasureSpec, heightMeasureSpec)
    }
    private var numbers = false
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF92979F.toInt(); textAlign = Paint.Align.RIGHT; typeface = Typeface.MONOSPACE }
    init {
        setTextColor(0xFFE7E9ED.toInt()); typeface = Typeface.MONOSPACE
        setTextIsSelectable(true); setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setPadding(12, 12, 12, 24); includeFontPadding = false
    }
    fun setNumbers(value: Boolean) {
        numbers = value
        numberPaint.textSize = textSize
        val pad = (12 * resources.displayMetrics.density).toInt()
        val gutter = if (value) numberPaint.measureText((document?.lineCount ?: 1).toString()).toInt() + pad * 2 else pad
        if (paddingLeft != gutter || paddingTop != pad) setPadding(gutter, pad, pad, pad * 2)
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        // TextView may leave its text-area clip active; the gutter sits outside it.
        val saved = canvas.save()
        super.onDraw(canvas)
        canvas.restoreToCount(saved)
        val index = document ?: return
        val textLayout = layout ?: return
        if (!numbers) return
        val visible = Rect(); if (!getLocalVisibleRect(visible)) return
        val first = textLayout.getLineForVertical((visible.top - paddingTop).coerceAtLeast(0))
        val last = textLayout.getLineForVertical((visible.bottom - paddingTop).coerceAtLeast(0))
        for (visual in first..last) {
            val start = textLayout.getLineStart(visual)
            val logical = index.line(start)
            if (index.offset(logical) == start) canvas.drawText(logical.toString(), paddingLeft - 12 * resources.displayMetrics.density,
                (textLayout.getLineBaseline(visual) + paddingTop).toFloat(), numberPaint)
        }
    }
}

/** Syntax never changes the text, size, background, selection or search spans. */
internal class ArtifactSyntaxSpan(val color: Int, val style: Int) : MetricAffectingSpan() {
    override fun updateDrawState(paint: TextPaint) { paint.color = color; updateMeasureState(paint) }
    override fun updateMeasureState(paint: TextPaint) { paint.typeface = Typeface.create(Typeface.MONOSPACE, style) }
}

@Composable
internal fun ArtifactHighlightingOffPill(bytes: Long) {
    var expanded by remember(bytes) { mutableStateOf(false) }
    val context = LocalContext.current
    val explanation = "This file is ${android.text.format.Formatter.formatShortFileSize(context, bytes)}. Syntax highlighting is off above ${android.text.format.Formatter.formatShortFileSize(context, ArtifactSyntaxPolicy.MAX_HIGHLIGHT_BYTES)} to keep scrolling smooth."
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.CenterEnd) {
        TextButton(onClick = { expanded = !expanded }, shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier.widthIn(max = 360.dp),
            colors = ButtonDefaults.textButtonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF24272C), contentColor = filesMuted),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                val scale = size.width / 24
                fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(filesMuted,
                    androidx.compose.ui.geometry.Offset(x * scale, y * scale), androidx.compose.ui.geometry.Offset(xx * scale, yy * scale), 1.5f * scale)
                line(5f, 4f, 16f, 4f); line(16f, 4f, 16f, 11f); line(16f, 11f, 5f, 11f); line(5f, 11f, 5f, 4f)
                line(10.5f, 11f, 10.5f, 21f); line(2f, 2f, 22f, 22f)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) explanation else "Highlighting off", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "×" else "›")
        }
    }
}
