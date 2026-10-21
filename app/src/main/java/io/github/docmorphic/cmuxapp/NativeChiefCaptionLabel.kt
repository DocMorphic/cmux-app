package io.github.docmorphic.cmuxapp

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun nativeChiefCaptionFont(): NativeChiefCaptionFonts.Metrics = with(LocalDensity.current) {
    NativeChiefCaptionFonts.forBody(17.sp.toDp().value.toDouble())
}

/** UIKit UILabel centers the font inside its explicit frame. Place Android text
 * by its measured baseline so platform font padding cannot change that frame. */
@Composable
internal fun NativeChiefCaptionLabel(text: AnnotatedString, font: NativeChiefCaptionFonts.Metrics,
    height: Double, baseline: Double, color: Color, tag: String, modifier: Modifier = Modifier,
    centered: Boolean = false) {
    val density = LocalDensity.current
    val style = nativeChiefTextStyle(11).copy(fontSize = with(density) { font.pointSize.toFloat().dp.toSp() })
    Layout(modifier = modifier.testTag("$tag:Label"), content = {
        Text(text, Modifier.testTag("$tag:Text"), style = style, color = color, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = if (centered) TextAlign.Center else TextAlign.Start)
    }) { children, constraints ->
        val child = children.single().measure(Constraints(maxWidth = constraints.maxWidth))
        val h = (height * density.density).roundToInt().coerceAtLeast(0)
        val baselinePx = (baseline * density.density).roundToInt()
        val childBaseline = child[FirstBaseline]
        layout(constraints.maxWidth, h, mapOf(FirstBaseline to baselinePx)) {
            child.place(if (centered) (constraints.maxWidth - child.width) / 2 else 0, baselinePx - childBaseline)
        }
    }
}
