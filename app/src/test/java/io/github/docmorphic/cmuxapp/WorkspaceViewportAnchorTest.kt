package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class WorkspaceViewportAnchorTest {
    private val old = (0 until 80).map { "row-$it" }
    private val visible = (20..25).map { WorkspaceViewportItem("row-$it", (it - 20) * 60 - 15, 60) }
    private fun position(target: List<String>, rows: List<WorkspaceViewportItem> = visible, top: Boolean = false) =
        workspaceViewportPosition(old, target, rows, 0, 300, top)

    @Test fun insertedAndRemovedRowsAbovePreserveVisibleOffset() {
        assertEquals(WorkspaceViewportPosition(22, 15), position(listOf("new-a", "new-b") + old))
        assertEquals(WorkspaceViewportPosition(10, 15), position(old.drop(10)))
    }
    @Test fun movingTheFirstVisibleRowToTopAnchorsItsUnmovedNeighbor() {
        val next = listOf("row-20") + old.filterNot { it == "row-20" }
        assertEquals(WorkspaceViewportPosition(21, -45), position(next))
        assertFalse("The moved row must not carry the viewport", "row-20" in workspaceStableViewportKeys(old, next))
    }
    @Test fun firstVisibleDeletionUsesNextStableRow() {
        assertEquals(WorkspaceViewportPosition(20, -45), position(old.filterNot { it == "row-20" }))
    }
    @Test fun exactlyOnePhysicalPixelCanAnchorButTouchingTheEdgeCannot() {
        val next = listOf("new") + old
        val edge = listOf(WorkspaceViewportItem("row-19", -60, 60)) + visible.map { it.copy(offset = it.offset + 15) }
        assertEquals(WorkspaceViewportPosition(21, 0), position(next, edge))
        assertEquals(WorkspaceViewportPosition(20, 59), position(next, edge.map {
            if (it.key == "row-19") it.copy(offset = -59) else it
        }))
    }
    @Test fun absoluteTopShowsNewLeadingRowsEvenWhenOldFirstRowSurvives() {
        assertEquals(WorkspaceViewportPosition(0, 0), position(listOf("new") + old, top = true))
    }
    @Test fun statusPrefixParticipatesInBothDiffAndDestinationIndices() {
        val next = listOf("status") + old
        assertEquals(WorkspaceViewportPosition(21, 15), position(next))
        assertEquals(WorkspaceViewportPosition(20, 15), workspaceViewportPosition(next, old, visible, 0, 300, false))
    }
    @Test fun noSurvivingVisibleAnchorOrEmptyTargetNeedsNoExplicitScroll() {
        assertNull(position(emptyList()))
        assertNull(position(listOf("replacement")))
        assertNull(position(old, listOf(WorkspaceViewportItem("row-20", 300, 60))))
        assertNull(position(old, listOf(WorkspaceViewportItem("row-20", 10, 0))))
    }
    @Test fun minimalEditStableSetAndLargeFeedDoNotTreatIndexShiftsAsMoves() {
        assertEquals(setOf("a", "b", "d"), workspaceStableViewportKeys(listOf("a", "b", "c", "d"), listOf("c", "a", "b", "d", "e")))
        val large = (0 until 50_000).map { "key-$it" }
        val moved = large[23_456]
        assertEquals(large.toSet() - moved, workspaceStableViewportKeys(large, listOf(moved) + large.filterNot { it == moved }))
        assertEquals(large.toSet(), workspaceStableViewportKeys(large, listOf("new") + large))
    }
}
