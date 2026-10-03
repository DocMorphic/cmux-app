package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class RoutedBrowserReturnScopeTest {
    private val key = LocalBrowserKey("account", "team", "computer", "workspace")
    private val workspace = NativeWorkspace(key.workspaceId, "Workspace", emptyList(), null, false, null, null, false, emptyList(), null, null, null)
    private fun destination() = LocalBrowserDestination(key, workspace, null, LocalBrowserSurface("same-id", null))

    @Test fun replacementAndAbsentDestinationsIgnoreOldReturnsEvenWhenIdsMatch() {
        val expected = destination()
        for (replacement in listOf(null, destination(), expected.copy(key = key.copy(accountId = "other")),
            expected.copy(key = key.copy(workspaceId = "other")))) {
            assertFalse(ownsBrowserDestination(expected, replacement))
            assertEquals(RoutedBrowserReturnScope.IGNORE, routedBrowserReturnScope(expected, replacement, expected, true, workspace.id))
            assertEquals(RoutedBrowserReturnScope.IGNORE, routedBrowserReturnScope(expected, replacement, expected, false, workspace.id))
        }
    }
    @Test fun missingRetiredOrMismatchedRegistrationMayOnlyLeaveItsOwnDestination() {
        val expected = destination()
        for (registered in listOf(null, destination(), expected.copy(key = key.copy(teamId = "other"))))
            assertEquals(RoutedBrowserReturnScope.LEAVE, routedBrowserReturnScope(expected, expected, registered, true, workspace.id))
        assertEquals(RoutedBrowserReturnScope.LEAVE, routedBrowserReturnScope(expected, expected, expected, false, workspace.id))
        assertEquals(RoutedBrowserReturnScope.LEAVE, routedBrowserReturnScope(expected, expected, expected, true, "different-workspace"))
    }
    @Test fun onlyCurrentOpenPresentationCanApplyAnActionOrRestart() {
        val expected = destination()
        assertEquals(RoutedBrowserReturnScope.APPLY, routedBrowserReturnScope(expected, expected.copy(workspace = workspace.copy(title = "Updated")), expected, true, workspace.id))
        expected.surface.close()
        assertEquals(RoutedBrowserReturnScope.LEAVE, routedBrowserReturnScope(expected, expected, expected, true, workspace.id))
    }
}
