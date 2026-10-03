package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativeWhatsNewPresentationTest {
    private class Store : WhatsNewStorage {
        val values = mutableMapOf<String, String>(); var fail = false
        override fun read(key: String) = values[key]
        override fun write(updates: Map<String, String?>): Boolean {
            if (fail) return false
            updates.forEach { (key, value) -> if (value != null) values[key] = value else values.remove(key) }; return true
        }
    }
    private fun center(store: Store) = NativeWhatsNewCenter(NativeWhatsNewCatalog.pages, "0.2.0", WhatsNewChannel.BETA, store)
    @Test fun stagingAndCompetingModalNeverWriteAcknowledgement() = runTest {
        val store = Store(); val c = center(store); c.refresh(); val p = NativeWhatsNewPresentation(c)
        p.reconcile("account", false); assertNull(p.state.value)
        p.reconcile("account", true); val staged = p.state.value!!; assertTrue(store.values.isEmpty())
        p.reconcile("account", false); assertNull(p.state.value)
        p.appeared(staged.token, "account", true); assertTrue(store.values.isEmpty())
        p.reconcile("account", true); assertNotEquals(staged.token, p.state.value!!.token)
    }
    @Test fun visibleSnapshotAndPageSurviveRefreshBackgroundAndRecreationOwner() = runTest {
        val store = Store(); val c = center(store); c.refresh(); val p = NativeWhatsNewPresentation(c)
        p.reconcile("account", true); val original = p.state.value!!
        p.appeared(original.token, "account", true); p.select(original.token, 1)
        c.refresh { "{\"visibleEntryIds\":[]}" }
        p.reconcile("account", true)
        assertEquals(original.pages, p.state.value!!.pages); assertEquals(1, p.state.value!!.pageIndex)
        p.reconcile("account", false); assertTrue(p.state.value!!.appeared)
        p.reconcile("account", true); assertEquals(original.token, p.state.value!!.token)
        p.dismiss(original.token); assertNull(p.state.value); p.reconcile("account", true); assertNull(p.state.value)
    }
    @Test fun ownerReplacementRejectsLateAppearanceAndDoesNotRequireComputers() = runTest {
        val store = Store(); val c = center(store); c.refresh(); val p = NativeWhatsNewPresentation(c)
        p.reconcile("old", true); val old = p.state.value!!
        p.reconcile("new", true); val new = p.state.value!!
        p.appeared(old.token, "old", true); assertTrue(store.values.isEmpty())
        p.appeared(new.token, "old", true); assertTrue(store.values.isEmpty())
        p.appeared(new.token, "new", true); assertTrue(p.state.value!!.appeared)
        assertNotNull(store.values[NativeWhatsNewCenter.MARKER])
    }
    @Test fun failedSaveDoesNotReopenInLoopButCanReturnNextLaunch() = runTest {
        val store = Store(); val c = center(store); c.refresh(); val p = NativeWhatsNewPresentation(c)
        store.fail = true; p.reconcile("account", true); val token = p.state.value!!.token
        p.appeared(token, "account", true); p.dismiss(token); p.reconcile("account", true)
        assertNull(p.state.value); assertNotNull(c.state.value.error)
        assertEquals(2, center(store).state.value.unseen.size)
    }
    @Test fun retractionBeforeAppearanceDoesNotAcknowledgeFrozenStaging() = runTest {
        val store = Store(); val c = center(store); c.refresh(); val p = NativeWhatsNewPresentation(c)
        p.reconcile("account", true); val token = p.state.value!!.token
        c.refresh { "{\"visibleEntryIds\":[]}" }
        p.appeared(token, "account", true)
        assertNull(p.state.value); assertNull(store.values[NativeWhatsNewCenter.MARKER])
    }
}
