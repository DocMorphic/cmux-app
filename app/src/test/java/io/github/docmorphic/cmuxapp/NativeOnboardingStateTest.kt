package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeOnboardingStateTest {
    @Test fun replayBackgroundAndPendingPairingNeverStartAnIncidentalConnection() {
        fun allowed(foreground: Boolean = true, pairing: Boolean = false, replay: Boolean = false, requested: Boolean = false,
            automatic: Boolean = true, code: Boolean = false, busy: Boolean = false, ready: Boolean = false) =
            nativeOnboardingMayChoose(true, foreground, pairing, replay, requested, automatic, code, busy, ready)
        assertTrue(allowed())
        assertFalse(allowed(foreground = false)); assertFalse(allowed(pairing = true))
        assertFalse(allowed(replay = true)); assertTrue(allowed(replay = true, requested = true))
        assertFalse(allowed(automatic = false)); assertFalse(allowed(code = true))
        assertFalse(allowed(busy = true)); assertFalse(allowed(ready = true))
    }
    @Test fun missingAndUnrecognizedProgressStartWelcome() {
        for (raw in listOf(null, "", "future", "complete")) {
            assertEquals(NativeOnboardingProgress.WELCOME, NativeOnboardingProgressStore({ raw }, { true }).progress)
        }
    }
    @Test fun durableConnectResumesAndCompletionCannotRegress() {
        var raw: String? = null
        fun store() = NativeOnboardingProgressStore({ raw }, { raw = it; true })
        store().connect()
        assertEquals(NativeOnboardingProgress.CONNECT, store().progress)
        store().complete(); store().connect()
        assertEquals(NativeOnboardingProgress.COMPLETE, store().progress)
    }
    @Test fun failedWriteDoesNotPretendToComplete() {
        val store = NativeOnboardingProgressStore({ "CONNECT" }, { false })
        assertThrows(IllegalStateException::class.java) { store.complete() }
        assertEquals(NativeOnboardingProgress.CONNECT, store.progress)
    }
    @Test fun fixtureBypassNeverChangesRealInstallProgress() {
        var writes = 0
        val store = NativeOnboardingProgressStore({ "WELCOME" }, { writes++; true }, forceComplete = true)
        store.connect(); store.complete()
        assertEquals(NativeOnboardingProgress.COMPLETE, store.progress)
        assertEquals(0, writes)
    }
    @Test fun cachedCredentialsRestorationAndExplicitRoutesCannotEnterTour() {
        val p = NativeOnboardingProgress.WELCOME
        assertFalse(nativeOnboardingEligible(p, false, true, false, false))
        assertFalse(nativeOnboardingEligible(p, true, false, false, false))
        assertFalse(nativeOnboardingEligible(p, true, true, true, false))
        assertFalse(nativeOnboardingEligible(p, true, true, false, true))
        assertTrue(nativeOnboardingEligible(p, true, true, false, false))
        assertFalse(nativeOnboardingEligible(NativeOnboardingProgress.COMPLETE, true, true, false, false))
    }
    @Test fun readyWinsOverSearchAndDirectoryCompletionAloneIsNotReady() {
        assertEquals(NativeOnboardingPhase.READY, NativeOnboardingPhase.resolve(true, true, true))
        assertEquals(NativeOnboardingPhase.SEARCHING, NativeOnboardingPhase.resolve(false, true, true))
        assertEquals(NativeOnboardingPhase.FALLBACK, NativeOnboardingPhase.resolve(false, false, true))
        assertEquals(NativeOnboardingPhase.IDLE, NativeOnboardingPhase.resolve(false, false, false))
    }
    @Test fun requiredPairingAndConnectionHaveNoSkip() {
        for (phase in NativeOnboardingPhase.entries) for (method in NativeOnboardingMethod.entries) {
            for (stage in listOf(NativeOnboardingStage.PAIRING, NativeOnboardingStage.CONNECT))
                assertFalse(NativeOnboardingChrome.forStage(stage, phase, method).skip)
        }
        assertFalse(NativeOnboardingChrome.forStage(NativeOnboardingStage.AGENTS, NativeOnboardingPhase.IDLE, NativeOnboardingMethod.AUTOMATIC).back)
    }
    @Test fun connectionActionsDistinguishMethodReadinessAndSearch() {
        fun chrome(phase: NativeOnboardingPhase, method: NativeOnboardingMethod) = NativeOnboardingChrome.forStage(NativeOnboardingStage.CONNECT, phase, method)
        assertEquals("Check for My Mac", chrome(NativeOnboardingPhase.IDLE, NativeOnboardingMethod.AUTOMATIC).primary)
        assertEquals("Scan Pairing Code", chrome(NativeOnboardingPhase.IDLE, NativeOnboardingMethod.TAILSCALE).primary)
        assertEquals("Check Again", chrome(NativeOnboardingPhase.FALLBACK, NativeOnboardingMethod.TAILSCALE).secondary)
        for (method in NativeOnboardingMethod.entries) {
            assertNull(chrome(NativeOnboardingPhase.SEARCHING, method).primary)
            assertEquals("Open Workspaces", chrome(NativeOnboardingPhase.READY, method).primary)
        }
    }
}
