package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import android.icu.text.BreakIterator
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.*
import org.commonmark.parser.*
import org.commonmark.parser.block.*
import org.commonmark.parser.beta.*

internal data class AgentMarkdownStyle(val bold: Boolean = false, val italic: Boolean = false,
    val code: Boolean = false, val strike: Boolean = false, val link: String? = null)
internal data class AgentMarkdownRun(val text: String, val style: AgentMarkdownStyle)

/** Inline-only Markdown with preserved line breaks; no HTML rendering or image/network loading. */
internal object AgentFeedMarkdown {
    private class WholeTextParser(source: String) : AbstractBlockParser() {
        private val paragraph = org.commonmark.node.Paragraph()
        // The document parser normalizes indentation and blank lines before addLine.
        // Supply the original lines directly to its inline parser instead.
        private val lines = SourceLines.of(source.split('\n').map { SourceLine.of(it, null) })
        override fun getBlock() = paragraph
        override fun tryContinue(state: ParserState): BlockContinue = BlockContinue.atIndex(0)
        override fun addLine(line: SourceLine) = Unit
        override fun parseInlines(inlineParser: InlineParser) { inlineParser.parse(lines, paragraph) }
    }
    private fun parser(source: String) = Parser.builder().enabledBlockTypes(emptySet())
        .extensions(listOf(StrikethroughExtension.create()))
        .customInlineContentParserFactory(object : InlineContentParserFactory {
            override fun getTriggerCharacters() = setOf(' ', '\t')
            override fun create() = InlineContentParser { state ->
                val scanner = state.scanner()
                val start = scanner.position()
                while (scanner.peek() != Scanner.END && scanner.peek() != '\n' &&
                    scanner.peek() !in "\\`*_~[]!<>&") scanner.next()
                // Keep authored whitespace that CommonMark's ordinary text parser trims.
                ParsedInline.of(org.commonmark.node.Text(scanner.getSource(start, scanner.position()).content), scanner.position())
            }
        })
        .customBlockParserFactory(object : AbstractBlockParserFactory() {
            override fun tryStart(state: ParserState, matchedBlockParser: MatchedBlockParser): BlockStart =
                BlockStart.of(WholeTextParser(source)).atIndex(0)
        }).build()
    private val cache = object : LinkedHashMap<String, List<AgentMarkdownRun>>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<AgentMarkdownRun>>?) = size > 512
    }
    @Synchronized fun parse(markdown: String): List<AgentMarkdownRun> {
        // Wire rows are already bounded; also bound local reply drafts and metadata callers.
        val source = NativeAgentFeedWire.bound(markdown, 8192)
        cache[source]?.let { return it }
        val runs = runCatching { parseSource(source) }.getOrElse { listOf(AgentMarkdownRun(source, AgentMarkdownStyle())) }
        cache[source] = runs
        return runs
    }
    // Full messages are already bounded by feed.text's 8 MiB transport limit.
    // Do not put these bodies in the row cache or apply the 8 KiB preview cap.
    fun parseFull(markdown: String): List<AgentMarkdownRun> = parseSource(markdown)

    private fun parseSource(source: String): List<AgentMarkdownRun> {
        if (source.none { it in "\\`*_~[]!<>&" }) return listOf(AgentMarkdownRun(source, AgentMarkdownStyle()))
        val runs = mutableListOf<AgentMarkdownRun>()
        val pending = StringBuilder()
        var pendingStyle = AgentMarkdownStyle()
        fun flush() {
            if (pending.isNotEmpty()) { runs += AgentMarkdownRun(pending.toString(), pendingStyle); pending.setLength(0) }
        }
        fun append(text: String, style: AgentMarkdownStyle) {
            if (text.isEmpty()) return
            if (style != pendingStyle) { flush(); pendingStyle = style }
            pending.append(text)
        }
        val start = source.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: source.length
        val end = source.indexOfLast { !it.isWhitespace() } + 1
        val plain = AgentMarkdownStyle()
        append(source.take(start), plain)
        if (end > start) {
            val stack = ArrayDeque<Pair<Node, AgentMarkdownStyle>>()
            val body = source.substring(start, end)
            // A single synthetic block bootstraps the public inline-parser API. Feeding
            // body through the document parser would split it at blank lines.
            // A punctuation marker avoids CommonMark's letter-start paragraph fast path.
            stack.addLast(parser(body).parse("#") to plain)
            while (stack.isNotEmpty()) {
                val (node, inherited) = stack.removeLast()
                val style = when (node) {
                    is StrongEmphasis -> inherited.copy(bold = true)
                    is Emphasis -> inherited.copy(italic = true)
                    is Strikethrough -> inherited.copy(strike = true)
                    is Link -> inherited.copy(link = node.destination.takeIf(MarkdownPreviewPolicy::external))
                    is Image -> inherited.copy(link = null)
                    else -> inherited
                }
                when (node) {
                    is org.commonmark.node.Text -> append(node.literal, style)
                    is Code -> append(node.literal, style.copy(code = true))
                    is SoftLineBreak, is HardLineBreak -> append("\n", style)
                    is HtmlInline -> append(node.literal, style)
                    else -> {
                        var child = node.lastChild
                        while (child != null) { stack.addLast(child to style); child = child.previous }
                    }
                }
            }
            append(source.substring(end), plain)
        }
        flush()
        return runs.toList()
    }
}

@Composable
internal fun AgentFeedMarkdownText(markdown: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    lineLimit: Int = Int.MAX_VALUE, monospaced: Boolean = false, fontSize: Int = 14,
    onLayout: ((TextLayoutResult) -> Unit)? = null) {
    val text = agentFeedAnnotatedText(markdown)
    Text(text, modifier, color = color, fontSize = fontSize.sp, maxLines = lineLimit,
        style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
        fontFamily = if (monospaced) FontFamily.Monospace else null,
        overflow = TextOverflow.Ellipsis, onTextLayout = { onLayout?.invoke(it) })
}

@Composable
private fun agentFeedAnnotatedText(markdown: String): AnnotatedString {
    val context = LocalContext.current
    val runs = remember(markdown) { AgentFeedMarkdown.parse(markdown) }
    return remember(runs, context) { buildAnnotatedString {
        runs.forEach { run ->
            val style = run.style
            val index = pushStyle(SpanStyle(fontWeight = FontWeight.Bold.takeIf { style.bold },
                fontStyle = FontStyle.Italic.takeIf { style.italic }, fontFamily = FontFamily.Monospace.takeIf { style.code },
                textDecoration = TextDecoration.LineThrough.takeIf { style.strike },
                background = if (style.code) Color(0x222C7ABB) else Color.Unspecified))
            if (style.link != null) {
                pushLink(LinkAnnotation.Url(style.link, TextLinkStyles(style = SpanStyle(color = Color(0xFF76B9FF), textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { link ->
                        (link as? LinkAnnotation.Url)?.url?.takeIf(MarkdownPreviewPolicy::external)?.let { url ->
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        }
                    }))
            }
            append(run.text); pop(index)
        }
    } }
}

/** Cut rendered text, retaining Markdown spans/links and whole Unicode graphemes. */
internal fun agentFeedCollapsedText(complete: AnnotatedString, visibleEnd: Int,
    suffix: AnnotatedString, fits: (AnnotatedString) -> Boolean): AnnotatedString {
    val characters = BreakIterator.getCharacterInstance(java.util.Locale.ROOT).apply { setText(complete.text) }
    fun boundary(index: Int): Int = if (characters.isBoundary(index)) index else characters.preceding(index)
    var cut = boundary(visibleEnd.coerceIn(0, complete.length))
    while (true) {
        // Trim whole graphemes so a whitespace + combining mark is never split.
        while (cut > 0) {
            val previous = characters.preceding(cut)
            if (!complete.text.substring(previous, cut).all(Char::isWhitespace)) break
            cut = previous
        }
        val candidate = complete.subSequence(0, cut) + suffix
        if (cut == 0 || fits(candidate)) return candidate
        // The initial layout already locates the final visible line. Reserving
        // room for the control requires only a few short backwards probes.
        cut = boundary((cut - 8).coerceAtLeast(0))
    }
}

@Composable
internal fun AgentFeedInlinePreview(text: String, hasMore: Boolean, lineLimit: Int, enabled: Boolean,
    onMore: () -> Unit, color: Color = Color.Unspecified, monospaced: Boolean = false, fontSize: Int = 14,
    modifier: Modifier = Modifier) {
    val complete = agentFeedAnnotatedText(text)
    val latestMore by rememberUpdatedState(onMore)
    val linkColor = Color(0xFF76B9FF).let { if (enabled) it else it.copy(alpha = .38f) }
    val suffix = remember(enabled, linkColor) { buildAnnotatedString {
        append("… ")
        if (enabled) pushLink(LinkAnnotation.Clickable("AgentFeedSeeMore",
            styles = TextLinkStyles(style = SpanStyle(color = linkColor)),
            linkInteractionListener = { latestMore() }))
        else pushStyle(SpanStyle(color = linkColor))
        append("See more")
        pop()
    } }
    val style = LocalTextStyle.current.merge(TextStyle(color = color, fontSize = fontSize.sp, textDirection = TextDirection.Content,
        fontFamily = if (monospaced) FontFamily.Monospace else null))
    val measurer = rememberTextMeasurer(cacheSize = 16)
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val rendered = remember(complete, suffix, hasMore, lineLimit, width, style, measurer) {
            val bounds = Constraints(maxWidth = width)
            fun measure(value: AnnotatedString) = measurer.measure(value, style,
                constraints = bounds, maxLines = lineLimit.coerceAtLeast(1), overflow = TextOverflow.Clip)
            val full = measure(complete)
            val expand = hasMore || full.hasVisualOverflow
            val collapsed = if (expand) agentFeedCollapsedText(complete,
                full.getLineEnd(full.lineCount - 1), suffix) { !measure(it).hasVisualOverflow } else complete
            collapsed to expand
        }
        // The expansion link has native link semantics, independently of row
        // navigation. At extreme font/width combinations let the control wrap
        // instead of clipping the only way to reach the full message.
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(rendered.first, Modifier.fillMaxWidth().then(if (rendered.second) Modifier.heightIn(min = 48.dp) else Modifier), style = style,
            onTextLayout = { layout = it })
        // Text adds resolved link styles to its layout input, so annotations differ
        // from our source even when offsets describe the same displayed string.
        val measured = layout?.takeIf { it.layoutInput.text.text == rendered.first.text }
        if (rendered.second && enabled && measured != null) {
            val link = rendered.first.getLinkAnnotations(0, rendered.first.length)
                .single { (it.item as? LinkAnnotation.Clickable)?.tag == "AgentFeedSeeMore" }
            val boxes = (link.start until link.end).map(measured::getBoundingBox)
            val bounds = androidx.compose.ui.geometry.Rect(boxes.minOf { it.left }, boxes.minOf { it.top },
                boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
            val density = LocalDensity.current
            // The text still owns pointer input and ordinary URL links. This virtual
            // control exposes the expansion subrange to TalkBack and Switch Access.
            Box(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = "See more"
                    onClick(label = "See more") { latestMore(); true }
                })
        }
    }
}
