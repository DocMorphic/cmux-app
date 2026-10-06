package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerDictationTest {
    private class Engine : ComposerSpeechEngine {
        lateinit var listener: ComposerSpeechEngine.Listener
        var closes = 0; var stops = 0
        var startFailure = false; var stopFailure = false
        override fun start(listener: ComposerSpeechEngine.Listener) {
            this.listener = listener
            if (startFailure) error("synthetic start failure")
        }
        override fun stop() { stops++; if (stopFailure) error("synthetic stop failure") }
        override fun close() { closes++; listener.transcript("callback during destruction", true) }
    }
    private class Fixture(scope: TestScope, initial: String = "Explain") {
        var text = initial; var current = true; var acceptWrite = true
        val engines = mutableListOf<Engine>()
        var make: () -> Engine = { Engine() }
        val controller = ComposerDictation(scope,
            { make().also { engines += it } }, { current }, { text }, { if (acceptWrite) { text = it; true } else false })
        fun start(): Engine {
            controller.permission(checkNotNull(controller.request()), true)
            return engines.last().also { it.listener.ready() }
        }
    }
    @Test fun partialsReplaceTheirTailAndFinalRefinesWithoutRepeatingTheBase() = runTest {
        val f = Fixture(this, "Explain\n"); val engine = f.start()
        engine.listener.transcript("  the pro", false); assertEquals("Explain\nthe pro", f.text)
        engine.listener.transcript("the problem", false); assertEquals("Explain\nthe problem", f.text)
        engine.listener.transcript("the problem.", true); assertEquals("Explain\nthe problem.", f.text)
        assertEquals(ComposerDictation.Phase.IDLE, f.controller.state.value.phase); assertEquals(1, engine.closes)
    }
    @Test fun blankResultsKeepTheLastWordsAndWhitespaceJoinsMatchIos() = runTest {
        val f = Fixture(this); val engine = f.start()
        engine.listener.transcript("λ world", false)
        engine.listener.transcript(" \n", false); engine.listener.transcript(null, true)
        assertEquals("Explain λ world", f.text)
        assertEquals("hello world", ComposerDictation.merge("hello", "  world"))
        assertEquals("hello\tworld", ComposerDictation.merge("hello\t", "world"))
        assertEquals("world", ComposerDictation.merge("", " world"))
        assertEquals("hello", ComposerDictation.merge("hello", ""))
    }
    @Test fun stopKeepsFieldLockedUntilFinalAndCancelsItsWatchdog() = runTest {
        val f = Fixture(this); val engine = f.start()
        engine.listener.transcript("one", false); f.controller.stop()
        assertEquals(1, engine.stops); assertTrue(f.controller.state.value.locksField)
        engine.listener.transcript("one two", true)
        advanceTimeBy(3_000); runCurrent()
        assertEquals("Explain one two", f.text); assertEquals(1, engine.closes)
        assertFalse(f.controller.state.value.locksField)
    }
    @Test fun stopTimeoutRetainsLatestPartialAndRejectsLateFinal() = runTest {
        val f = Fixture(this); val engine = f.start()
        engine.listener.transcript("one", false); f.controller.stop()
        advanceTimeBy(2_500); runCurrent()
        assertFalse(f.controller.state.value.locksField); assertEquals(1, engine.closes)
        engine.listener.transcript("late words", true); assertEquals("Explain one", f.text)
    }
    @Test fun naturalEndWaitsForFinalWithoutStoppingTwice() = runTest {
        val f = Fixture(this); val engine = f.start()
        engine.listener.ended(); engine.listener.ended()
        assertEquals(0, engine.stops); assertEquals(ComposerDictation.Phase.STOPPING, f.controller.state.value.phase)
        engine.listener.transcript("complete", true); assertEquals("Explain complete", f.text)
    }
    @Test fun sendCancelsBeforeSnapshotAndCannotRefillClearedDraft() = runTest {
        val f = Fixture(this); val engine = f.start()
        engine.listener.transcript("send now", false); f.controller.cancel()
        val sent = f.text; f.text = ""
        engine.listener.transcript("send now plus late tail", true)
        assertEquals("Explain send now", sent); assertEquals("", f.text); assertEquals(1, engine.closes)
    }
    @Test fun cancelledPermissionAndOldCallbacksCannotStartOrStopReplacement() = runTest {
        val f = Fixture(this); val oldToken = f.controller.request()!!
        assertTrue(f.controller.state.value.locksField); assertNull(f.controller.request())
        f.controller.cancel(); val engine = f.start()
        f.controller.permission(oldToken, true); assertEquals(1, f.engines.size)
        engine.listener.transcript("first", false); f.controller.cancel()
        val replacement = f.start()
        engine.listener.ready(); engine.listener.failed("old failure"); engine.listener.transcript("old", true)
        assertEquals(0, replacement.closes); assertEquals(ComposerDictation.Phase.LISTENING, f.controller.state.value.phase)
        replacement.listener.transcript("second", true); assertEquals("Explain first second", f.text)
    }
    @Test fun terminalRetirementAndExternalEditsCancelWithoutClobberingTheDraft() = runTest {
        val f = Fixture(this); val engine = f.start()
        f.current = false; engine.listener.transcript("wrong terminal", false)
        assertEquals("Explain", f.text); assertEquals(1, engine.closes)
        f.current = true; val second = f.start(); f.text = "external edit"
        second.listener.transcript("stale partial", true)
        assertEquals("external edit", f.text); assertEquals(1, second.closes)
    }
    @Test fun ownerCanRejectAWriteEvenBeforeCompositionObservesRetirement() = runTest {
        val f = Fixture(this); val engine = f.start(); f.acceptWrite = false
        engine.listener.transcript("revoked", false)
        assertEquals("Explain", f.text); assertEquals(1, engine.closes); assertFalse(f.controller.state.value.locksField)
    }
    @Test fun deniedPermissionIsRetryableAfterSettingsAndDestroyIsPermanent() = runTest {
        val f = Fixture(this); f.controller.permission(f.controller.request()!!, false)
        assertTrue(f.engines.isEmpty()); assertNotNull(f.controller.state.value.error)
        val engine = f.start(); f.controller.close(); f.controller.cancel(); f.controller.close()
        engine.listener.transcript("late", true)
        assertNull(f.controller.request()); assertEquals(ComposerDictation.Phase.CLOSED, f.controller.state.value.phase)
        assertEquals(1, engine.closes); assertEquals("Explain", f.text)
    }
    @Test fun startTimeoutAndExceptionsReleaseResourcesAndAllowRetry() = runTest {
        val f = Fixture(this)
        f.controller.permission(f.controller.request()!!, true)
        advanceTimeBy(10_000); runCurrent()
        assertFalse(f.controller.state.value.locksField); assertEquals(1, f.engines.single().closes)
        f.make = { Engine().apply { startFailure = true } }
        f.controller.permission(f.controller.request()!!, true)
        assertEquals(1, f.engines.last().closes); assertNotNull(f.controller.state.value.error)
        f.make = { Engine().apply { stopFailure = true } }
        val engine = f.start(); f.controller.stop(); assertEquals(1, engine.closes)
        assertFalse(f.controller.state.value.locksField)
    }
    @Test fun errorsKeepPartialsAndPendingStartCanBeCancelledWithoutListening() = runTest {
        val f = Fixture(this); val engine = f.start()
        engine.listener.transcript("keep me", false); engine.listener.failed("connection lost")
        assertEquals("Explain keep me", f.text); assertEquals("connection lost", f.controller.state.value.error)
        val token = f.controller.request()!!; f.controller.permission(token, true)
        val starting = f.engines.last(); f.controller.stop(); starting.listener.ready()
        assertEquals(1, starting.closes); assertFalse(f.controller.state.value.locksField)
        assertEquals("Explain keep me", f.text)
    }
}
