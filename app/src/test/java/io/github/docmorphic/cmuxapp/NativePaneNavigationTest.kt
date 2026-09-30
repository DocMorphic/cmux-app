package io.github.docmorphic.cmuxapp

import org.junit.Test
import org.junit.Assert.*

class NativePaneNavigationTest {
    private val mac = NativeCredentialStore.PairedMac("route-a", "Mac-A", "Mac", "build", accountUserId = "user", accountTeamId = "team")
    @Test fun sameOwnerRetainsSelectionAndDoesNotDependOnDisplayName() {
        val navigation = NativePaneNavigation()
        val state = navigation.select("login", mac, null)
        state.terminal.value = NativeTerminal("terminal", "Shell")
        assertSame(state, navigation.select("login", mac.copy(name = "Renamed"), null))
        assertEquals("terminal", navigation.select("login", mac, null).terminal.value?.id)
    }
    @Test fun loginMacBuildRouteAndAccountChangesEachRetireTheOldSelection() {
        val alternatives = listOf("new-login" to mac, "login" to mac.copy(deviceId = "other"),
            "login" to mac.copy(instanceTag = "other"), "login" to mac.copy(code = "route-b"),
            "login" to mac.copy(accountUserId = "other"), "login" to mac.copy(accountTeamId = "other"))
        alternatives.forEach { (login, changed) ->
            val navigation = NativePaneNavigation()
            val old = navigation.select("login", mac, null)
            old.terminal.value = NativeTerminal("old", "Old")
            val current = navigation.select(login, changed, null)
            assertNotSame(old, current); assertNull(current.terminal.value)
            old.terminal.value = NativeTerminal("late", "Late")
            assertNull(current.terminal.value)
        }
    }
    @Test fun losingSavedMacAdmissionOrSigningOutDropsAllState() {
        for (signedOut in listOf(false, true)) {
            val navigation = NativePaneNavigation()
            val old = navigation.select("login", mac, null)
            old.terminal.value = NativeTerminal("terminal", "Shell")
            val current = navigation.select(if (signedOut) null else "login", if (signedOut) mac else null, null)
            assertNotSame(old, current); assertNull(current.terminal.value)
            assertNotSame(old, navigation.select("login", mac, null))
        }
    }
    @Test fun matchingTeamRefreshGenerationPreservesStateButDifferentTeamDoesNot() {
        val navigation = NativePaneNavigation()
        val state = navigation.select("login", mac, null)
        val team = NativeTeamScope("login", "user", "team", 1)
        assertSame(state, navigation.select("login", mac, team))
        assertSame(state, navigation.select("login", mac, team.copy(generation = 2)))
        assertNotSame(state, navigation.select("login", mac, team.copy(teamId = "other")))
    }
    @Test fun explicitClearCannotRevivePreviousState() {
        val navigation = NativePaneNavigation()
        val state = navigation.select("login", mac, null)
        navigation.clear(); assertNotSame(state, navigation.select("login", mac, null))
    }
}
