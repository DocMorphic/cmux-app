package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class WorkspacePresentationRowsTest {
    private data class Row(val key: String, val text: String)
    private val old = listOf(Row("a", "old a"), Row("b", "old b"), Row("c", "old c"))
    private fun reconcile(rendered: List<Row>, target: List<Row>, held: Boolean = true) =
        workspacePresentationRows(rendered, target, held, Row::key)

    @Test fun orderAndMembershipWaitWhileSurvivorsUseCurrentContent() {
        val target = listOf(Row("new", "new"), Row("c", "new c"), Row("a", "new a"))
        val shown = reconcile(old, target)
        assertEquals(listOf("a", "b", "c"), shown.map { it.key })
        assertEquals(listOf("new a", "old b", "new c"), shown.map { it.text })
        assertEquals(listOf("old a", "old b", "old c"), old.map { it.text })
    }
    @Test fun releaseUsesNewestSnapshotAndDoesNotReplayIntermediatePolls() {
        val intermediate = listOf(Row("x", "temporary"), old[2])
        val newest = listOf(Row("b", "new b"), Row("d", "new d"))
        val shown = reconcile(reconcile(old, intermediate), newest)
        assertEquals(listOf("a", "b", "c"), shown.map { it.key })
        assertEquals("new b", shown[1].text)
        assertSame(newest, reconcile(shown, newest, held = false))
    }
    @Test fun deletionAndReappearanceKeepGeometryButRefreshTheReturningModel() {
        val absent = reconcile(old, emptyList())
        assertEquals(old, absent)
        val returned = reconcile(absent, listOf(Row("b", "returned")))
        assertEquals(listOf("a", "b", "c"), returned.map { it.key })
        assertEquals("returned", returned[1].text)
        assertTrue(reconcile(returned, emptyList(), held = false).isEmpty())
    }
    @Test fun largeSnapshotRefreshKeepsAllOriginalPositionsUntilRelease() {
        val many = (0 until 50_000).map { Row("$it", "old") }
        val latest = many.reversed().map { it.copy(text = "new") }
        val shown = reconcile(many, latest)
        assertEquals(many.map { it.key }, shown.map { it.key })
        assertTrue(shown.all { it.text == "new" })
        assertSame(latest, reconcile(shown, latest, held = false))
    }
}
