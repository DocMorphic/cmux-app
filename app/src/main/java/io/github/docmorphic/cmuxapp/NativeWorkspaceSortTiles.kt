package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Matches the iOS view-options card: diagrams explain how each mode arranges the feed. */
@Composable
internal fun NativeWorkspaceSortTiles(selected: NativeWorkspaceSortMode, select: (NativeWorkspaceSortMode) -> Unit) {
    Text("Sort Computers By", style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).semantics { heading() })
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 12.dp, vertical = 6.dp)
        .selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NativeWorkspaceSortMode.entries.forEach { mode ->
            val active = selected == mode
            val accent = MaterialTheme.colorScheme.primary
            val onAccent = MaterialTheme.colorScheme.onPrimary
            val outline = MaterialTheme.colorScheme.outlineVariant
            Column(Modifier.weight(1f).fillMaxHeight().testTag("workspace.sort.${mode.raw}")
                .selectable(active, role = Role.RadioButton, onClick = { select(mode) })
                .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WorkspaceSortSchematic(mode, Modifier.size(72.dp, 96.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp))
                    .border(if (active) 2.dp else 1.dp, if (active) accent else outline, RoundedCornerShape(10.dp)))
                // No maximum line count: enlarged text can grow the scrollable card.
                Text(mode.title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                    minLines = 2, modifier = Modifier.weight(1f))
                Canvas(Modifier.size(20.dp)) {
                    if (active) {
                        drawCircle(accent)
                        drawLine(onAccent, Offset(size.width * .26f, size.height * .5f),
                            Offset(size.width * .44f, size.height * .67f), 1.8.dp.toPx(), StrokeCap.Round)
                        drawLine(onAccent, Offset(size.width * .44f, size.height * .67f),
                            Offset(size.width * .76f, size.height * .33f), 1.8.dp.toPx(), StrokeCap.Round)
                    } else drawCircle(outline, radius = size.minDimension / 2 - 1.dp.toPx(),
                        center = center, style = Stroke(1.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun WorkspaceSortSchematic(mode: NativeWorkspaceSortMode, modifier: Modifier) {
    val row = MaterialTheme.colorScheme.onSurface.copy(alpha = .13f)
    val detail = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        // Fixed design coordinates, scaled to density; no text or extra accessibility nodes.
        scale(size.width / 72f, size.height / 96f, pivot = Offset.Zero) {
            val blue = Color(0xff3388ef)
            val orange = Color(0xffed922d)
            fun bar(y: Float, width: Float = 40f, alpha: Float = 1f) = drawRoundRect(
                row.copy(alpha = row.alpha * alpha), Offset(20f, y), Size(width, 7f), CornerRadius(2f))
            when (mode) {
                NativeWorkspaceSortMode.AUTOMATIC -> {
                    drawRoundRect(blue.copy(alpha = .16f), Offset(9f, 8f), Size(54f, 15f), CornerRadius(4f))
                    computer(12f, 12f, blue, laptop = true)
                    drawRoundRect(blue.copy(alpha = .55f), Offset(27f, 14f), Size(18f, 5f), CornerRadius(2f))
                    drawLine(blue, Offset(55f, 19f), Offset(55f, 12f), 1.4f)
                    drawLine(blue, Offset(52f, 15f), Offset(55f, 12f), 1.4f)
                    drawLine(blue, Offset(58f, 15f), Offset(55f, 12f), 1.4f)
                    bar(28f); bar(39f)
                    computer(12f, 57f, orange.copy(alpha = .45f), laptop = false)
                    drawRoundRect(orange.copy(alpha = .25f), Offset(27f, 59f), Size(18f, 5f), CornerRadius(2f))
                    bar(73f, alpha = .45f)
                }
                NativeWorkspaceSortMode.PRIORITY -> {
                    rankedHeader(14f, orange, 1, detail)
                    bar(31f)
                    rankedHeader(48f, blue, 2, detail)
                    bar(65f); bar(76f)
                }
                NativeWorkspaceSortMode.ACTIVITY -> {
                    drawLine(row, Offset(15f, 14f), Offset(15f, 76f), 1f)
                    val widths = listOf(29f, 27f, 35f, 23f, 29f)
                    widths.forEachIndexed { index, width ->
                        val y = 12f + index * 15f
                        val alpha = 1f - .15f * index
                        val tint = if (index == 0 || index == 3) orange else blue
                        drawCircle(tint.copy(alpha = alpha), 3f, Offset(15f, y + 3.5f))
                        bar(y, width, alpha)
                    }
                    drawCircle(detail, 3.5f, Offset(56f, 15.5f), style = Stroke(1f))
                    drawLine(detail, Offset(56f, 13.5f), Offset(56f, 15.5f), 1f)
                    drawLine(detail, Offset(56f, 15.5f), Offset(58f, 16.5f), 1f)
                }
            }
        }
    }
}

private fun DrawScope.computer(x: Float, y: Float, color: Color, laptop: Boolean) {
    drawRoundRect(color, Offset(x, y), Size(10f, 7f), CornerRadius(1f), style = Stroke(1f))
    if (laptop) drawLine(color, Offset(x - 1f, y + 9f), Offset(x + 11f, y + 9f), 1f)
    else {
        drawLine(color, Offset(x + 5f, y + 7f), Offset(x + 5f, y + 10f), 1f)
        drawLine(color, Offset(x + 2f, y + 10f), Offset(x + 8f, y + 10f), 1f)
    }
}

private fun DrawScope.rankedHeader(y: Float, tint: Color, rank: Int, detail: Color) {
    drawCircle(tint, 5f, Offset(17f, y + 4f))
    // Tiny rank glyphs are paths so system font scaling cannot distort the miniature diagram.
    if (rank == 1) {
        drawLine(Color.White, Offset(16f, y + 2f), Offset(17f, y + 1f), 1f)
        drawLine(Color.White, Offset(17f, y + 1f), Offset(17f, y + 7f), 1f)
    } else {
        val points = listOf(Offset(15f, y + 2f), Offset(17f, y + 1f), Offset(19f, y + 2f),
            Offset(19f, y + 3f), Offset(15f, y + 7f), Offset(19f, y + 7f))
        points.zipWithNext().forEach { (a, b) -> drawLine(Color.White, a, b, 1f) }
    }
    computer(25f, y, tint, laptop = rank == 2)
    drawRoundRect(tint.copy(alpha = .55f), Offset(39f, y + 2f), Size(12f, 5f), CornerRadius(2f))
    repeat(3) { index -> drawLine(detail, Offset(55f, y + index * 3f), Offset(61f, y + index * 3f), 1f) }
}
