package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeNotificationVisibilityTest {
    private val selection = NativeNotificationSelection("login", "mac-stable", "workspace", "terminal")
    private val item = NativeNotification("n", "workspace", "terminal", "Title", "Body", false)

    @Test fun onlyTheDisplayedTerminalOrWorkspaceIsQuiet() {
        val state = NativeNotificationVisibility(); state.update(this, selection)
        assertTrue(state.suppresses("login", setOf("mac-stable"), item))
        assertTrue(state.suppresses("login", setOf("mac-stable"), item.copy(surfaceId = null)))
        assertFalse(state.suppresses("login", setOf("mac-stable"), item.copy(surfaceId = "other")))
        assertFalse(state.suppresses("login", setOf("mac-stable"), item.copy(workspaceId = "other")))
        assertFalse(state.suppresses("login", setOf("mac-stable"), item.copy(workspaceId = "")))
        // Do not guess that a moved surface belongs to the displayed workspace.
        assertFalse(state.suppresses("login", setOf("mac-stable"), item.copy(workspaceId = "old", retargetsToLiveSurfaceOwner = true)))
    }

    @Test fun otherAccountsMacsAndBuildsStayVisible() {
        val state = NativeNotificationVisibility(); state.update(this, selection)
        assertFalse(state.suppresses("replacement", setOf("mac-stable"), item))
        assertFalse(state.suppresses(null, setOf("mac-stable"), item))
        assertFalse(state.suppresses("", setOf("mac-stable"), item))
        assertFalse(state.suppresses("login", setOf("other-mac"), item))
        assertFalse(state.suppresses("login", setOf("mac-nightly"), item))
        assertTrue(state.suppresses("login", setOf("new-authorized-route", "mac-stable"), item))
    }

    @Test fun browserDoesNotPretendThePreviousTerminalIsVisible() {
        val state = NativeNotificationVisibility(); state.update(this, selection.copy(terminal = null))
        assertFalse(state.suppresses("login", setOf("mac-stable"), item))
        assertTrue(state.suppresses("login", setOf("mac-stable"), item.copy(surfaceId = null)))
        state.update(this, null) // Settings, list, sign-out or navigation transition.
        assertFalse(state.suppresses("login", setOf("mac-stable"), item.copy(surfaceId = null)))
    }

    @Test fun removingOneWindowDoesNotClearAnotherAndProcessRestartIsEmpty() {
        val state = NativeNotificationVisibility(); val other = Any()
        state.update(this, selection); state.update(other, selection.copy(terminal = "other"))
        state.update(this, null)
        assertFalse(state.suppresses("login", setOf("mac-stable"), item))
        assertTrue(state.suppresses("login", setOf("mac-stable"), item.copy(surfaceId = "other")))
        assertFalse(NativeNotificationVisibility().suppresses("login", setOf("mac-stable"), item.copy(surfaceId = "other")))
    }
}
