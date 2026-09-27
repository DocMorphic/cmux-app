package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserInteractionTest {
    @Test fun pageStateUsesRemoteNavigationAndClampsProgress() {
        val value = JSONObject().put("url", "https://cmux.com").put("title", "cmux").put("can_go_back", true)
            .put("can_go_forward", false).put("is_loading", true).put("progress", 4).put("editable_focused", true)
        val page = BrowserPageState.read(value)
        assertTrue(page.canGoBack); assertFalse(page.canGoForward); assertTrue(page.loading)
        assertTrue(page.editableFocused); assertEquals(1f, page.progress)
        assertEquals(0f, BrowserPageState.read(value.put("progress", -2)).progress)
        assertEquals("", BrowserPageState.read(value.put("url", JSONObject.NULL)).url)
        assertEquals(1f, BrowserPageState.read(JSONObject()).progress)
    }
    @Test fun keyboardManualHideSurvivesRepeatedPageFocusUntilItChanges() {
        var policy = BrowserKeyboardPolicy().pageFocus(true)
        assertTrue(policy.focus)
        policy = policy.hide()
        assertFalse(policy.pageFocus(true).focus)
        assertTrue(policy.pageFocus(false).pageFocus(true).focus)
        assertTrue(policy.toggle().focus)
        assertFalse(BrowserKeyboardPolicy().hide().focus)
        assertTrue(BrowserKeyboardPolicy().toggle().focus)
        assertTrue(BrowserKeyboardPolicy().pageFocus(true).show().focus)
    }
    @Test fun multilineUnicodePasteUsesNativeReturnAndExactPanel() {
        val input = BrowserInput.committed("中文🙂\r\n\nlast\n")
        assertEquals(listOf(BrowserInput.Text("中文🙂"), BrowserInput.Key("return"), BrowserInput.Key("return"),
            BrowserInput.Text("last"), BrowserInput.Key("return")), input)
        input.forEach { assertEquals("panel-B", it.parameters("panel-B").getString("panel_id")) }
        val key = BrowserInput.Key("a", listOf("command", "shift")).parameters("panel-B")
        assertEquals("a", key.getString("key")); assertEquals("command", key.getJSONArray("modifiers").getString(0))
        assertEquals("cmux search", BrowserInput.Navigation("navigate", " cmux search ").parameters("panel-B").getString("url"))
    }
    @Test fun scrollCoalescingKeepsOrderAndGestureBoundariesDuringSlowDelivery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val received = mutableListOf<BrowserInput>()
        val queue = BrowserInputQueue(scope) { received += it; if (received.size == 1) release.await() }
        try {
            queue.offer(BrowserInput.Text("first"))
            queue.offer(BrowserInput.Scroll(0.0, 0.0, 10.0, 20.0, "began"))
            queue.offer(BrowserInput.Scroll(2.0, 4.0, 12.0, 22.0, "changed"))
            queue.offer(BrowserInput.Scroll(3.0, 5.0, 15.0, 25.0, "changed"))
            queue.offer(BrowserInput.Scroll(0.0, 0.0, 15.0, 25.0, "ended"))
            queue.offer(BrowserInput.Key("return"))
            assertEquals(1, received.size)
            release.complete(Unit)
            assertEquals(4, received.size)
            assertEquals(BrowserInput.Scroll(5.0, 9.0, 15.0, 25.0, "began"), received[1])
            assertEquals("ended", (received[2] as BrowserInput.Scroll).phase)
            assertEquals(BrowserInput.Key("return"), received[3])
        } finally { queue.close(); scope.cancel() }
    }
    @Test fun rejectedInputDiscardsTheRemainderAndRequiresExplicitResume() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val received = mutableListOf<BrowserInput>()
        val queue = BrowserInputQueue(scope) { received += it; if (received.size == 1) { release.await(); error("lost acknowledgement") } }
        try {
            queue.offer(BrowserInput.committed("first\nnever replay"))
            release.complete(Unit)
            assertNotNull(queue.error.value)
            assertFalse(queue.offer(BrowserInput.Text("paused")))
            assertEquals(listOf(BrowserInput.Text("first")), received)
            assertTrue(queue.resume()); queue.offer(BrowserInput.Text("explicit new input"))
            assertEquals(listOf(BrowserInput.Text("first"), BrowserInput.Text("explicit new input")), received)
        } finally { queue.close(); scope.cancel() }
    }
    @Test fun oversizePasteIsAtomicAndCannotResumeBeforeInflightCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val received = mutableListOf<BrowserInput>()
        val queue = BrowserInputQueue(scope) { received += it; release.await() }
        try {
            queue.offer(BrowserInput.Text("pending"))
            assertFalse(queue.offer(BrowserInput.committed("not partially sent\n" + "中".repeat(32_000))))
            assertFalse(queue.resume())
            release.complete(Unit); assertTrue(queue.resume())
            queue.close(); assertFalse(queue.offer(BrowserInput.Text("closed")))
            assertEquals(listOf(BrowserInput.Text("pending")), received)
        } finally { queue.close(); scope.cancel() }
    }
    @Test fun timeoutPausesWithoutKillingExplicitRecovery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var count = 0
        val queue = BrowserInputQueue(scope) { if (++count == 1) withTimeout(10) { awaitCancellation() } }
        try {
            queue.offer(BrowserInput.Key("return"))
            withTimeout(3_000) { while (queue.error.value == null) yield() }
            assertTrue(queue.resume()); queue.offer(BrowserInput.Text("after timeout"))
            assertEquals(2, count)
        } finally { queue.close(); scope.cancel() }
    }
    @Test fun streamLossDiscardsWaitingInputEvenWhenInflightDeliveryLaterSucceeds() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val received = mutableListOf<BrowserInput>()
        val queue = BrowserInputQueue(scope) { received += it; release.await() }
        try {
            queue.offer(BrowserInput.Text("in flight"), BrowserInput.Text("discard"))
            queue.pause(); assertFalse(queue.resume())
            release.complete(Unit)
            assertNotNull(queue.error.value)
            assertEquals(listOf(BrowserInput.Text("in flight")), received)
            assertTrue(queue.resume())
        } finally { queue.close(); scope.cancel() }
    }
}
