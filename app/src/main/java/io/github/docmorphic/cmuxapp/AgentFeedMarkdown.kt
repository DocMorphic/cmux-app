package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
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
                while (scanner.peek() == ' ' || scanner.peek() == '\t') scanner.next()
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
    private fun parseSource(source: String): List<AgentMarkdownRun> {
        val runs = mutableListOf<AgentMarkdownRun>()
        fun append(text: String, style: AgentMarkdownStyle) {
            if (text.isEmpty()) return
            if (runs.lastOrNull()?.style == style) runs[runs.lastIndex] = runs.last().copy(text = runs.last().text + text)
            else runs += AgentMarkdownRun(text, style)
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
        return runs.toList()
    }
}

@Composable
internal fun AgentFeedMarkdownText(markdown: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    lineLimit: Int = Int.MAX_VALUE, monospaced: Boolean = false, fontSize: Int = 14,
    onLayout: ((TextLayoutResult) -> Unit)? = null) {
    val context = LocalContext.current
    val runs = remember(markdown) { AgentFeedMarkdown.parse(markdown) }
    val text = remember(runs, context) { buildAnnotatedString {
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
    Text(text, modifier, color = color, fontSize = fontSize.sp, maxLines = lineLimit,
        fontFamily = if (monospaced) FontFamily.Monospace else null,
        overflow = TextOverflow.Ellipsis, onTextLayout = { onLayout?.invoke(it) })
}

@Composable
internal fun AgentFeedInlinePreview(text: String, hasMore: Boolean, lineLimit: Int, enabled: Boolean,
    onMore: () -> Unit, color: Color = Color.Unspecified, monospaced: Boolean = false, fontSize: Int = 14) {
    var overflow by remember(text, lineLimit) { mutableStateOf(false) }
    AgentFeedMarkdownText(text, color = color, lineLimit = lineLimit, monospaced = monospaced, fontSize = fontSize,
        onLayout = { overflow = it.hasVisualOverflow })
    if (hasMore || overflow) androidx.compose.material3.TextButton(onClick = onMore, enabled = enabled) { Text("See more") }
}
