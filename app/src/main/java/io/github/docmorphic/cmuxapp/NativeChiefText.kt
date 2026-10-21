package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit

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
            val style = SpanStyle(textDecoration = TextDecoration.Underline)
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
    Text(text, modifier, color = color, fontSize = fontSize.sp, maxLines = lineLimit,
        style = nativeChiefTextStyle(fontSize), overflow = TextOverflow.Ellipsis)
}
