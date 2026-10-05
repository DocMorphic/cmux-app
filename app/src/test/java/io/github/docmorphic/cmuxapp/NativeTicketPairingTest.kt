package io.github.docmorphic.cmuxapp

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeTicketPairingTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val peer = "a".repeat(64)
    private fun payload(device: String = "device") = """{"version":1,"workspaceID":"w","terminalID":"t","macDeviceID":"$device",
        "macUserID":"user","auth_token":"synthetic-bearer","routes":[
        {"id":"ts","kind":"tailscale","endpoint":{"type":"host_port","host":"100.64.0.7","port":58465}},
        {"id":"ir","kind":"iroh","endpoint":{"type":"peer","id":"$peer"}}]}"""
    private fun ticket(device: String = "device") = MobileAttachTicketCodec.decodeJson(payload(device)).getOrThrow()
    private fun computers(device: String = "device", owner: NativeTeamScope = team) = NativeComputersState(owner, ready = true,
        computers = listOf(IrohV2Computer("record", peer, device, "default", "Mac", emptyList())))

    @Test fun choicesContainOnlyPublicAddressesAndIdentity() {
        val ticket = ticket(); val choices = NativeTicketPairingRoutes.choices(ticket)
        assertEquals(2, choices.size)
        assertTrue(choices.all { PairingCodeParser.parse(it.code).isSuccess })
        assertTrue(choices.all { NativeTicketPairingRoutes.covers(ticket, it.pairing) })
        assertFalse(choices.toString().contains("synthetic-bearer"))
        assertFalse(choices.any { it.code.contains("payload=") || it.code.contains("auth") })
        assertFalse(NativeTicketPairingRoutes.covers(ticket, PairingCode.Tailscale(listOf(PairingCode.Route("100.64.0.8", 58465)), "user")))
    }

    @Test fun rawChoicesSortByPriorityThenIdWithoutChangingTheTicket() {
        val original = ticket()
        val tailscale = original.routes.single { it.kind == "tailscale" }
        val native = original.routes.single { it.kind == "iroh" }
        for ((routes, first) in listOf(
            listOf(tailscale.copy(priority = 4), native.copy(priority = -3)) to PairingCode.Iroh::class.java,
            listOf(native.copy(priority = 5), tailscale.copy(priority = -1)) to PairingCode.Tailscale::class.java,
            listOf(tailscale, native) to PairingCode.Iroh::class.java
        )) {
            val value = original.constrainingRoutes(routes, "Fixture")
            assertEquals(first, NativeTicketPairingRoutes.choices(value).first().pairing.javaClass)
            assertEquals(routes, value.routes)
        }
    }

    @Test fun unsupportedAndDuplicateRoutesDoNotDisplaceThePreferredUsableChoice() {
        val original = ticket()
        val native = original.routes.single { it.kind == "iroh" }
        val tailscale = original.routes.single { it.kind == "tailscale" }
        val value = original.constrainingRoutes(listOf(
            native.copy(id = "later", priority = 50), tailscale.copy(priority = 1),
            tailscale.copy(kind = "debug_loopback", priority = -20),
            MobileAttachRoute("web", "websocket", -30, MobileAttachEndpoint.Url("wss://fixture.invalid")),
            native.copy(id = "preferred", priority = -2)
        ), "Fixture")
        val choices = NativeTicketPairingRoutes.choices(value)
        assertEquals(2, choices.size)
        assertTrue(choices.first().pairing is PairingCode.Iroh)
        assertTrue(choices.last().pairing is PairingCode.Tailscale)
        assertTrue(choices.all { NativeTicketPairingRoutes.covers(value, it.pairing) })
    }

    @Test fun selectingTailscaleRetainsScopeAndRequiresCurrentProposalOwner() {
        val session = NativeTicketPairing(); session.propose(ticket(), team, null, NativePairingEntry.IN_APP)
        val proposal = session.pending.value!!
        val choice = proposal.choices.single { it.pairing is PairingCode.Tailscale }
        assertTrue(runCatching { session.select(proposal, choice, team.copy(teamId = "other"), computers()) }.isFailure)
        val selected = session.select(proposal, choice, team, computers())
        assertEquals("w", selected.ticket.context().workspaceId)
        assertEquals("t", selected.ticket.context().terminalId)
        assertNull(session.pending.value); assertSame(selected, session.current(selected.code, team))
        assertNull(session.current(selected.code, team.copy(generation = 2)))
        assertFalse(selected.toString().contains("synthetic-bearer"))
        session.completed(selected); assertNull(session.current(selected.code, team))
    }

    @Test fun entrySourceFiltersBeforeChoosingTheDefaultAndNeverAuthorizesBySorting() {
        val ticket = ticket()
        val session = NativeTicketPairing()
        session.propose(ticket, team, null, NativePairingEntry.IN_APP)
        assertTrue(session.pending.value!!.choices.all { it.pairing is PairingCode.Tailscale })
        assertNull(session.resumeCode())
        session.propose(ticket, team, null, NativePairingEntry.EXTERNAL_LINK)
        assertTrue(session.pending.value!!.choices.all { it.pairing is PairingCode.Iroh })
        val nativeOnly = session.pending.value!!
        val rawTailscale = NativeTicketPairingRoutes.choices(ticket).single { it.pairing is PairingCode.Tailscale }
        assertTrue(runCatching { session.select(nativeOnly, rawTailscale, team, computers()) }.isFailure)
        assertNull(session.resumeCode())
    }

    @Test fun externalTailscaleTicketDoesNotCreateAConfirmableProposal() {
        val value = ticket().let { it.constrainingRoutes(it.routes.filter { it.kind == "tailscale" }, "Mac") }
        val session = NativeTicketPairing()
        val failure = runCatching { session.propose(value, team, null, NativePairingEntry.EXTERNAL_LINK) }.exceptionOrNull()
        assertEquals(NativePairingEntryPolicy.ENTER_IN_APP, failure?.message)
        assertNull(session.pending.value); assertNull(session.resumeCode())
    }

    @Test fun inAppNumericRoutesKeepPriorityOrderWithoutNativeFallback() {
        val original = ticket()
        val first = MobileAttachRoute("first", "tailscale", 3, MobileAttachEndpoint.HostPort("100.64.0.9", 58465))
        val last = MobileAttachRoute("last", "tailscale", 8, MobileAttachEndpoint.HostPort("100.64.0.8", 58465))
        val routes = listOf(last) + original.routes.filter { it.kind == "iroh" }.map { it.copy(priority = -100) } + first
        val value = original.constrainingRoutes(routes, "Mac")
        val session = NativeTicketPairing(); session.propose(value, team, null, NativePairingEntry.IN_APP)
        assertEquals(listOf("100.64.0.9", "100.64.0.8"), session.pending.value!!.choices.map {
            (it.pairing as PairingCode.Tailscale).routes.single().host
        })
        assertEquals(routes, value.routes)
        assertNull(session.resumeCode())
    }

    @Test fun mixedLoopbackTicketIsRejectedRatherThanSilentlyFilteringTheLocalRoute() {
        val original = ticket()
        for (host in listOf("127.1", "0x7f.0.0.1", "2130706433", "[::ffff:127.0.0.1]", "::", "app.localhost.")) {
            val local = MobileAttachRoute("local", "tailscale", 5, MobileAttachEndpoint.HostPort(host, 58465))
            val value = original.constrainingRoutes(original.routes + local, "Mac")
            for (entry in NativePairingEntry.entries) {
                val session = NativeTicketPairing()
                assertTrue(host, runCatching { session.propose(value, team, null, entry) }.isFailure)
                assertNull(session.pending.value)
            }
        }
    }

    @Test fun hostnamesDoNotBecomeExactPairingAuthorityAndNativeRemainsAvailable() {
        val original = ticket()
        val dns = MobileAttachRoute("dns", "tailscale", -20, MobileAttachEndpoint.HostPort("mac.tailnet.ts.net", 58465))
        val value = original.constrainingRoutes(listOf(dns) + original.routes.filter { it.kind == "iroh" }, "Mac")
        val session = NativeTicketPairing(); session.propose(value, team, null, NativePairingEntry.IN_APP)
        assertTrue(session.pending.value!!.choices.single().pairing is PairingCode.Iroh)
        assertFalse(NativePairingEntryPolicy.isExactTailscale(PairingCode.Tailscale(listOf(PairingCode.Route("mac.tailnet.ts.net", 58465)), "user")))
    }

    @Test fun nativeSelectionUsesFreshDirectoryIdentityAndBuild() {
        val session = NativeTicketPairing(); session.propose(ticket(), team, null)
        val proposal = session.pending.value!!; val choice = proposal.choices.single { it.pairing is PairingCode.Iroh }
        for (directory in listOf(NativeComputersState(), computers("different"), computers(owner = team.copy(teamId = "other")),
            computers().copy(computers = computers().computers + computers().computers)))
            assertTrue(runCatching { session.select(proposal, choice, team, directory) }.isFailure)
        val result = session.select(proposal, choice, team, computers())
        val route = PairingCodeParser.parse(result.code).getOrThrow() as PairingCode.Iroh
        assertEquals(team.teamId, route.teamId); assertEquals("default", route.buildTag)
    }

    @Test fun opaqueDeviceIdsRemainCaseSensitiveButUuidAliasesMatch() {
        val session = NativeTicketPairing(); session.propose(ticket("DEVICE"), team, null)
        val p = session.pending.value!!
        assertTrue(runCatching { session.select(p, p.choices.single { it.pairing is PairingCode.Iroh }, team, computers("device")) }.isFailure)
        val uuid = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"
        session.propose(ticket(uuid), team, null)
        val q = session.pending.value!!
        assertNotNull(session.select(q, q.choices.single { it.pairing is PairingCode.Iroh }, team, computers(uuid.lowercase())))
    }

    @Test fun replacingProposalAndAccountChangesRetireCapturedActions() {
        val session = NativeTicketPairing(); session.propose(ticket(), team, null)
        val first = session.pending.value!!
        session.propose(ticket(), team, null)
        assertTrue(runCatching { session.select(first, first.choices.first(), team, computers()) }.isFailure)
        val current = session.pending.value!!
        val attempt = session.select(current, current.choices.first(), team, computers())
        assertEquals(attempt.code, session.resumeCode())
        session.reconcile(team.copy(login = "new"))
        assertFalse(session.isCurrent(attempt))
        assertTrue(session.requiresTicket(attempt.code))
        assertNull(session.current(attempt.code, team))
        assertEquals(attempt.code, session.resumeCode())
        session.propose(ticket(), team, null); session.dismiss(); assertNull(session.pending.value)
        session.clear(); assertFalse(session.requiresTicket(attempt.code)); assertNull(session.resumeCode())
    }

    @Test fun rejectsWrongAccountAndUnsupportedRemoteRoutes() {
        val session = NativeTicketPairing()
        assertTrue(runCatching { session.propose(ticket(), team.copy(userId = "other"), null) }.isFailure)
        val email = MobileAttachTicketCodec.decodeJson(JSONObject(payload()).put("macUserEmail", "owner@example.invalid").toString()).getOrThrow()
        assertTrue(runCatching { session.propose(email, team, null) }.isFailure)
        session.propose(email, team, "OWNER@example.invalid")
        val unsupported = MobileAttachTicketCodec.decodeJson("""{"version":1,"workspaceID":"","macDeviceID":"device",
            "routes":[{"id":"local","kind":"debug_loopback","endpoint":{"type":"host_port","host":"127.0.0.1","port":58465}}]}""").getOrThrow()
        assertTrue(runCatching { session.propose(unsupported, team, null) }.isFailure)
    }

    @Test fun legacyLaunchWaitsInMemoryButCannotEnterSavedStateOrDiagnostics() {
        val url = "cmux-ios://attach?v=1&payload=" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload().toByteArray())
        val routes = NativeLaunchRoutes.incoming(url, null)
        assertEquals(url, routes.pairing)
        assertFalse(routes.encode().contains("payload")); assertFalse(routes.toString().contains(url))
        assertEquals(NativeLaunchRoutes(), NativeLaunchRoutes.decode(routes.encode()))
        val forged = JSONObject().put("version", 1).put("pairing", url).toString()
        assertEquals(NativeLaunchRoutes(), NativeLaunchRoutes.decode(forged))
    }

    @Test fun publicParserCannotSmugglePayloadsEncodedCredentialKeysOrAuthorityFields() {
        val valid = "cmux-ios://attach?v=2&r=100.64.0.7:58465"
        for (value in listOf("$valid&payload=encoded-secret", "$valid&%61uth=secret",
            valid.replace("://attach", "://user:secret@attach"), "$valid#secret")) {
            assertTrue(PairingCodeParser.parse(value).isFailure)
            assertFalse(NativeLaunchRoutes(pairing = value).encode().contains("secret"))
        }
    }
}
