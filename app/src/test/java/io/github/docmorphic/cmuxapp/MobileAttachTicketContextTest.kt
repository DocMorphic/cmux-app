package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobileAttachTicketContextTest {
    private fun ticket(workspace: String = "work", terminal: String? = "term", expiry: Long? = 100) =
        MobileAttachTicketContext(workspace, terminal, "  fixture-secret  ", expiry)
    private fun params(workspace: String? = null, terminal: String? = null) = JSONObject()
        .put("workspace_id", workspace).put("surface_id", terminal)

    @Test fun terminalPinAcceptsSameSurfaceWithNoWorkspaceButRejectsConflictingWorkspace() {
        val ticket = ticket(" work ", " term ")
        assertEquals("fixture-secret", ticket.tokenFor("mobile.terminal.input", params(terminal = " term "), 99))
        assertEquals("fixture-secret", ticket.tokenFor("mobile.terminal.replay", params(" work ", "term"), 99))
        assertNull(ticket.tokenFor("mobile.terminal.input", params("other", "term"), 99))
        assertNull(ticket.tokenFor("mobile.terminal.input", params("work", "other"), 99))
        assertNull(ticket.tokenFor("mobile.terminal.input", params("work"), 99))
    }

    @Test fun workspaceTicketCoversItsTerminalsAndMacWideTicketCoversAnySelection() {
        val scoped = ticket(terminal = null)
        assertEquals("fixture-secret", scoped.tokenFor("mobile.terminal.input", params("work", "any"), 1))
        assertNull(scoped.tokenFor("mobile.terminal.input", params(terminal = "any"), 1))
        assertNull(scoped.tokenFor("workspace.close", params("other"), 1))
        assertEquals("fixture-secret", ticket(" \n ").tokenFor("mobile.terminal.input", params("other", "any"), 1))
    }

    @Test fun terminalAliasesMustAgreeAndIgnoredAliasesPreventTicketUseEvenWhenNull() {
        val context = ticket()
        val both = params("work", "term").put("terminal_id", " term ").put("tab_id", " ")
        assertEquals("fixture-secret", context.tokenFor("terminal.input", both, 1))
        assertNull(context.tokenFor("terminal.input", both.put("tab_id", "other"), 1))
        assertNull(context.tokenFor("workspace.list", JSONObject().put("workspaceID", JSONObject.NULL), 1))
        assertNull(context.tokenFor("workspace.list", JSONObject().put("terminalID", "term"), 1))
    }

    @Test fun expiryIsInclusiveAndUntimedTicketDoesNotExpire() {
        val request = params("work", "term")
        val context = ticket()
        assertFalse(context.isExpired(99)); assertTrue(context.isExpired(100))
        assertNull(context.tokenFor("terminal.input", request, 100))
        assertNull(context.tokenFor("terminal.input", request, 101))
        assertEquals("fixture-secret", ticket(expiry = null).tokenFor("terminal.input", request, Long.MAX_VALUE))
        assertNull(MobileAttachTicketContext("", null, null, null).tokenFor("workspace.list", JSONObject(), 0))
    }

    @Test fun accountWideFeedsAndUnknownVerbsDoNotCarryEvenMacWideTicket() {
        val context = ticket("", null)
        for (method in listOf("mobile.host.status", "notification.feed.list", "notification.feed.mark_all_read",
            "feed.list", "feed.text", "feed.permission.reply", "feed.question.reply", "feed.exit_plan.reply", "future.operation")) {
            assertNull(method, context.tokenFor(method, JSONObject().put("request_id", "request"), 0))
        }
    }

    @Test fun browserPanelOnlyVerbsNeedMacWideContextWhileListUsesWorkspace() {
        val scoped = ticket()
        assertEquals("fixture-secret", scoped.tokenFor("mobile.browser.list", params("work"), 0))
        assertNull(scoped.tokenFor("mobile.browser.navigate", JSONObject().put("panel_id", "panel"), 0))
        assertEquals("fixture-secret", ticket("", null).tokenFor("mobile.browser.navigate", JSONObject().put("panel_id", "panel"), 0))
    }

    @Test fun macMutationsRetainNarrowingTokenButOnlyCurrentMacWideTicketAdmitsLegacyUi() {
        // Sending scope context lets the host reject an accidentally attempted Mac-wide mutation.
        val scoped = ticket()
        assertEquals("fixture-secret", scoped.tokenFor("workspace.group.create", JSONObject(), 0))
        assertFalse(scoped.allowsMacWorkspaceMutations(false, 0))
        assertTrue(scoped.allowsMacWorkspaceMutations(true, 100))
        assertTrue(ticket("", null).allowsMacWorkspaceMutations(false, 99))
        assertFalse(ticket("", null).allowsMacWorkspaceMutations(false, 100))
        assertFalse(MobileAttachTicketContext("", null, null, null).allowsMacWorkspaceMutations(false, 0))
    }

    @Test fun currentTerminalManagementVerbsUseTerminalCoverage() {
        for (method in listOf("mobile.terminal.reattach", "mobile.terminal.size_policy.set", "mobile.terminal.participant.disconnect")) {
            assertEquals(method, "fixture-secret", ticket().tokenFor(method, params("work", "term"), 0))
            assertNull(method, ticket().tokenFor(method, params("work", "other"), 0))
        }
    }

    @Test fun tokenIsRedactedAndInvalidOrOversizedStateCannotBeConstructed() {
        assertEquals("MobileAttachTicketContext(redacted)", ticket().toString())
        for (token in listOf("", " \n", "s".repeat(4097)))
            assertTrue(runCatching { MobileAttachTicketContext("", null, token, null) }.isFailure)
        assertTrue(runCatching { ticket("w".repeat(1025)) }.isFailure)
        assertTrue(runCatching { ticket(terminal = "t".repeat(1025)) }.isFailure)
    }
}
