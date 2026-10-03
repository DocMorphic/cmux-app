package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeTerminalStartupTest {
    private var time = 0L
    private val startup = NativeTerminalStartup { time }
    private val key = NativeWorkspaceTabKey("account", "team", "mac/build", "workspace")
    private val starting = NativeTerminal("new", "New shell", isReady = false)
    private val ready = NativeTerminal("old", "Ready shell", isFocused = true)
    private fun workspace(vararg terminals: NativeTerminal) = parseWorkspaces(JSONObject(
        """{"workspaces":[{"id":"workspace","title":"Work","terminals":[]}]}""")).single().copy(terminals = terminals.toList())

    @Test fun returningToTheSameScreenStillInvalidatesAnOlderCreate() {
        val navigation = NativeNavigationGeneration()
        val ticket = navigation.observe(listOf("list", "account", "mac"))
        assertTrue(navigation.matches(ticket, listOf("list", "account", "mac")))
        navigation.observe(listOf("other-workspace", "account", "mac"))
        assertFalse(navigation.matches(ticket, listOf("list", "account", "mac")))
    }
    @Test fun routingDoesNotRestartTheCreateDeadline() {
        time = 10_000
        startup.begin(key, starting, startedAt = 0)
        assertEquals(20_000L, startup.remaining(startup.state.value.pending!!))
    }
    @Test fun createPinWinsOverReadySiblingUntilReadiness() {
        startup.begin(key, starting)
        assertEquals(starting, startup.reconcile(key, workspace(ready, starting), starting)?.terminal)
        assertNotNull(startup.state.value.pending)
        val started = starting.copy(isReady = true)
        assertEquals(started, startup.reconcile(key, workspace(ready, started), starting)?.terminal)
        assertNull(startup.state.value.pending)
    }
    @Test fun existingUnreadyTerminalYieldsToReadySiblingWithoutCreatePin() {
        assertEquals(ready, startup.reconcile(key, workspace(ready, starting), starting)?.terminal)
        assertEquals(starting, startup.reconcile(key, workspace(starting), starting)?.terminal)
    }
    @Test fun deadlineIsThirtySecondsAndNotExtendedBySnapshots() {
        startup.begin(key, starting)
        val ticket = startup.state.value.pending!!
        time = 29_999
        startup.reconcile(key, workspace(ready, starting), starting)
        assertFalse(startup.expire(ticket))
        assertEquals(1L, startup.remaining(ticket))
        time++
        assertTrue(startup.expire(ticket))
        assertEquals(ready, startup.reconcile(key, workspace(ready, starting), starting)?.terminal)
        assertEquals(starting.id, startup.state.value.failure?.terminalId)
    }
    @Test fun lateReadinessClearsTimeoutWithoutStealingSelection() {
        startup.begin(key, starting); val ticket = startup.state.value.pending!!
        time = 40_000; startup.expire(ticket)
        assertEquals(ready, startup.reconcile(key, workspace(ready, starting.copy(isReady = true)), ready)?.terminal)
        assertNull(startup.state.value.failure)
    }
    @Test fun confirmedDisappearanceReleasesPinWithoutReportingTimeout() {
        startup.begin(key, starting); val ticket = startup.state.value.pending!!
        assertEquals(ready, startup.reconcile(key, workspace(ready), starting)?.terminal)
        time = 40_000
        assertFalse(startup.expire(ticket)); assertNull(startup.state.value.failure)
        startup.begin(key, starting)
        assertNull(startup.reconcile(key, null, starting)); assertNull(startup.state.value.pending)
    }
    @Test fun navigationAndAccountMacBuildChangesCancelPin() {
        listOf(key.copy(accountId = "other"), key.copy(teamId = "other"),
            key.copy(computerId = "mac/nightly"), key.copy(workspaceId = "other"), null).forEach { next ->
            startup.begin(key, starting); val ticket = startup.state.value.pending!!
            startup.observe(next, starting.id)
            time += 40_000
            assertFalse(startup.expire(ticket))
        }
        startup.begin(key, starting); startup.observe(key, ready.id); assertNull(startup.state.value.pending)
    }
    @Test fun differentOwnersInventoryCannotRetireOrCompleteThePin() {
        startup.begin(key, starting)
        startup.reconcile(key.copy(computerId = "other"), workspace(ready, starting.copy(isReady = true)), starting)
        assertNotNull(startup.state.value.pending)
    }
    @Test fun explicitSelectionAndNewCreateInvalidateOldTimer() {
        startup.begin(key, starting); val old = startup.state.value.pending!!
        startup.cancelPin(); time = 40_000; assertFalse(startup.expire(old))
        startup.begin(key, starting); val newer = startup.state.value.pending!!
        time += 40_000; assertFalse(startup.expire(old)); assertTrue(startup.expire(newer))
        startup.begin(key, ready); assertEquals(NativeTerminalStartup.State(), startup.state.value)
    }
    @Test fun focusedNonTerminalReceivesFallbackAndUnknownFailuresCannotMoveIt() {
        startup.begin(key, starting); val ticket = startup.state.value.pending!!
        time = 30_000; startup.expire(ticket)
        val workspace = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"workspace","terminals":[
            {"id":"new","is_ready":false},{"id":"old","is_ready":true}],"surfaces":[
            {"surface_id":"todo","kind":"todo","is_focused":true}]}]}""")).single()
        assertEquals("todo", startup.reconcile(key, workspace, starting)?.surface?.id)
        startup.observe(key, null); assertNotNull(startup.state.value.failure)
        startup.observe(null, null); assertNull(startup.state.value.failure)
    }
}
