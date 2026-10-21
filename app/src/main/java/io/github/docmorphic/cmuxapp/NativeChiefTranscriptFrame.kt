package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Full transcript viewport with the two source bars over it, rather than a shortened list.
 * Measure bars first so editor growth changes scroll insets without replacing its state. */
@Composable
internal fun NativeChiefTranscriptFrame(modifier: Modifier, compact: Boolean, dark: Boolean, trailingOverhang: Dp,
    header: @Composable () -> Unit, composer: @Composable () -> Unit, overlay: @Composable (Dp) -> Unit,
    content: @Composable (PaddingValues, Dp, Dp) -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val barConstraints = Constraints(minWidth = width, maxWidth = width, maxHeight = height)
        val top = subcompose("header", header).single().measure(barConstraints)
        val bottom = subcompose("composer", composer).single().measure(
            barConstraints.copy(maxHeight = (height - top.height).coerceAtLeast(0)))
        val headerHeight = top.height.toDp()
        val composerHeight = bottom.height.toDp()
        val padding = PaddingValues(start = 16.dp, end = 16.dp,
            top = NativeChiefTranscriptGeometry.topInset(headerHeight.value.toDouble(), compact).toFloat().dp,
            bottom = NativeChiefTranscriptGeometry.bottomPadding(composerHeight.value.toDouble(), trailingOverhang.value.toDouble()).toFloat().dp)
        val transcript = subcompose("transcript") { content(padding, headerHeight, composerHeight) }
            .single().measure(Constraints.fixed(width, height))
        val wash = subcompose("wash") { NativeChiefTopEdgeFade(headerHeight, dark) }.single().measure(barConstraints)
        val controls = subcompose("overlay") { overlay(headerHeight) }.single().measure(Constraints.fixed(width, height))
        layout(width, height) {
            transcript.place(0, 0)
            bottom.place(0, height - bottom.height)
            wash.place(0, 0)
            top.place(0, 0)
            controls.place(0, 0)
        }
    }
}

/** The pinned iOS 26 plain-background wash. It has no pointer handlers or accessibility node. */
@Composable
internal fun NativeChiefTopEdgeFade(headerHeight: Dp, dark: Boolean) {
    val density = LocalDensity.current
    val safeTop = WindowInsets.statusBars.getTop(density) / density.density.toDouble()
    Canvas(Modifier.fillMaxWidth().height(headerHeight + 46.dp).testTag("ChiefTopEdgeFade")) {
        // The outer Android shell already consumed the status bar. Crop the source
        // window-anchored gradient instead of starting a new ramp below that bar.
        val span = safeTop + headerHeight.value + 46
        val color = if (dark) Color.Black else Color.White
        val ramp = NativeChiefTranscriptGeometry.wash(span, safeTop + headerHeight.value, dark)
        drawRect(Brush.verticalGradient(*ramp.map { it.location.toFloat() to color.copy(alpha = it.alpha.toFloat()) }.toTypedArray(),
            startY = -(safeTop * density.density).toFloat(), endY = (headerHeight.value + 46) * density.density))
    }
}
