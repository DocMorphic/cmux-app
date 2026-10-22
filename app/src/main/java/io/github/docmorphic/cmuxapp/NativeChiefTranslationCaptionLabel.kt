package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal data class NativeChiefTranslationAttachmentMetrics(val width: Double, val height: Double, val prefixAdvance: Double)

/** The source UILabel has a fixed Caption 2 line box. Its attachment is centered
 * in that box; the text keeps the source font baseline and physical sender edge. */
@Composable
internal fun NativeChiefTranslationCaptionLabel(label: String, outgoing: Boolean,
    font: NativeChiefCaptionFonts.Metrics, attachment: NativeChiefTranslationAttachmentMetrics,
    tailOverhang: Dp, color: Color, tag: String, modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val style = nativeChiefTranslationFont(nativeChiefTextStyle(11).copy(
        fontSize = with(density) { font.pointSize.toFloat().dp.toSp() }), font.pointSize, NativeChiefTranslationFonts.Probe.CAPTION)
    val height = NativeChiefBubbleGeometry.ceilToPixel(font.lineHeight, density.density.toDouble())
    val baseline = font.ascender + (height - font.lineHeight) / 2
    // MessageCellLayout.footerRect insets only the edge beside the message.
    // The opposite edge retains the full transcript margin, including in RTL.
    Layout(modifier = modifier.fillMaxWidth().absolutePadding(left = if (outgoing) 0.dp else 20.dp,
        right = if (outgoing) 20.dp else 0.dp).testTag("$tag:Frame"), content = {
        Layout(modifier = Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {}.then(actionModifier), content = {
            Image(painterResource(R.drawable.ic_chief_translation_caption), null, Modifier.testTag("$tag:Glyph"),
                contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(color))
            Text(label, Modifier.testTag("$tag:Text"), style = style, color = color,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Left)
        }) { children, constraints ->
            val scale = density.density
            val h = (height * scale).roundToInt()
            val b = (baseline * scale).roundToInt()
            val prefix = (attachment.prefixAdvance * scale).roundToInt()
            val icon = children[0].measure(Constraints.fixed((attachment.width * scale).roundToInt(), (attachment.height * scale).roundToInt()))
            val text = children[1].measure(Constraints(maxWidth = (constraints.maxWidth - prefix).coerceAtLeast(0)))
            val x = if (outgoing) constraints.maxWidth - prefix - text.width else 0
            layout(constraints.maxWidth, h, mapOf(FirstBaseline to b)) {
                icon.placeWithLayer(x, 0) { translationY = (h - icon.height) / 2f }
                text.place(x + prefix, b - text[FirstBaseline])
            }
        }
    }) { children, constraints ->
        val child = children.single().measure(constraints.copy(minHeight = 0))
        val y = with(density) { (4.dp - tailOverhang).roundToPx() }
        layout(constraints.maxWidth, (child.height + y).coerceAtLeast(0)) { child.place(0, y) }
    }
}
