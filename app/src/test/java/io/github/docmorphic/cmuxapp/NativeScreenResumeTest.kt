package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class NativeScreenResumeTest {
    private val mac = NativeCredentialStore.PairedMac("route", "device", "Mac", "build", "user", "team")
    private val key = workspaceTabKey("login", null, mac, "workspace")!!
    private val terminal = NativeWorkspaceTab(NativeWorkspaceTabKind.TERMINAL, "terminal")
    private fun checkpoint(tab: NativeWorkspaceTab? = terminal) = NativeScreenCheckpoint("login", key, tab, savedAt = 1000, bootCount = 7)
    private fun workspace(ready: Boolean = false) = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"workspace","terminals":[{"id":"terminal","title":"Shell","is_ready":$ready},{"id":"other","title":"Ready"}]}]}""")).single()
    @Test fun everyPaneKindEmptyWorkspaceAndChangesRoundTrip() {
        val values = NativeWorkspaceTabKind.entries.map { checkpoint(if (it == NativeWorkspaceTabKind.LOCAL_BROWSER) NativeWorkspaceTab.LocalBrowser else NativeWorkspaceTab(it, "pane")) } +
            listOf(checkpoint(null), checkpoint(null).copy(changes = true))
        values.forEach { assertEquals(it, NativeScreenCheckpoint.decode(it.encode())) }
    }
    @Test fun malformedAndUnknownSavedStateIsDiscarded() {
        val value = checkpoint().encode()
        val invalid = listOf("", "[1]", "x".repeat(33_000), JSONObject(value).put("version", 2).toString(),
            JSONObject(value).put("kind", "future-kind").toString(), JSONObject(value).put("workspace", "").toString(),
            JSONObject(value).put("changes", true).toString(), JSONObject(value).put("tab", JSONObject.NULL).toString(),
            JSONObject(value).put("saved_at", -1).toString(), JSONObject(value).put("saved_at", 1.5).toString(),
            JSONObject(value).put("saved_at", "1000").toString())
        invalid.forEach { assertNull(NativeScreenCheckpoint.decode(it)) }
    }
    @Test fun ownerComparisonRejectsAnotherLoginTeamMacOrBuild() {
        val value = checkpoint()
        assertTrue(value.matches("login", null, mac.copy(name = "Renamed")))
        assertFalse(value.matches("other", null, mac))
        assertFalse(value.matches("login", NativeTeamScope("login", "user", "other", 1), mac))
        assertFalse(value.matches("login", null, mac.copy(deviceId = "other")))
        assertFalse(value.matches("login", null, mac.copy(instanceTag = "other")))
    }
    @Test fun pendingRestoreSurvivesEmptyCompositionsUntilCompletedOrCancelled() {
        val value = checkpoint()
        val resume = NativeScreenResume(value)
        resume.observe(null); assertEquals(value, NativeScreenCheckpoint.decode(resume.save()))
        resume.complete(); resume.observe(checkpoint(null)); assertNull(resume.pending)
        assertEquals(checkpoint(null), NativeScreenCheckpoint.decode(resume.save()))
        resume.cancel(); assertEquals("", resume.save())
    }
    @Test fun legacyMacIdentityCanWaitForAccountAdmissionWithoutInventingAnOwner() {
        val value = checkpoint()
        val legacy = mac.copy(accountUserId = null, accountTeamId = null)
        assertTrue(value.matchesSavedIdentity("login", legacy))
        assertFalse(value.matches("login", null, legacy))
        assertTrue(value.matches("login", NativeTeamScope("login", "user", "team", 1), legacy))
        assertFalse(value.matches("login", NativeTeamScope("login", "user", "other", 1), legacy))
    }
    @Test fun cachedConflictingOwnerOrDifferentBuildCannotEnterPendingAdmission() {
        val value = checkpoint()
        assertFalse(value.matchesSavedIdentity("login", mac.copy(accountUserId = "other")))
        assertFalse(value.matchesSavedIdentity("login", mac.copy(accountTeamId = "other")))
        assertFalse(value.matchesSavedIdentity("login", mac.copy(instanceTag = "other")))
        assertFalse(value.matchesSavedIdentity("other-login", mac))
    }
    @Test fun restoredStartupKeepsItsExactTicketAndDeadline() {
        val ticket = NativeTerminalStartup.Pending(key, "terminal", 31_000, "ticket")
        val value = checkpoint().copy(startup = ticket)
        assertEquals(value, NativeScreenCheckpoint.decode(value.encode()))
        val startup = NativeTerminalStartup { 5000 }
        startup.restore(value, workspace(), 7)
        assertEquals(ticket, startup.state.value.pending); assertEquals(26_000, startup.remaining(ticket))
        assertEquals("terminal", startup.reconcile(key, workspace(), workspace().terminals.first())?.terminal?.id)
    }
    @Test fun elapsedDeadlineOrClockResetBecomesFailureInsteadOfAnotherThirtySeconds() {
        val value = checkpoint().copy(startup = NativeTerminalStartup.Pending(key, "terminal", 31_000, "ticket"))
        for (now in listOf(32_000L, 500L)) {
            val startup = NativeTerminalStartup { now }; startup.restore(value, workspace(), 7)
            assertNull(startup.state.value.pending); assertEquals("terminal", startup.state.value.failure?.terminalId)
        }
    }
    @Test fun differentOrUnknownBootCannotReviveAPinEvenWhenElapsedClocksOverlap() {
        val value = checkpoint().copy(startup = NativeTerminalStartup.Pending(key, "terminal", 31_000, "ticket"))
        for (boot in listOf(8, null)) {
            val startup = NativeTerminalStartup { 5000 }
            startup.restore(value, workspace(), boot)
            assertNull(startup.state.value.pending); assertEquals("terminal", startup.state.value.failure?.terminalId)
        }
        val startup = NativeTerminalStartup { 5000 }
        startup.restore(value.copy(bootCount = null), workspace(), 7)
        assertNull(startup.state.value.pending)
    }
    @Test fun readyOrMissingTerminalRetiresSavedPinAndReadyClearsFailure() {
        val value = checkpoint().copy(startup = NativeTerminalStartup.Pending(key, "terminal", 31_000, "ticket"))
        val startup = NativeTerminalStartup { 5000 }
        startup.restore(value, workspace(ready = true), 7); assertNull(startup.state.value.pending)
        startup.restore(value, workspace().copy(terminals = emptyList()), 7); assertNull(startup.state.value.pending)
        startup.restore(checkpoint().copy(failure = NativeTerminalStartup.Failure(key, "terminal")), workspace(ready = true), 7)
        assertNull(startup.state.value.failure)
    }
}
