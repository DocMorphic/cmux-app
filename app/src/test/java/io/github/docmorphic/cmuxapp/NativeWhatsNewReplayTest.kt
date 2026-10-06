package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeWhatsNewReplayTest {
    private val entry = WhatsNewPage("same-id", "Binary update", WhatsNewBody.Features(emptyList()))
    private val news = entry.copy(title = "Service update", kind = WhatsNewKind.ANNOUNCEMENT)
    private val third = entry.copy(id = "third")

    @Test fun rangeUsesNamespacedKeysAndAcceptsReversedEndpoints() {
        val pages = listOf(entry, news, third)
        assertEquals(listOf(news), NativeWhatsNewReplay.range(pages, news.key, news.key))
        assertEquals(pages, NativeWhatsNewReplay.range(pages, third.key, entry.key))
        assertNull(NativeWhatsNewReplay.range(pages, "same-id", third.key))
        assertNull(NativeWhatsNewReplay.range(emptyList(), entry.key, entry.key))
    }

    @Test fun openReplayFreezesCatalogAndFeatureRowsWhilePaging() {
        val rows = mutableListOf(WhatsNewFeature("Before", "Original"))
        val first = entry.copy(body = WhatsNewBody.Features(rows))
        val pages = mutableListOf(first, news)
        val replay = NativeWhatsNewReplay()
        replay.reconcile("login", true)
        replay.start("login", pages, first.key, news.key)
        val active = replay.state.value!!
        pages.clear(); rows.clear()
        assertEquals(2, active.pages.size)
        assertEquals("Before", (active.pages[0].body as WhatsNewBody.Features).rows.single().title)
        replay.select(active.token, 1)
        assertEquals(1, replay.state.value!!.pageIndex)
        replay.select(active.token, 9)
        assertEquals(1, replay.state.value!!.pageIndex)
    }

    @Test fun accountReplacementAndArchiveExitRetireReplayAndRejectStaleCallbacks() {
        val replay = NativeWhatsNewReplay()
        replay.reconcile("old", true)
        replay.start("old", listOf(entry, news), entry.key, news.key)
        val old = replay.state.value!!
        replay.reconcile("new", true)
        assertNull(replay.state.value)
        replay.start("old", old.pages, entry.key, news.key)
        assertNull(replay.state.value)
        replay.start("new", old.pages, entry.key, news.key)
        replay.select(old.token, 1)
        assertEquals(0, replay.state.value!!.pageIndex)
        replay.reconcile("new", false)
        assertNull(replay.state.value)
        replay.start("new", old.pages, entry.key, news.key)
        assertNull(replay.state.value)
    }

    @Test fun invalidOrRetractedRangeCannotReplaceAnOpenSnapshot() {
        val replay = NativeWhatsNewReplay()
        replay.reconcile("login", true)
        replay.start("login", listOf(entry, news), entry.key, news.key)
        val original = replay.state.value
        replay.start("login", listOf(news), entry.key, news.key)
        assertEquals(original, replay.state.value)
        replay.dismiss()
        assertNull(replay.state.value)
    }
}
