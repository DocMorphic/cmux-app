package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeWhatsNewWebLoadTest {
    private val policy = WhatsNewWebPolicy(null)
    @Test fun stalledExchangeReachesWholeLoadDeadlineAndLateFinishCannotRecover() = runTest {
        var stopped = 0; var disposed = 0
        val load = NativeWhatsNewWebLoad(this, policy, "https://cmux.com/news", 10_000, { stopped++ }, { disposed++ }) { awaitCancellation() }
        runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(WhatsNewWebPhase.FAILED, load.outcome()); assertEquals(1, stopped)
        load.finishedInitialPage(); assertEquals(WhatsNewWebPhase.FAILED, load.outcome())
        load.close(); load.close(); assertEquals(1, disposed)
    }
    @Test fun successfulLoadResumesAllWaitersAndLateWaitersReturnImmediately() = runTest {
        var stops = 0; var disposed = 0
        val load = NativeWhatsNewWebLoad(this, policy, "https://cmux.com/news", 10_000, { stops++ }, { disposed++ }) {}
        val first = async { load.outcome() }; val second = async { load.outcome() }
        runCurrent(); load.finishedInitialPage()
        assertEquals(WhatsNewWebPhase.LOADED, first.await()); assertEquals(first.await(), second.await())
        assertEquals(WhatsNewWebPhase.LOADED, load.outcome())
        advanceTimeBy(10_001); runCurrent(); assertEquals(0, stops)
        load.close(); assertEquals(1, disposed)
    }
    @Test fun invalidInitialUrlNeverStartsAndOffsiteInitialRedirectFailsImmediately() = runTest {
        var starts = 0
        val invalid = NativeWhatsNewWebLoad(this, policy, "https://evil.test", 10_000, {}, {}) { starts++ }
        runCurrent(); assertEquals(0, starts); assertEquals(WhatsNewWebPhase.FAILED, invalid.outcome()); invalid.close()
        val valid = NativeWhatsNewWebLoad(this, policy, "https://cmux.com/news", 10_000, {}, {}) { starts++ }
        runCurrent(); assertTrue(valid.allowsNavigation("about:blank", true))
        assertFalse(valid.allowsNavigation("https://evil.test", false)); assertEquals(WhatsNewWebPhase.LOADING, valid.phase.value)
        assertFalse(valid.allowsNavigation("http://cmux.com/news", true)); assertEquals(WhatsNewWebPhase.FAILED, valid.outcome()); valid.close()
    }
    @Test fun rejectedLinksAndLaterErrorsPreserveAlreadyRenderedPage() = runTest {
        var stops = 0
        val load = NativeWhatsNewWebLoad(this, policy, "https://cmux.com/news", 10_000, { stops++ }, {}) {}
        runCurrent(); load.finishedInitialPage()
        assertFalse(load.allowsNavigation("https://evil.test", true)); load.failedInitialPage()
        assertEquals(WhatsNewWebPhase.LOADED, load.outcome()); assertEquals(0, stops)
        assertTrue(load.allowsNavigation("https://cmux.com/next", true)); load.close()
    }
    @Test fun parentRetirementDisposesLoadedRendererAndResolvesPendingLoads() = runTest {
        val parent = Job(); val owner = CoroutineScope(coroutineContext + parent); var disposed = 0
        val loaded = NativeWhatsNewWebLoad(owner, policy, "https://cmux.com/a", 10_000, {}, { disposed++ }) {}
        val pending = NativeWhatsNewWebLoad(owner, policy, "https://cmux.com/b", 10_000, {}, { disposed++ }) { awaitCancellation() }
        runCurrent(); loaded.finishedInitialPage(); parent.cancel(); runCurrent()
        assertEquals(WhatsNewWebPhase.FAILED, pending.outcome()); assertEquals(2, disposed)
    }
    @Test fun concurrentPreloadsShareOneDeadlineWindowAndArchiveRetryGetsFreshStart() = runTest {
        var starts = 0
        fun make(timeout: Long) = NativeWhatsNewWebLoad(this, policy, "https://cmux.com/news", timeout, {}, {}) { starts++; awaitCancellation() }
        val a = make(NativeWhatsNewWebLoad.LAUNCH_DEADLINE_MS); val b = make(NativeWhatsNewWebLoad.LAUNCH_DEADLINE_MS)
        runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(WhatsNewWebPhase.FAILED, a.outcome()); assertEquals(WhatsNewWebPhase.FAILED, b.outcome())
        assertEquals(10_000L, currentTime)
        a.close(); b.close()
        val retry = make(NativeWhatsNewWebLoad.ARCHIVE_DEADLINE_MS); runCurrent()
        assertEquals(3, starts); advanceTimeBy(19_999); assertEquals(WhatsNewWebPhase.LOADING, retry.phase.value)
        advanceTimeBy(1); runCurrent(); assertEquals(WhatsNewWebPhase.FAILED, retry.outcome()); retry.close()
    }
}
