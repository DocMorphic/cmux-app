package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints

/** Measure the actual transcript rows through six screens. Nothing is placed or exposed to accessibility.
 * Measuring the bounded top region avoids average-height guesses for quotes, cards and enlarged text. */
@Composable
internal fun NativeChiefHistoryProbe(count: Int, viewport: Int, modifier: Modifier = Modifier,
    onGeometry: (NativeChiefHistoryGeometry) -> Unit, row: @Composable (Int) -> Unit) {
    SubcomposeLayout(modifier.clearAndSetSemantics {}) { constraints ->
        if (viewport > 0 && constraints.hasBoundedWidth) {
            val offsets = mutableListOf<Int>()
            var height = 0
            var index = 0
            while (index < count && height <= viewport * 6) {
                offsets += height
                val children = subcompose(index) { row(index) }
                val rowHeight = children.maxOfOrNull { it.measure(Constraints.fixedWidth(constraints.maxWidth)).height } ?: 0
                height += rowHeight
                ++index
            }
            onGeometry(NativeChiefHistoryGeometry(offsets, height, constraints.maxWidth, viewport))
        }
        layout(constraints.maxWidth, 0) {}
    }
}
