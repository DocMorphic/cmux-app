package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints

internal val LocalWorkspaceGeometryHeld = compositionLocalOf { false }

/** Measure current stateless drawing before admitting a height change during an interaction.
 * The probe is never placed. The actual content keeps a single composition slot, preserving
 * its identity. Equal-height updates stay live; release consumes the latest model directly.
 * Layout environment changes invalidate the old geometry (rotation/font changes must relayout).
 */
@Composable
internal fun <T> WorkspaceMeasuredContent(target: T, held: Boolean,
    content: @Composable (T, measuring: Boolean) -> Unit) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val committed = remember(density.density, density.fontScale, direction) { WorkspaceMeasuredModel(target) }
    SubcomposeLayout { constraints ->
        var shown = target
        if (held && committed.constraints == constraints && committed.model != target) {
            val probe = subcompose(WorkspaceMeasureSlot.Probe) {
                Box(Modifier.clearAndSetSemantics { }, propagateMinConstraints = true) { content(target, true) }
            }.single().measure(constraints)
            if (probe.height != committed.height) shown = committed.model
        }
        val placeable = subcompose(WorkspaceMeasureSlot.Content) { content(shown, false) }.single().measure(constraints)
        committed.model = shown
        committed.height = placeable.height
        committed.constraints = constraints
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
}

private enum class WorkspaceMeasureSlot { Probe, Content }
private class WorkspaceMeasuredModel<T>(var model: T) {
    var constraints: Constraints? = null
    var height = 0
}
