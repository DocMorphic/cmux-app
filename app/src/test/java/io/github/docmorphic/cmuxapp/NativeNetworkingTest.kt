package io.github.docmorphic.cmuxapp

import io.github.docmorphic.cmuxapp.iroh.IrxEndpointStatus
import org.junit.Assert.*
import org.junit.Test

class NativeNetworkingTest {
    private val relay = IrohV2Relay("https://relay.example/", "secret-fixture-token", 2000, 1500)
    private val control = IrohV2ControlState(ready = true, mode = "websocket", directoryRevision = 17,
        permissionExpiresAt = 1500, relays = listOf(relay), failure = "secret-error-body")

    @Test fun projectsBrokerMetadataWithoutCredentialsOrFailureBodies() {
        val result = NativeNetworkingSnapshot.from(control, IrxEndpointStatus(true, "https://relay.example"), 1000)
        assertEquals(NativeNetworkingSnapshot.Runtime.ACTIVE, result.runtime)
        assertEquals(NativeNetworkingSnapshot.Discovery.PUSH, result.discovery)
        assertEquals(17L, result.revision)
        assertTrue(result.permissionValid)
        assertTrue(result.relays.single().home)
        assertTrue(result.relays.single().usable)
        assertFalse(result.toString().contains("secret"))
    }
    @Test fun relayCredentialsDoNotProveEndpointBindingOrHomeRoute() {
        val result = NativeNetworkingSnapshot.from(control, null, 1000)
        assertEquals(NativeNetworkingSnapshot.Runtime.WAITING_FOR_MAC, result.runtime)
        assertNull(result.homeRelay)
        assertFalse(result.relays.single().home)
    }
    @Test fun expiredPermissionAndCredentialsAreNotReportedCurrent() {
        val result = NativeNetworkingSnapshot.from(control.copy(mode = "http"), IrxEndpointStatus(true, null), 2000)
        assertEquals(NativeNetworkingSnapshot.Discovery.POLLING, result.discovery)
        assertFalse(result.permissionValid)
        assertFalse(result.relays.single().usable)
        assertNull(result.homeRelay)
    }
    @Test fun stoppedEndpointDropsHomeRelayEvenWithCachedCredentials() {
        val result = NativeNetworkingSnapshot.from(control.copy(ready = false), IrxEndpointStatus(false, relay.url), 1000)
        assertEquals(NativeNetworkingSnapshot.Runtime.STOPPED, result.runtime)
        assertEquals(NativeNetworkingSnapshot.Discovery.UNAVAILABLE, result.discovery)
        assertFalse(result.permissionValid)
        assertFalse(result.relays.single().home)
        assertNull(result.homeRelay)
    }
    @Test fun doesNotExposeCredentialBearingOrUnexpectedRelayURLs() {
        for (url in listOf("http://relay.example/", "https://user:secret@relay.example/", "https://relay.example/?token=secret",
            "https://relay.example/#secret", "https://relay.example/secret", "not a URL")) {
            val result = NativeNetworkingSnapshot.from(control.copy(relays = listOf(IrohV2Relay(url, "secret", 2000, 1500))),
                IrxEndpointStatus(true, url), 1000)
            assertTrue(url, result.relays.isEmpty())
            assertNull(url, result.homeRelay)
        }
    }
    @Test fun retainsObservedHomeRelayEvenWhenNotInCredentialList() {
        val result = NativeNetworkingSnapshot.from(control, IrxEndpointStatus(true, "https://old-relay.example/"), 1000)
        assertEquals("https://old-relay.example/", result.homeRelay)
        assertFalse(result.relays.single().home)
        assertEquals("Unavailable", networkingDate(Long.MAX_VALUE))
    }
}
