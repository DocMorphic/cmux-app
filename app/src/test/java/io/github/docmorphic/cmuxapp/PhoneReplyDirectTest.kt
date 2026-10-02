package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneReplyDirectTest {
    private val target = PhoneReplyDirectTarget(NativeTeamScope("login", "user", "team", 0), "origin", "epoch",
        PhonePushPeer(PhonePushTuple("user", null, "fixture", "phone", "mac", "stable", "fixture.mac"),
            PhonePushIdentity.generate().descriptor()), "w", "s", false)
    private fun workspace(id: String = "w", surface: String = "s") = NativeWorkspace(id, "Fixture",
        listOf(NativeTerminal(surface, "Terminal")), null, false, null, null, false, emptyList(), null, null, null)

    @Test fun topologyPreservesConfinementAndRequiresOneReadyOwner() {
        val original = workspace(); val moved = workspace("moved")
        assertEquals("w", target.resolve(listOf(original)))
        assertNull(target.resolve(listOf(moved)))
        assertEquals("moved", target.copy(retarget = true).resolve(listOf(moved)))
        assertEquals("moved", target.copy(workspace = null, retarget = true).resolve(listOf(moved)))
        assertNull(target.copy(workspace = null).resolve(listOf(moved)))
        assertNull(target.copy(workspace = null, retarget = true).resolve(listOf(original, moved)))
        assertNull(target.resolve(listOf(original.copy(terminals = listOf(NativeTerminal("s", "Terminal", isReady = false))))))
        assertNull(target.resolve(listOf(workspace(surface = "other"))))
    }

    @Test fun unavailableNeverInvokesDeliveryAndSuccessfulReplyPreservesText() = runTest {
        val unavailable = PhoneReplyDirectAttempt(target, { false }) { _, _ -> error("must not write") }
        assertEquals(PhoneReplyDirectResult.UNAVAILABLE, unavailable.send("reply") { true })
        val retired = PhoneReplyDirectAttempt(target, { true }) { _, _ -> error("must not write") }
        assertEquals(PhoneReplyDirectResult.UNAVAILABLE, retired.send("reply") { false })
        var text: String? = null
        val direct = PhoneReplyDirectAttempt(target, { true }) { value, allowed -> assertTrue(allowed()); text = value; true }
        assertEquals(PhoneReplyDirectResult.DELIVERED, direct.send(" literal λ 中\n") { true })
        assertEquals(" literal λ 中\n", text)
    }

    @Test fun lostAcknowledgementIsUnknownAndQueuedWorkCannotBeginAfterTimeout() = runTest {
        var writes = 0; var lateAdmission: (() -> Boolean)? = null
        val direct = PhoneReplyDirectAttempt(target, { true }) { _, allowed ->
            lateAdmission = allowed; writes++; awaitCancellation()
        }
        assertEquals(PhoneReplyDirectResult.UNKNOWN, direct.send("reply") { true })
        assertEquals(1, writes); assertFalse(lateAdmission!!())
        val failed = PhoneReplyDirectAttempt(target, { true }) { _, _ -> throw java.io.EOFException() }
        assertEquals(PhoneReplyDirectResult.UNKNOWN, failed.send("reply") { true })
    }

    @Test fun partialPasteAndMissingSubmissionEvidenceCannotBeRelayedOrClaimedDelivered() = runTest {
        val partial = PhoneReplyDirectAttempt(target, { true }) { _, _ ->
            checkPhoneReplyPaste(JSONObject().put("submitted", false).put("submit_error", "input_queue_full")); true
        }
        assertEquals(PhoneReplyDirectResult.SUBMIT_REQUIRED, partial.send("reply") { true })
        val duplicateWithoutSubmitEvidence = PhoneReplyDirectAttempt(target, { true }) { _, _ ->
            checkPhoneReplyPaste(JSONObject()); true
        }
        assertEquals(PhoneReplyDirectResult.UNKNOWN, duplicateWithoutSubmitEvidence.send("reply") { true })
        checkPhoneReplyPaste(JSONObject().put("submitted", true))
    }

    @Test fun queuedReplyRespectsExistingInputOrderAndRetirementBeforeItsTurn() = runTest {
        val first = CompletableDeferred<Unit>(); val trace = mutableListOf<String>()
        val queue = TerminalInputQueue(backgroundScope) { trace += it.text; first.await() }
        queue.offer("earlier")
        var current = true
        val direct = PhoneReplyDirectAttempt(target, { current }) { text, allowed ->
            queue.performOrdered { if (!allowed()) false else { trace += text; true } }
        }
        val result = async { direct.send("reply") { true } }
        yield(); current = false; first.complete(Unit)
        assertEquals(PhoneReplyDirectResult.UNAVAILABLE, result.await())
        assertEquals(listOf("earlier"), trace); queue.close()
    }
}
