package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class LocalBrowserStateTest {
    private val key = LocalBrowserKey("account", "team", "mac", "workspace")
    @Test fun commandsAndLoadsAreConsumedOnceAndEmptyReadsDoNotChangeState() {
        val surface = LocalBrowserSurface("id", "https://example.com")
        assertEquals("https://example.com", surface.takeWork().url)
        val original = surface.state.value
        repeat(3) { assertEquals(LocalBrowserWork(null, null), surface.takeWork()); assertSame(original, surface.state.value) }
        surface.request(LocalBrowserCommand.BACK); surface.request(LocalBrowserCommand.FORWARD)
        surface.load("https://example.com/new")
        assertEquals(LocalBrowserWork("https://example.com/new", LocalBrowserCommand.FORWARD), surface.takeWork())
        assertEquals(LocalBrowserWork(null, null), surface.takeWork())
    }
    @Test fun redirectsUpdateCommittedUrlWithoutOverwritingAddressWhileEditing() {
        val surface = LocalBrowserSurface("id"); val token = surface.attach()
        surface.location(token, "https://example.com/a", "A", false, false)
        surface.editing(true); surface.editAddress("unfinished search")
        surface.location(token, "https://example.com/redirect", "Redirect", true, false)
        assertEquals("unfinished search", surface.state.value.address)
        assertEquals("https://example.com/redirect", surface.state.value.url)
        assertTrue(surface.state.value.canGoBack)
        surface.editing(false)
        surface.location(token, "https://example.com/final", "Final", true, true)
        assertEquals("https://example.com/final", surface.state.value.address)
    }
    @Test fun remountRestoresUrlAndRejectsOldViewCallbacksAndOldHistory() {
        val surface = LocalBrowserSurface("id", "https://example.com/")
        val first = surface.attach(); surface.takeWork()
        surface.location(first, "https://example.com/committed", "Committed", true, true)
        surface.detach(first); val second = surface.attach()
        assertEquals("https://example.com/committed", surface.takeWork().url)
        assertFalse(surface.state.value.canGoBack); assertFalse(surface.state.value.canGoForward)
        surface.location(first, "https://stale.example/", "Stale", true, true)
        surface.started(first); surface.failed(first, "Stale failure"); surface.detach(first)
        assertEquals("https://example.com/committed", surface.state.value.url)
        assertNull(surface.state.value.error)
        surface.started(second); surface.finished(second)
        assertEquals(1f, surface.state.value.progress)
    }
    @Test fun navigationLifecycleClearsErrorsAndKeepsReplacementLoadsActiveWhenOldLoadIsCancelled() {
        val surface = LocalBrowserSurface("id"); val token = surface.attach()
        surface.started(token); surface.progress(token, .5f); assertTrue(surface.state.value.loading)
        surface.failed(token, "No network"); assertEquals("No network", surface.state.value.error)
        surface.started(token); assertNull(surface.state.value.error)
        surface.stopped(token, true); assertTrue(surface.state.value.loading)
        surface.progress(token, Float.NaN); assertEquals(0f, surface.state.value.progress)
        surface.finished(token); assertFalse(surface.state.value.loading); assertEquals(1f, surface.state.value.progress)
    }
    @Test fun addressSubmissionKeepsEmptyInputIdleAndRequestsResolvedLoadsOnce() {
        val surface = LocalBrowserSurface("id", resolver = LocalBrowserAddress(asciiHost = { it }))
        surface.editAddress("  "); assertFalse(surface.submitAddress()); assertNull(surface.takeWork().url)
        surface.editAddress("localhost:3000"); assertTrue(surface.submitAddress())
        assertEquals("http://localhost:3000", surface.takeWork().url); assertNull(surface.takeWork().url)
    }
    @Test fun storeRetainsSameSurfaceAcrossInventoryRefreshButSeparatesEveryIdentityComponent() {
        var next = 0; val store = LocalBrowserStore(makeId = { "id-${++next}" })
        val first = store.open(key); assertSame(first, store.open(key)); assertSame(first, store.active(key))
        val siblings = listOf(key.copy(accountId = "other"), key.copy(teamId = "other"), key.copy(computerId = "other"), key.copy(workspaceId = "other"))
        val others = siblings.map(store::open); assertTrue(others.all { it !== first })
        store.close(key); assertNull(store.active(key)); assertTrue(first.state.value.closed)
        assertNotSame(first, store.open(key)); assertTrue(others.none { it.state.value.closed })
        store.clear(); assertTrue(others.all { it.state.value.closed })
    }
    @Test fun restorationIsOneShotAndAccountOrTeamChangesRetireOldStateAndCallbacks() {
        val store = LocalBrowserStore(); val surface = store.open(key); val token = surface.attach()
        store.requestRestore(key); assertSame(surface, store.consumeRestore(key)); assertNull(store.consumeRestore(key))
        val next = key.copy(teamId = "new"); val kept = store.open(next)
        store.requestRestore(key); store.retainAccount("account", "new")
        assertTrue(surface.state.value.closed); assertNull(store.consumeRestore(key)); assertSame(kept, store.active(next))
        surface.location(token, "https://late.example/", "Late", true, true)
        assertNotEquals("https://late.example/", surface.state.value.url)
        surface.load("https://late.example/"); surface.request(LocalBrowserCommand.RELOAD)
        assertEquals(LocalBrowserWork(null, null), surface.takeWork())
    }
}
