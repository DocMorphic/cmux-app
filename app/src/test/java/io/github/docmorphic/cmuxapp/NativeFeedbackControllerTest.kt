package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeFeedbackControllerTest {
    private val stamp = NativeFeedbackStamp("0.2", "1", "app", "dev", "Android", "Pixel", "en")
    private fun draft(controller: NativeFeedbackController): String {
        controller.open("login", "reply@example.test")
        return checkNotNull(controller.state.value.id).also { controller.edit(it, message = "Draft 你好") }
    }
    @Test fun draftAndFailureRestoreWithoutDispatchAndAreScopedToTheLogin() = runTest {
        var saved: String? = null
        val original = NativeFeedbackController(this) { saved = it }
        val id = draft(original)
        original.send(id, stamp) { _, _, _ -> throw java.io.IOException("offline") }; runCurrent()
        val restored = NativeFeedbackController(this, saved)
        assertEquals("Draft 你好", restored.state.value.message); assertEquals("offline", restored.state.value.error)
        restored.bind("other")
        assertNull(restored.state.value.id); assertEquals("", restored.state.value.message)
    }
    @Test fun pendingProcessSnapshotBecomesUncertainWithoutReplayingAndCanBeRetriedExplicitly() = runTest {
        var saved: String? = null; var sent = 0
        val original = NativeFeedbackController(backgroundScope, save = { saved = it })
        val id = draft(original)
        original.send(id, stamp) { _, _, _ -> sent++; awaitCancellation() }; runCurrent()
        assertEquals(1, sent)
        val restored = NativeFeedbackController(this, saved)
        assertFalse(restored.state.value.sending)
        assertEquals(NativeFeedbackController.INTERRUPTED, restored.state.value.error)
        assertEquals(1, sent)
        restored.send(id, stamp) { _, _, _ -> sent++ }; runCurrent()
        assertEquals(2, sent); assertNull(restored.state.value.id); assertEquals(id, restored.state.value.receipt)
    }
    @Test fun repeatedSendIsIgnoredAndDismissedSuccessCannotCloseANewerDraft() = runTest {
        val released = CompletableDeferred<Unit>(); var sent = 0
        val controller = NativeFeedbackController(this)
        val id = draft(controller)
        val submit: suspend (String, String, NativeFeedbackStamp) -> Unit = { _, _, _ ->
            sent++; withContext(NonCancellable) { released.await() }
        }
        controller.send(id, stamp, submit); controller.send(id, stamp, submit); runCurrent()
        assertEquals(1, sent)
        controller.dismiss(id); val next = draft(controller)
        released.complete(Unit); runCurrent()
        assertEquals(next, controller.state.value.id); assertFalse(controller.state.value.sending); assertNull(controller.state.value.receipt)
    }
    @Test fun completedReceiptSurvivesRestorationUntilAcknowledgedWithoutRetainingMessage() = runTest {
        var saved: String? = null
        val controller = NativeFeedbackController(this, save = { saved = it })
        val id = draft(controller); controller.send(id, stamp) { _, _, _ -> }; runCurrent()
        val restored = NativeFeedbackController(this, saved)
        assertEquals(id, restored.state.value.receipt); assertEquals("", restored.state.value.message)
        restored.acknowledge(id); assertNull(restored.state.value.receipt)
    }
    @Test fun liveCompletionIsOwnerScopedAndConsumedOnceButNotRestored() = runTest {
        var saved: String? = null
        val controller = NativeFeedbackController(this, save = { saved = it })
        val id = draft(controller)
        controller.send(id, stamp) { _, _, _ -> }; runCurrent()
        assertNull(controller.takeCompletion("other"))
        assertNull(NativeFeedbackController(this, saved).takeCompletion("login"))
        assertEquals(NativeFeedbackCompletion.SUCCESS, controller.takeCompletion("login"))
        assertNull(controller.takeCompletion("login"))
        assertEquals(id, controller.state.value.receipt)
    }
    @Test fun eachExplicitFailedRetryHasOneCompletionAndAccountChangeRetiresIt() = runTest {
        val controller = NativeFeedbackController(this)
        val id = draft(controller)
        repeat(2) {
            controller.send(id, stamp) { _, _, _ -> throw java.io.IOException("offline") }; runCurrent()
            assertEquals(NativeFeedbackCompletion.FAILURE, controller.takeCompletion("login"))
            assertNull(controller.takeCompletion("login"))
        }
        controller.send(id, stamp) { _, _, _ -> }; runCurrent()
        controller.bind("other")
        assertNull(controller.takeCompletion("login"))
        assertNull(controller.takeCompletion("other"))
    }
    @Test fun cancellationAndLateResultsNeverEmitCompletion() = runTest {
        val controller = NativeFeedbackController(this)
        val id = draft(controller)
        controller.send(id, stamp) { _, _, _ -> throw CancellationException("cancelled") }; runCurrent()
        assertNull(controller.takeCompletion("login"))
        val release = CompletableDeferred<Unit>()
        controller.send(id, stamp) { _, _, _ -> withContext(NonCancellable) { release.await() } }; runCurrent()
        controller.dismiss(id)
        draft(controller)
        release.complete(Unit); runCurrent()
        assertNull(controller.takeCompletion("login"))
    }

    @Test fun immediateIdenticalRetryHasADistinctUiEffectKey() = runTest {
        val controller = NativeFeedbackController(this)
        val id = draft(controller)
        val fail: suspend (String, String, NativeFeedbackStamp) -> Unit = { _, _, _ -> throw java.io.IOException("offline") }
        controller.send(id, stamp, fail); runCurrent()
        val first = controller.state.value
        controller.takeCompletion("login")
        // A UI frame can skip the intermediate null completion and sending state.
        controller.send(id, stamp, fail); runCurrent()
        val second = controller.state.value
        assertEquals(first.completion, second.completion)
        assertNotEquals(first.completionId, second.completionId)
        assertEquals(NativeFeedbackCompletion.FAILURE, controller.takeCompletion("login"))
    }

}
