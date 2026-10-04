package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeWhatsNewLaunchTest {
    private class Store : WhatsNewStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(updates: Map<String, String?>): Boolean {
            updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }
    }
    private val native = WhatsNewPage("native", "Native", WhatsNewBody.Features(emptyList()))
    private fun web(id: String) = WhatsNewPage(id, id, WhatsNewBody.Web("https://cmux.com/$id"))
    private fun center(store: Store, pages: List<WhatsNewPage>) =
        NativeWhatsNewCenter(pages, "0.2.0", WhatsNewChannel.BETA, store)
    private class Page(scope: CoroutineScope, page: WhatsNewPage, var dark: Boolean) : NativeWhatsNewPreloadedPage {
        override val isClosed = MutableStateFlow(false)
        var closes = 0
        override val load = NativeWhatsNewWebLoad(scope, WhatsNewWebPolicy(null),
            (page.body as WhatsNewBody.Web).url, NativeWhatsNewWebLoad.LAUNCH_DEADLINE_MS,
            stopRenderer = { isClosed.value = true }, closeRenderer = { isClosed.value = true }) { awaitCancellation() }
        override fun theme(dark: Boolean) { this.dark = dark }
        override fun close() { closes++; load.close() }
    }
    @Test fun waitsForInitialRefreshThenConcurrentDeadlinesExcludeFailureWithoutBurningItsMarker() = runTest {
        val store = Store(); val a = web("a"); val b = web("b"); val c = center(store, listOf(b, a, native))
        val created = mutableMapOf<WhatsNewPage, Page>()
        val p = NativeWhatsNewPresentation(c, this) { page, _, dark -> Page(this, page, dark).also { created[page] = it } }
        p.reconcile("owner", true); runCurrent(); assertTrue(created.isEmpty())
        c.refresh { """{"visibleEntryIds":["a","b","native","web"]}""" }; p.reconcile("owner", true); runCurrent()
        assertEquals(2, created.size); assertNull(p.state.value); assertNull(store.values[NativeWhatsNewCenter.MARKER])
        created.getValue(a).load.finishedInitialPage(); runCurrent(); assertNull(p.state.value)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(10_000L, currentTime); assertEquals(listOf(a, native), p.state.value!!.pages)
        assertEquals(1, created.getValue(b).closes); assertNull(store.values[NativeWhatsNewCenter.MARKER])
        val token = p.state.value!!.token
        p.appeared(token, "owner", true); assertEquals("a", store.values[NativeWhatsNewCenter.MARKER])
        p.dismiss(token); p.reconcile("owner", true); runCurrent()
        assertNull(p.state.value); assertEquals(2, created.size)
        assertEquals(listOf(b), c.state.value.unseen); p.close()
    }
    @Test fun competingModalCancelsPendingPagesAndResumptionStartsFreshWithoutLateAcknowledgement() = runTest {
        val store = Store(); val w = web("a"); val c = center(store, listOf(w)); c.refresh { """{"visibleEntryIds":["a","b","native","web"]}""" }
        val created = mutableListOf<Page>()
        val p = NativeWhatsNewPresentation(c, this) { page, _, dark -> Page(this, page, dark).also(created::add) }
        p.reconcile("owner", true); runCurrent(); val old = created.single()
        p.reconcile("owner", false); runCurrent(); assertEquals(1, old.closes)
        old.load.finishedInitialPage(); runCurrent(); assertNull(p.state.value); assertNull(store.values[NativeWhatsNewCenter.MARKER])
        p.reconcile("owner", true); runCurrent(); assertEquals(2, created.size)
        created.last().load.finishedInitialPage(); runCurrent(); assertEquals(listOf(w), p.state.value!!.pages)
        p.close()
    }
    @Test fun accountChangeRetiresOldPageAndRejectsOldSheetAppearance() = runTest {
        val store = Store(); val w = web("a"); val c = center(store, listOf(w)); c.refresh { """{"visibleEntryIds":["a","b","native","web"]}""" }
        val created = mutableListOf<Pair<String, Page>>()
        val p = NativeWhatsNewPresentation(c, this) { page, owner, dark -> Page(this, page, dark).also { created += owner to it } }
        p.reconcile("old", true); runCurrent(); created[0].second.load.finishedInitialPage(); runCurrent()
        val old = p.state.value!!
        p.reconcile("new", true); runCurrent(); assertTrue(created[0].second.isClosed.value)
        created[1].second.load.finishedInitialPage(); runCurrent()
        p.appeared(old.token, "old", true); assertNull(store.values[NativeWhatsNewCenter.MARKER])
        assertEquals("new", p.state.value!!.owner)
        p.appeared(p.state.value!!.token, "new", true); assertTrue(p.state.value!!.appeared); p.close()
    }
    private fun feed(path: String) = """{"visibleEntryIds":[],"announcements":[{"id":"notice","minVersion":"0.2.0","maxVersion":"0.2.0","title":"Notice","webUrl":"https://cmux.com/$path"}]}"""
    @Test fun contentReplacementBeforeAppearanceRequiresFreshLoadAndNeverAcknowledgesOldDocument() = runTest {
        val store = Store(); val c = center(store, emptyList()); c.refresh { feed("old") }
        val created = mutableListOf<Page>()
        val p = NativeWhatsNewPresentation(c, this) { page, _, dark -> Page(this, page, dark).also(created::add) }
        p.reconcile("owner", true); runCurrent(); created.single().load.finishedInitialPage(); runCurrent()
        val old = p.state.value!!; val persisted = store.values.toMap()
        c.refresh { feed("new") }; val afterRefresh = store.values.toMap()
        p.appeared(old.token, "owner", true); runCurrent()
        assertEquals(afterRefresh, store.values); assertFalse(persisted == afterRefresh)
        assertNull(p.state.value); assertEquals(2, created.size); assertEquals(1, created.first().closes)
        created.last().load.finishedInitialPage(); runCurrent()
        assertEquals(WhatsNewBody.Web("https://cmux.com/new"), p.state.value!!.pages.single().body); p.close()
    }
    @Test fun retiredAfterLoadingBeforeAppearanceIsOmittedAndNotAcknowledged() = runTest {
        val store = Store(); val w = web("a"); val c = center(store, listOf(w)); c.refresh { """{"visibleEntryIds":["a","b","native","web"]}""" }
        lateinit var page: Page
        val p = NativeWhatsNewPresentation(c, this) { content, _, dark -> Page(this, content, dark).also { page = it } }
        p.reconcile("owner", true); runCurrent(); page.load.finishedInitialPage(); runCurrent()
        val token = p.state.value!!.token; page.isClosed.value = true
        p.appeared(token, "owner", true); runCurrent()
        assertNull(p.state.value); assertNull(store.values[NativeWhatsNewCenter.MARKER]); p.close()
    }
    @Test fun recreationAndThemeReuseLoadedPageAndDismissalRetiresIt() = runTest {
        val store = Store(); val w = web("a"); val c = center(store, listOf(w)); c.refresh { """{"visibleEntryIds":["a","b","native","web"]}""" }
        val created = mutableListOf<Page>()
        val p = NativeWhatsNewPresentation(c, this) { page, _, dark -> Page(this, page, dark).also(created::add) }
        p.theme(true); p.reconcile("owner", true); runCurrent(); val page = created.single(); assertTrue(page.dark)
        page.load.finishedInitialPage(); runCurrent(); val token = p.state.value!!.token
        p.appeared(token, "owner", true); p.reconcile("owner", false); p.theme(false); p.reconcile("owner", true)
        assertEquals(token, p.state.value!!.token); assertEquals(1, created.size)
        assertSame(page, p.webPage(w)); assertFalse(page.dark)
        p.dismiss(token); assertEquals(1, page.closes); assertNull(p.webPage(w)); p.close()
    }
    @Test fun retractionWhileLoadingRemovesStalePageEvenWithoutAnotherReconcileCall() = runTest {
        val store = Store(); val c = center(store, emptyList()); c.refresh { feed("old") }
        lateinit var page: Page
        val p = NativeWhatsNewPresentation(c, this) { content, _, dark -> Page(this, content, dark).also { page = it } }
        p.reconcile("owner", true); runCurrent()
        c.refresh { """{"visibleEntryIds":[],"announcements":[]}""" }
        val afterRefresh = store.values.toMap()
        page.load.finishedInitialPage(); runCurrent()
        assertNull(p.state.value); assertEquals(1, page.closes); assertEquals(afterRefresh, store.values); p.close()
    }
}
