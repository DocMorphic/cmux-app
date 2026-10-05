package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NativeExternalTicketRoutesTest {
    private val owner = NativeTeamScope("login", "user", "team", 1)
    private val peer = "a".repeat(64)
    private val raw = PairingCode.Tailscale(listOf(PairingCode.Route("100.64.0.7", 58465)), "user")
    private val target = NativeComputerTarget("device", "default", "Mac")
    private fun ticket(native: Boolean = false) = MobileAttachTicketCodec.decodeJson("""{
        "version":1,"workspaceID":"w","macDeviceID":"device","macUserID":"user","routes":[
        {"id":"ts","kind":"tailscale","endpoint":{"type":"host_port","host":"100.64.0.7","port":58465}}
        ${if (native) ",{\"id\":\"ir\",\"kind\":\"iroh\",\"endpoint\":{\"type\":\"peer\",\"id\":\"$peer\"}}" else ""}]}
        """).getOrThrow()
    private inner class Fixture {
        var state = JSONObject().put("task_session", owner.login).put("refresh_token", "fixture")
        val grants = TailscaleGrantStore({ state }, { it(state) })
        var settings: String? = null
        val store = NativeMacConnectionStore({ settings }, { settings = it })
        var current = true
        var rows = emptyList<NativeCredentialStore.PairedMac>()
        var directory = NativeComputersState(owner, ready = true,
            computers = listOf(IrohV2Computer("record", peer, "device", "default", "Mac", emptyList())))
        val routes = NativeExternalTicketRoutes(owner, grants, { rows }, { directory }, { store.state.value }, { current })
        fun grant(build: String? = "default", pairing: PairingCode.Tailscale = raw, device: String = "device", scope: NativeTeamScope = owner): TailscaleSavedGrant =
            TailscaleSavedGrant(UUID.randomUUID().toString(), scope.userId, scope.teamId, TailscaleGrantStore.source(pairing), device, build, raw.routes.single())
                .also { grants.save(scope, it) { true } }
        fun method(value: NativeMacConnectionMethod, t: NativeComputerTarget = target) = store.update(t, { true }) { it.copy(method = value) }
    }
    @Test fun noGrantDoesNotAuthorizeAnExternalAddress() {
        val f = Fixture()
        assertTrue(f.routes.choices(ticket()).isEmpty())
        assertTrue(f.routes.choices(ticket(true)).keys.single().pairing is PairingCode.Iroh)
        f.grant(device = "other")
        assertTrue(f.routes.choices(ticket()).isEmpty())
    }
    @Test fun automaticUsesExactStoredLegacyDestinationOnlyWithoutNativeIdentity() {
        val f = Fixture(); val grant = f.grant()
        val choices = f.routes.choices(ticket())
        assertEquals(grant, choices.values.single().savedGrant)
        assertTrue(f.routes.choices(ticket(true)).keys.single().pairing is PairingCode.Iroh)
        f.rows = listOf(NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
            PairingCodeParser.computer(f.directory.computers.single(), owner), "device", "Mac", "default"), owner))
        assertTrue(f.routes.choices(ticket()).isEmpty())
        assertTrue(runCatching { choices.values.single().requireCurrent() }.isFailure)
    }
    @Test fun tailscaleOnlyKeepsItsGrantAndDirectKeepsOnlyNativeEvenInAMixedTicket() {
        val f = Fixture(); f.grant(); f.method(NativeMacConnectionMethod.TAILSCALE)
        assertTrue(f.routes.choices(ticket(true)).keys.single().pairing is PairingCode.Tailscale)
        f.method(NativeMacConnectionMethod.DIRECT)
        assertTrue(f.routes.choices(ticket()).isEmpty())
        assertTrue(f.routes.choices(ticket(true)).keys.single().pairing is PairingCode.Iroh)
    }
    @Test fun destinationGrantIsIndependentOfTicketSourceHintsButAmbiguousBuildsAreRejected() {
        val f = Fixture()
        val dns = raw.copy(routes = listOf(PairingCode.Route("mac.tail.ts.net", 58465)))
        val grant = f.grant(pairing = dns)
        assertEquals(grant, f.routes.choices(ticket()).values.single().savedGrant)
        f.grant(build = "nightly")
        assertTrue(f.routes.choices(ticket()).isEmpty())
    }
    @Test fun directoryNativePeerResolvesExactBuildForAMixedTicketWithSharedDestination() {
        val f = Fixture()
        val old = f.grant(pairing = raw.copy(stackUserId = null))
        f.grant(build = "nightly")
        f.method(NativeMacConnectionMethod.TAILSCALE)
        val choice = f.routes.choices(ticket(true)).values.single()
        assertEquals(old, choice.savedGrant)
        f.directory = f.directory.copy(computers = emptyList())
        assertTrue(runCatching { choice.requireCurrent() }.isFailure)
    }
    @Test fun methodRoundTripGrantReplacementAndAccountRetirementInvalidateCapturedConfirmation() {
        val f = Fixture(); f.grant()
        val first = f.routes.choices(ticket()).values.single()
        f.method(NativeMacConnectionMethod.DIRECT); f.method(NativeMacConnectionMethod.IROH)
        assertTrue(runCatching { first.requireCurrent() }.isFailure)
        val second = f.routes.choices(ticket()).values.single(); f.grant()
        assertTrue(runCatching { second.requireCurrent() }.isFailure)
        val third = f.routes.choices(ticket()).values.single(); f.current = false
        assertTrue(runCatching { third.requireCurrent() }.isFailure)
    }
    @Test fun siblingBuildDoesNotSupplyMethodAndSettingsErrorsDoNotDefaultToAutomatic() {
        val f = Fixture(); f.grant()
        f.method(NativeMacConnectionMethod.DIRECT, target.copy(buildTag = "nightly"))
        assertEquals(1, f.routes.choices(ticket()).size)
        f.settings = "broken"; f.store.reload()
        assertTrue(runCatching { f.routes.choices(ticket()) }.isFailure)
    }
    @Test fun nativeColdLaunchWaitsForDiscoveryAndCapturesItsMethodAtConfirmation() {
        val f = Fixture(); val ready = f.directory
        f.directory = NativeComputersState(owner, loading = true)
        val admission = f.routes.choices(ticket(true)).values.single()
        assertTrue(runCatching { admission.requireCurrent() }.isFailure)
        f.directory = ready; admission.requireCurrent()
        f.method(NativeMacConnectionMethod.TAILSCALE)
        assertTrue(runCatching { admission.requireCurrent() }.isFailure)
        f.directory = NativeComputersState(owner, loading = true)
        val late = f.routes.choices(ticket(true)).values.single()
        f.directory = ready
        assertTrue(runCatching { late.requireCurrent() }.isFailure)
    }
    @Test fun selectionRetainsAdmissionAndNeverRequestsFreshAuthorization() {
        val f = Fixture(); f.grant()
        val pairing = NativeTicketPairing()
        pairing.propose(ticket(), owner, null, NativePairingEntry.EXTERNAL_LINK, f.routes)
        val proposal = pairing.pending.value!!
        val attempt = pairing.select(proposal, proposal.choices.single(), owner, f.directory)
        assertFalse(attempt.freshTailscaleAuthorization)
        assertNotNull(attempt.admission?.savedGrant)
        f.method(NativeMacConnectionMethod.DIRECT)
        assertTrue(runCatching { attempt.admission!!.requireCurrent() }.isFailure)
    }
    @Test fun proposalCannotBeConfirmedAfterGrantRemovalOrNativeDirectoryReplacement() {
        val f = Fixture(); val grant = f.grant()
        val pairing = NativeTicketPairing()
        pairing.propose(ticket(), owner, null, NativePairingEntry.EXTERNAL_LINK, f.routes)
        val p = pairing.pending.value!!
        f.grants.removeRoute(owner, target, grant) { true }
        assertTrue(runCatching { pairing.select(p, p.choices.single(), owner, f.directory) }.isFailure)
        val native = f.routes.choices(ticket(true)).values.single()
        f.directory = f.directory.copy(computers = emptyList())
        assertTrue(runCatching { native.requireCurrent() }.isFailure)
    }
}
