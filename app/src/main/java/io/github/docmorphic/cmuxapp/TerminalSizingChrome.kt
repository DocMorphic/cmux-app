package io.github.docmorphic.cmuxapp

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/** iOS shared-sizing chrome rules, applied to the Android painter's actual rectangle. */
internal object TerminalSizingChrome {
    fun settled(state: TerminalSizeState?, selfId: String?, viewport: TerminalViewport?,
                reportConfirmed: Boolean, rendered: SharedTerminalGrid, connected: Boolean): Boolean {
        if (!connected || !reportConfirmed || state == null || viewport == null || rendered != state.grid) return false
        val local = SharedTerminalGrid(viewport.columns, viewport.rows)
        return state.participants.firstOrNull { it.id == selfId }?.viewport == local && state.grid != local
    }

    enum class Edge { TOP, RIGHT, BOTTOM, LEFT }
    data class Bounds(val grid: Rect, val hatch: List<Rect>, val edges: Set<Edge>, val cuts: Set<Edge>)
    fun bounds(viewport: Rect, render: Rect, density: Float): Bounds {
        val intersection = render.intersect(viewport)
        val grid = if (intersection.isEmpty) viewport else intersection
        val hatch = listOf(
            Rect(viewport.left, viewport.top, viewport.right, grid.top),
            Rect(viewport.left, grid.bottom, viewport.right, viewport.bottom),
            Rect(viewport.left, grid.top, grid.left, grid.bottom),
            Rect(grid.right, grid.top, viewport.right, grid.bottom)
        ).filter { it.width >= density * .5f && it.height >= density * .5f }
        val edges = buildSet {
            if (grid.top - viewport.top > density) add(Edge.TOP)
            if (viewport.right - grid.right > density) add(Edge.RIGHT)
            if (viewport.bottom - grid.bottom > density) add(Edge.BOTTOM)
            if (grid.left - viewport.left > density) add(Edge.LEFT)
        }
        val cuts = buildSet {
            if (render.top < viewport.top - density * .5f) add(Edge.TOP)
            if (render.right > viewport.right + density * .5f) add(Edge.RIGHT)
            if (render.bottom > viewport.bottom + density * .5f) add(Edge.BOTTOM)
            if (render.left < viewport.left - density * .5f) add(Edge.LEFT)
        }
        return Bounds(grid, hatch, edges, cuts)
    }

    enum class Anchor { BELOW, BESIDE, ABOVE, COMPACT }
    data class Chip(val frame: Rect, val anchor: Anchor)
    fun chip(viewport: Rect, grid: Rect, full: Size, compact: Size, inset: Float): Chip {
        val width = full.width.coerceAtMost((viewport.width - inset * 2).coerceAtLeast(0f))
        fun trailing(w: Float) = (minOf(grid.right, viewport.right - inset) - w).coerceAtLeast(viewport.left + inset)
        if (viewport.bottom - grid.bottom >= full.height + inset * 2)
            return Chip(Rect(trailing(width), grid.bottom + inset, trailing(width) + width, grid.bottom + inset + full.height), Anchor.BELOW)
        if (viewport.right - grid.right >= width + inset * 2 && grid.height >= full.height)
            return Chip(Rect(grid.right + inset, grid.bottom - full.height, grid.right + inset + width, grid.bottom), Anchor.BESIDE)
        if (grid.top - viewport.top >= full.height + inset * 2)
            return Chip(Rect(trailing(width), grid.top - inset - full.height, trailing(width) + width, grid.top - inset), Anchor.ABOVE)
        val w = compact.width.coerceAtMost((viewport.width - inset * 2).coerceAtLeast(0f))
        return Chip(Rect(viewport.right - inset - w, viewport.top + inset, viewport.right - inset,
            viewport.top + inset + compact.height), Anchor.COMPACT)
    }
}
