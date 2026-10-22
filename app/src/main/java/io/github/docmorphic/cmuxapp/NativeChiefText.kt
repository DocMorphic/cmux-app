package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit

internal const val ChiefDetectedTextRange = "ChiefDetectedTextRange"

/** ConversationDataDetection.underlineAlpha at the pinned a5911a1f source. */
internal fun nativeChiefUnderlineAlpha(saturation: Float, brightness: Float): Float =
    if (saturation < .02f && brightness > .98f) .46f else .26f

internal fun nativeChiefUnderlineAlpha(color: Color): Float {
    val maximum = maxOf(color.red, color.green, color.blue)
    val minimum = minOf(color.red, color.green, color.blue)
    val saturation = if (maximum == 0f) 0f else (maximum - minimum) / maximum
    return nativeChiefUnderlineAlpha(saturation, maximum)
}

@Composable
internal fun nativeChiefTextStyle(size: Int = 17): TextStyle = TextStyle(
    fontFamily = LocalTextStyle.current.fontFamily, fontWeight = FontWeight.Normal,
    fontSize = size.sp, lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp,
    textDirection = TextDirection.Content, platformStyle = PlatformTextStyle(includeFontPadding = false))

/** Chief supplies plain text without rich-text runs; Markdown characters are message content. */
internal fun nativeChiefDetectedText(source: String, interactive: Boolean = true, open: (String) -> Unit): AnnotatedString {
    val detected = SpannableString(source)
    Linkify.addLinks(detected, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES or Linkify.PHONE_NUMBERS)
    return buildAnnotatedString {
        append(source)
        detected.getSpans(0, detected.length, URLSpan::class.java).forEach { span ->
            val url = span.url
            if (Uri.parse(url).scheme?.lowercase() !in setOf("http", "https", "mailto", "tel")) return@forEach
            // Compose's decoration has no independent color. Draw the lighter
            // line from the actual text selection path, preserving opaque glyphs.
            val style = SpanStyle(textDecoration = TextDecoration.None)
            addStringAnnotation(ChiefDetectedTextRange, url, detected.getSpanStart(span), detected.getSpanEnd(span))
            if (interactive) addLink(LinkAnnotation.Url(url, TextLinkStyles(style = style),
                linkInteractionListener = { open(url) }), detected.getSpanStart(span), detected.getSpanEnd(span))
            else addStyle(style, detected.getSpanStart(span), detected.getSpanEnd(span))
        }
    }
}

@Composable
internal fun NativeChiefText(source: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    fontSize: Int = 17, lineLimit: Int = Int.MAX_VALUE, interactive: Boolean = true) {
    val context = LocalContext.current
    val text = remember(source, context, interactive) { nativeChiefDetectedText(source, interactive) { url ->
        // A telephone tap opens the dialer for user confirmation, like the source's tel: action.
        val uri = Uri.parse(url)
        val action = if (uri.scheme == "tel") Intent.ACTION_DIAL else Intent.ACTION_VIEW
        runCatching { context.startActivity(Intent(action, uri)) }
    } }
    val style = nativeChiefTextStyle(fontSize)
    val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val density = LocalDensity.current
    val typeface by LocalFontFamilyResolver.current.resolve(style.fontFamily, FontWeight.Normal, FontStyle.Normal, FontSynthesis.All)
    val paint = remember(typeface, density, fontSize) { android.graphics.Paint().apply {
        this.typeface = typeface as android.graphics.Typeface
        textSize = with(density) { fontSize.sp.toPx() }
    } }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val ranges = remember(text) { text.getStringAnnotations(ChiefDetectedTextRange, 0, text.length) }
    Text(text, modifier.drawWithContent {
        // BasicText can resolve link styles into additional span runs. Compare
        // the literal content, whose UTF-16 offsets own these detector ranges.
        layout?.takeIf { it.layoutInput.text.text == text.text }?.let { measured ->
            val underlinePosition = if (android.os.Build.VERSION.SDK_INT >= 29) paint.underlinePosition else paint.fontMetrics.descent / 2f
            val thickness = (if (android.os.Build.VERSION.SDK_INT >= 29) paint.underlineThickness else paint.textSize / 16f).coerceAtLeast(1f)
            val underline = ink.copy(alpha = nativeChiefUnderlineAlpha(ink))
            for (range in ranges) for (line in 0 until measured.lineCount) {
                val start = maxOf(range.start, measured.getLineStart(line))
                val end = minOf(range.end, measured.getLineEnd(line, visibleEnd = true))
                if (end > start) clipPath(measured.getPathForRange(start, end)) {
                    drawRect(underline, Offset(0f, measured.getLineBaseline(line) + underlinePosition), Size(size.width, thickness))
                }
            }
        }
        drawContent()
    }, color = ink, fontSize = fontSize.sp, maxLines = lineLimit,
        style = style, overflow = TextOverflow.Ellipsis, onTextLayout = { layout = it })
}
