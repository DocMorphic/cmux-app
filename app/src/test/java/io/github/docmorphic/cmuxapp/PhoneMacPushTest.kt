package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneMacPushTest {
    private fun status() = JSONObject().put("forwarding_enabled", true).put("mode", "onlyWhenAway")
        .put("hide_content", true).put("admission", "suppressed_mac_active").put("queue_persistence", "healthy")
        .put("account_scope", "verified_same_account").put("api_origin", "https://cmux.com")
    private fun host(settings: JSONObject = status(), capable: Boolean = true) = JSONObject().put("phone_push", settings)
        .put("capabilities", JSONArray(if (capable) listOf("phone_push.settings.v1") else emptyList<String>()))

    @Test fun unknownOrMalformedAuthorityDoesNotEnableControls() {
        val valid = status()
        assertEquals(PhoneMacPushMode.AWAY, PhoneMacPushStatus.parse(valid)?.mode)
        for ((field, value) in listOf("account_scope" to "other_account", "forwarding_enabled" to "true",
            "mode" to "future", "hide_content" to 1, "admission" to "future", "queue_persistence" to "future", "api_origin" to 12)) {
            assertNull(field, PhoneMacPushStatus.parse(JSONObject(valid.toString()).put(field, value)))
        }
        valid.remove("hide_content"); valid.remove("admission"); valid.remove("queue_persistence")
        val legacy = checkNotNull(PhoneMacPushStatus.parse(valid))
        assertFalse(legacy.hideContent); assertEquals("unknown", legacy.admission)
        assertNull(PhoneMacPushStatus.parse(null))
    }

    @Test fun mutationUsesRefetchedHostValuesIncludingConcurrentMacChanges() = runTest {
        var remote = host(); val writes = mutableListOf<PhoneMacPushChange>()
        val controller = PhoneMacPushController({ true }, { remote }, {
            writes += it
            remote = host(status().put("mode", "always").put("hide_content", false))
            JSONObject().put("mode", "always")
        })
        controller.refresh()
        assertTrue(controller.state.value.canChange)
        controller.change(PhoneMacPushChange.Mode(PhoneMacPushMode.ALWAYS))
        assertEquals(1, writes.size)
        assertEquals(PhoneMacPushMode.ALWAYS, controller.state.value.status?.mode)
        assertEquals(false, controller.state.value.status?.hideContent)
        assertFalse(controller.state.value.busy)
    }

    @Test fun lostAcknowledgmentIsNotReplayedAndRefreshRecoversActualCommittedState() = runTest {
        var remote = host(); var writes = 0
        val controller = PhoneMacPushController({ true }, { remote }, {
            writes++; remote = host(status().put("forwarding_enabled", false))
            throw java.io.IOException("lost response")
        })
        controller.refresh(); controller.change(PhoneMacPushChange.Enabled(false))
        assertTrue(controller.state.value.stale); assertFalse(controller.state.value.canChange)
        assertEquals(true, controller.state.value.status?.enabled) // Last confirmed, clearly marked stale.
        controller.change(PhoneMacPushChange.Enabled(false)); assertEquals(1, writes)
        controller.refresh()
        assertEquals(false, controller.state.value.status?.enabled)
        assertNull(controller.state.value.error); assertTrue(controller.state.value.canChange)
    }

    @Test fun lateAccountResponseCannotPopulateNewOwnerSettings() = runTest {
        var current = true; val response = CompletableDeferred<JSONObject>()
        val controller = PhoneMacPushController({ current }, { response.await() }, { error("must not write") })
        val job = launch { controller.refresh() }; runCurrent()
        current = false; response.complete(host()); job.join()
        assertNull(controller.state.value.status); assertFalse(controller.state.value.canChange)
    }

    @Test fun unsupportedHostNeverSendsMutation() = runTest {
        val controller = PhoneMacPushController({ true }, { host(capable = false) }, { error("must not write") })
        controller.refresh(); assertNotNull(controller.state.value.status)
        assertFalse(controller.state.value.canChange)
        controller.change(PhoneMacPushChange.HideContent(false))
    }

    @Test fun repeatedGestureIsCoalescedButFirstWaitsForInFlightRefresh() = runTest {
        var heldRead: CompletableDeferred<Unit>? = null; var writes = 0
        val controller = PhoneMacPushController({ true }, { heldRead?.await(); host() }, { writes++; JSONObject() })
        controller.refresh()
        val waiting = CompletableDeferred<Unit>(); heldRead = waiting
        val refresh = launch { controller.refresh() }; runCurrent()
        val first = launch { controller.change(PhoneMacPushChange.Enabled(false)) }; runCurrent()
        assertTrue(controller.state.value.busy)
        val second = launch { controller.change(PhoneMacPushChange.Enabled(false)) }; runCurrent()
        assertEquals(0, writes)
        heldRead = null; waiting.complete(Unit); joinAll(refresh, first, second)
        assertEquals(1, writes); assertFalse(controller.state.value.busy)
    }

    @Test fun retiredMutationAndCancelledReadLeaveNoEditableSnapshot() = runTest {
        var current = true; val sent = CompletableDeferred<Unit>()
        val controller = PhoneMacPushController({ current }, { host() }, { sent.await(); JSONObject() })
        controller.refresh()
        val job = launch { controller.change(PhoneMacPushChange.Enabled(false)) }; runCurrent()
        current = false; sent.complete(Unit); job.join()
        assertNull(controller.state.value.status); assertFalse(controller.state.value.busy)
        val waiting = PhoneMacPushController({ true }, { awaitCancellation() }, { JSONObject() })
        val read = launch { waiting.refresh() }; runCurrent(); read.cancelAndJoin()
        assertFalse(waiting.state.value.canChange)
    }
}
