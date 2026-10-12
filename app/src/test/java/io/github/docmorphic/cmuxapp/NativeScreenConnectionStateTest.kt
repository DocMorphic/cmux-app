package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeScreenConnectionStateTest {
    @Test fun newCompositionUsesSameConnectionAndMetadataWithoutKeepingViewCallbacks() = runBlocking<Unit> {
        val wire = PoolTestTransport()
        val client = MobileRpcClient(wire, { "fixture" }); client.connect()
        val owner = NativeScreenConnectionState("original")
        try {
            owner.client = client; owner.connectedCode = "original"; owner.connectionReady = true; owner.admittedRetry = 4
            owner.hostName = "Verified Mac"; owner.hostCapabilities = setOf("terminal.resize.v1")
            val old = NativeScreenTransientState("original", emptyList(), false, connectionState = owner)
            old.terminalClick = {}
            val replacement = NativeScreenTransientState("ignored", emptyList(), false, connectionState = owner)
            assertSame(client, replacement.client); assertEquals("original", replacement.code)
            assertEquals("Verified Mac", replacement.hostName); assertEquals(owner.hostCapabilities, replacement.hostCapabilities)
            assertNull(replacement.terminalClick); assertNotNull(old.terminalClick)
            val oldGeneration = ++old.replayGeneration
            ++replacement.replayGeneration
            assertNotEquals(oldGeneration, old.replayGeneration)
            owner.viewportGeneration.longValue = 17
            assertEquals(17L, replacement.connectionState.viewportGeneration.longValue)
            assertSame(client, owner.retained("original", 4) { true })
            assertEquals(1, wire.connects.get()); assertEquals(0, wire.closes.get())
        } finally { owner.close() }
        assertEquals(1, wire.closes.get())
    }

    @Test fun retryCodeLossOfAdmissionAndClosedHandleCannotRetainAConnection() = runBlocking<Unit> {
        val client = MobileRpcClient(PoolTestTransport(), { "fixture" }); client.connect()
        val owner = NativeScreenConnectionState("one")
        owner.client = client; owner.connectedCode = "one"; owner.connectionReady = true; owner.admittedRetry = 3
        assertNull(owner.retained("one", 4) { true })
        assertNull(owner.retained("other", 3) { true })
        assertNull(owner.retained("one", 3) { false })
        owner.connectionReady = false; assertNull(owner.retained("one", 3) { true })
        owner.connectionReady = true; client.close(); assertNull(owner.retained("one", 3) { true })
        owner.close(); assertEquals("", owner.code); assertFalse(owner.connectionReady)
    }

    @Test fun replacementClosesOnlyPreviousHandleAndRepeatedAssignmentDoesNotDisposeCurrent() = runBlocking<Unit> {
        val firstWire = PoolTestTransport(); val secondWire = PoolTestTransport()
        val first = MobileRpcClient(firstWire, { "fixture" }); first.connect()
        val second = MobileRpcClient(secondWire, { "fixture" }); second.connect()
        val owner = NativeScreenConnectionState("one")
        owner.client = first; owner.client = second; owner.client = second
        assertTrue(first.isClosed); assertFalse(second.isClosed)
        assertEquals(1, firstWire.closes.get()); assertEquals(0, secondWire.closes.get())
        owner.hostName = "old"; owner.hostCapabilities = setOf("old"); owner.code = "two"
        assertEquals("cmux", owner.hostName); assertTrue(owner.hostCapabilities.isEmpty())
        owner.close(); owner.close(); assertEquals(1, secondWire.closes.get())
    }
}
