package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class ArtifactAudioFocusOwnerTest {
    private class Port : ArtifactAudioFocusPort {
        val listeners = mutableListOf<(ArtifactFocusEvent) -> Unit>()
        var allow = true
        var abandoned = 0
        override fun request(listener: (ArtifactFocusEvent) -> Unit): Boolean { listeners += listener; return allow }
        override fun abandon() { abandoned++ }
        fun emit(event: ArtifactFocusEvent) = listeners.last()(event)
    }
    @Test fun preparationDoesNotAcquireAndRepeatedPlaybackReusesOneLease() {
        val port = Port(); val owner = ArtifactAudioFocusOwner(port) {}
        assertFalse(owner.canPlay); assertTrue(port.listeners.isEmpty())
        assertEquals(ArtifactFocusPermission.GRANTED, owner.acquire())
        assertEquals(ArtifactFocusPermission.GRANTED, owner.acquire())
        assertEquals(1, port.listeners.size)
        owner.close(); assertEquals(1, port.abandoned); assertFalse(owner.canPlay)
    }
    @Test fun transientLossWaitsWithoutStealingFocusAndGainPermitsResume() {
        val port = Port(); val changes = mutableListOf<ArtifactFocusEvent>()
        val owner = ArtifactAudioFocusOwner(port, changes::add)
        owner.acquire(); port.emit(ArtifactFocusEvent.TRANSIENT_LOSS)
        assertFalse(owner.canPlay)
        assertEquals(ArtifactFocusPermission.SUSPENDED, owner.acquire())
        assertEquals(1, port.listeners.size)
        port.emit(ArtifactFocusEvent.GAIN)
        assertTrue(owner.canPlay)
        assertEquals(listOf(ArtifactFocusEvent.TRANSIENT_LOSS, ArtifactFocusEvent.GAIN), changes)
    }
    @Test fun pauseOrDisconnectRetiresPendingResumeAndLateCallbacks() {
        val port = Port(); val changes = mutableListOf<ArtifactFocusEvent>()
        val owner = ArtifactAudioFocusOwner(port, changes::add)
        owner.acquire(); val old = port.listeners.last()
        old(ArtifactFocusEvent.TRANSIENT_LOSS); owner.release()
        old(ArtifactFocusEvent.GAIN); assertFalse(owner.canPlay)
        assertEquals(listOf(ArtifactFocusEvent.TRANSIENT_LOSS), changes)
        owner.acquire(); old(ArtifactFocusEvent.LOSS)
        assertTrue(owner.canPlay); assertEquals(1, port.abandoned)
    }
    @Test fun permanentLossRequiresNewPlaybackRequestAndRetiresOldLease() {
        val port = Port(); val changes = mutableListOf<ArtifactFocusEvent>()
        val owner = ArtifactAudioFocusOwner(port, changes::add)
        owner.acquire(); val old = port.listeners.last()
        old(ArtifactFocusEvent.LOSS); old(ArtifactFocusEvent.GAIN)
        assertFalse(owner.canPlay); assertEquals(listOf(ArtifactFocusEvent.LOSS), changes)
        assertEquals(1, port.abandoned)
        assertEquals(ArtifactFocusPermission.GRANTED, owner.acquire())
        assertEquals(2, port.listeners.size)
    }
    @Test fun deniedFocusCannotStartOrResumeButExplicitRetryCanAcquire() {
        val port = Port().apply { allow = false }; val changes = mutableListOf<ArtifactFocusEvent>()
        val owner = ArtifactAudioFocusOwner(port, changes::add)
        assertEquals(ArtifactFocusPermission.DENIED, owner.acquire())
        port.emit(ArtifactFocusEvent.GAIN); assertFalse(owner.canPlay); assertTrue(changes.isEmpty())
        port.allow = true
        assertEquals(ArtifactFocusPermission.GRANTED, owner.acquire())
    }
    @Test fun duckingRestoresVolumeAndClosedOwnerIgnoresAllCallbacks() {
        val port = Port(); val owner = ArtifactAudioFocusOwner(port) {}
        owner.acquire(); port.emit(ArtifactFocusEvent.DUCK)
        assertTrue(owner.canPlay); assertEquals(.2f, owner.volumeMultiplier)
        port.emit(ArtifactFocusEvent.GAIN); assertEquals(1f, owner.volumeMultiplier)
        owner.close(); port.emit(ArtifactFocusEvent.GAIN)
        assertFalse(owner.canPlay); assertEquals(ArtifactFocusPermission.DENIED, owner.acquire())
        owner.close(); assertEquals(1, port.abandoned)
    }
}
