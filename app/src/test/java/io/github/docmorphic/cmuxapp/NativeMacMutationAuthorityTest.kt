package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMacMutationAuthorityTest {
    @Test fun uiProjectionEnablesOnlyFreshMacTicketsOrAccountCapabilityWithEachOperationCapability() {
        val caps = setOf("workspace.group_actions.v1", "workspace.group_create.v1", "workspace.create_in_group.v1")
        val source = NativeFeedSource(NativeCredentialStore.PairedMac("public", "mac", "Mac"), capabilities = caps)
        assertFalse(source.canEditGroups()); assertFalse(source.canCreateGroup()); assertFalse(source.canCreateInGroup())
        for (ticket in listOf(MobileAttachTicketContext("work", null, "secret", null), MobileAttachTicketContext("", null, null, null)))
            assertNull(ticket.macMutationTicket())
        val ticket = MobileAttachTicketContext("", null, "secret", 1000).macMutationTicket()
        assertNotNull(ticket); assertFalse(ticket.toString().contains("secret"))
        val legacy = source.copy(macMutationTicket = ticket)
        assertTrue(legacy.canMutateMacWorkspaces(999)); assertFalse(legacy.canMutateMacWorkspaces(1000))
        val untimed = source.copy(macMutationTicket = NativeMacMutationTicket(null))
        assertTrue(untimed.canEditGroups()); assertTrue(untimed.canCreateGroup()); assertTrue(untimed.canCreateInGroup())
        assertFalse(untimed.copy(capabilities = emptySet()).canEditGroups())
        val account = source.copy(capabilities = caps + WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY, macMutationTicket = NativeMacMutationTicket(0))
        assertTrue(account.canEditGroups()); assertTrue(account.canCreateGroup()); assertTrue(account.canCreateInGroup())
    }

    @Test fun unscopedMissingAndExpiredAuthorityCannotSendMacMutationsOrAcquireAccountToken() = runBlocking<Unit> {
        for (context in listOf(null, MobileAttachTicketContext("work", "term", "secret", null),
            MobileAttachTicketContext("", null, "secret", 0))) {
            val wire = PoolTestTransport(); var lookups = 0
            MobileRpcClient(wire, { lookups++; "stack" }, context).use { client ->
                client.connect()
                for (method in listOf("workspace.create", "workspace.move", "workspace.group.create", "workspace.group.action"))
                    assertTrue(runCatching { client.request(method) }.isFailure)
                assertEquals(0, lookups); assertTrue(wire.sent.tryReceive().isFailure)
            }
        }
    }

    @Test fun freshMacTicketAuthorizesAllMacMutationVerbsAndTravelsOnEachFrame() = runBlocking<Unit> {
        val wire = PoolTestTransport()
        MobileRpcClient(wire, { "stack" }, MobileAttachTicketContext("", null, "secret", null)).use { client ->
            client.connect()
            for (method in listOf("workspace.create", "workspace.move", "workspace.group.create", "workspace.group.action")) {
                val pending = async { client.request(method) }
                val frame = withTimeout(2000) { wire.sent.receive() }
                assertEquals("secret", frame.getJSONObject("auth").getString("attach_token"))
                assertEquals("stack", frame.getJSONObject("auth").getString("stack_access_token"))
                wire.answer(frame); withTimeout(2000) { pending.await() }
            }
        }
    }

    @Test fun ticketExpiryWhileAccountTokenLookupWaitsCannotFallBackToAccountOnlyMutation() = runBlocking<Unit> {
        val wire = PoolTestTransport(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        MobileRpcClient(wire, { entered.complete(Unit); release.await(); "stack" }).use { owner ->
            owner.connect()
            val expiry = System.currentTimeMillis() + 250
            owner.withAttachTicket(MobileAttachTicketContext("", null, "secret", expiry)) {}.use { client ->
                val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.request("workspace.create") } }
                withTimeout(1000) { entered.await() }
                delay((expiry - System.currentTimeMillis()).coerceAtLeast(0) + 5)
                release.complete(Unit)
                assertTrue(withTimeout(2000) { pending.await() }.isFailure)
                assertTrue(wire.sent.tryReceive().isFailure)
            }
        }
    }
}
