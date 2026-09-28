package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun FilesViewModeButton(grid: Boolean, onClick: () -> Unit) {
    IconButton(onClick, Modifier.semantics { contentDescription = if (grid) "List" else "Icons" }) {
        Canvas(Modifier.size(22.dp)) {
            scale(size.width / 24, size.height / 24, Offset.Zero) {
                if (grid) for (y in listOf(5f, 12f, 19f)) {
                    drawCircle(filesAccent, 1.2f, Offset(3f, y))
                    drawLine(filesAccent, Offset(8f, y), Offset(22f, y), 1.7f)
                } else for (x in listOf(2f, 10f, 18f)) for (y in listOf(2f, 10f, 18f)) {
                    drawRect(filesAccent, Offset(x, y), Size(4f, 4f), style = Stroke(1.3f))
                }
            }
        }
    }
}

@Composable
internal fun FilesGlyph(filter: ArtifactFilter?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        scale(size.width / 24, size.height / 24, Offset.Zero) {
            fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(filesAccent, Offset(x, y), Offset(xx, yy), 1.4f)
            when (filter) {
                ArtifactFilter.FOLDERS -> drawPath(Path().apply {
                    moveTo(2f, 6f); lineTo(2f, 20f); lineTo(22f, 20f); lineTo(22f, 7f)
                    lineTo(11f, 7f); lineTo(9f, 4f); lineTo(2f, 4f); close()
                }, filesAccent, style = Stroke(1.4f))
                ArtifactFilter.CODE -> {
                    line(7f, 6f, 2f, 12f); line(2f, 12f, 7f, 18f)
                    line(17f, 6f, 22f, 12f); line(22f, 12f, 17f, 18f); line(14f, 3f, 10f, 21f)
                }
                ArtifactFilter.IMAGES -> {
                    drawRect(filesAccent, Offset(2f, 3f), Size(20f, 18f), style = Stroke(1.4f))
                    drawCircle(filesAccent, 2f, Offset(7f, 8f))
                    line(3f, 20f, 12f, 11f); line(12f, 11f, 21f, 20f)
                }
                ArtifactFilter.LOGS -> for (y in listOf(5f, 10f, 15f, 20f)) line(3f, y, if (y == 20f) 14f else 21f, y)
                else -> {
                    drawPath(Path().apply { moveTo(5f, 2f); lineTo(15f, 2f); lineTo(20f, 7f); lineTo(20f, 22f); lineTo(5f, 22f); close() }, filesAccent, style = Stroke(1.4f))
                    line(15f, 2f, 15f, 7f); line(15f, 7f, 20f, 7f)
                    line(8f, 12f, 17f, 12f); line(8f, 16f, 17f, 16f)
                }
            }
        }
    }
}
