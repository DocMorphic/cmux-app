package io.github.docmorphic.cmuxapp

import org.junit.Test
import org.junit.Assert.*

class NativeLaunchRoutesTest {
    private val code = "cmux-ios://attach?v=2&r=100.64.0.1:58465"
    private val notification = "11111111-1111-4111-8111-111111111111"
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val mac = IrohV2Computer("record", "endpoint", "device", "stable", "Mac", emptyList())
    private val computers = NativeComputersState(account = team, ready = true, computers = listOf(mac))
    private val iroh = PairingCodeParser.computer(mac, team)
    @Test fun pendingAndConsumedRoutesRoundTripWithoutReplayingLaunchData() {
        for (value in listOf(NativeLaunchRoutes(pairing = code), NativeLaunchRoutes(notification = notification), NativeLaunchRoutes()))
            assertEquals(value, NativeLaunchRoutes.decode(value.encode()))
        assertEquals(NativeLaunchRoutes(), NativeLaunchRoutes.decode(NativeLaunchRoutes(pairing = code).handledPairing(code).encode()))
        assertEquals(NativeLaunchRoutes(), NativeLaunchRoutes.decode(NativeLaunchRoutes(notification = notification).handledNotification(notification).encode()))
    }
    @Test fun lateAcknowledgementCannotClearANewerRoute() {
        val newer = NativeLaunchRoutes(pairing = code.replace(".1:", ".2:"))
        assertEquals(newer, newer.handledPairing(code))
        val next = NativeLaunchRoutes(notification = notification)
        assertEquals(next, next.handledPairing(code)); assertEquals(next, next.handledNotification("old"))
    }
    @Test fun invalidUnboundedCredentialBearingOrUnknownStateIsDiscarded() {
        for (value in listOf(null, "", "garbage", "x".repeat(17_000), "{\"version\":2}",
            NativeLaunchRoutes(pairing = "$code&token=not-a-real-token").encode(),
            NativeLaunchRoutes(notification = "not-a-route").encode())) assertEquals(NativeLaunchRoutes(), NativeLaunchRoutes.decode(value))
    }
    @Test fun onlyOneRecognizedRouteCanBePending() {
        assertEquals(NativeLaunchRoutes(notification = notification), NativeLaunchRoutes.incoming(code, notification))
        assertEquals(NativeLaunchRoutes(pairing = code), NativeLaunchRoutes.incoming(code, null))
    }
    @Test fun signedOutLinksWaitAndExternalTailscaleRequiresSeparateInAppEntry() {
        assertEquals(NativePairingLinkAction.Wait, incomingPairingAction(code, false, false, null, computers))
        assertEquals(NativePairingLinkAction.EnterInApp, incomingPairingAction(code, true, false, null, computers))
        assertEquals(NativePairingLinkAction.Consumed, incomingPairingAction(code, true, true, null, computers))
    }
    @Test fun irohWaitsForTheCurrentAccountDirectory() {
        for (directory in listOf(NativeComputersState(), computers.copy(ready = false), computers.copy(account = team.copy(generation = 2))))
            assertEquals(NativePairingLinkAction.Wait, incomingPairingAction(iroh, true, false, team, directory))
        assertEquals(NativePairingLinkAction.Wait, incomingPairingAction(iroh, true, false, null, computers))
        assertEquals(NativePairingLinkAction.Select(iroh), incomingPairingAction(iroh, true, false, team, computers))
    }
    @Test fun wrongUserTeamBuildDeviceOrAmbiguousDirectoryCannotSelect() {
        for (value in listOf(iroh.replace("ub=user", "ub=other"), iroh.replace("t=team", "t=other"),
            iroh.replace("b=stable", "b=nightly"), iroh.replace("d=device", "d=other")))
            assertEquals(NativePairingLinkAction.Unavailable, incomingPairingAction(value, true, false, team, computers))
        assertEquals(NativePairingLinkAction.Unavailable, incomingPairingAction(iroh, true, false, team, computers.copy(computers = listOf(mac, mac))))
    }
    @Test fun directoryRefreshDoesNotTurnAPendingLookupIntoMissingMac() {
        assertEquals(NativePairingLinkAction.Wait, incomingPairingAction(iroh, true, false, team, computers.copy(ready = false, computers = emptyList())))
        assertEquals(NativePairingLinkAction.Unavailable, incomingPairingAction(iroh, true, false, team, computers.copy(computers = emptyList())))
    }
}
