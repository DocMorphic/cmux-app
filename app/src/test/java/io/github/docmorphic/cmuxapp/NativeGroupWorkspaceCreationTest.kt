package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeGroupWorkspaceCreationTest {
    @Test fun exactCreatedIdentityIsUsedEvenWhenItIsNotFirst() {
        val response = JSONObject("""{"created_workspace_id":"new","workspaces":[{"id":"old"},{"id":"new","group_id":"g"}]}""")
        assertEquals("new", createdGroupWorkspace(response)?.id)
    }
    @Test fun legacySuccessDoesNotGuessAWorkspaceOrWeakenTaskCreation() {
        val response = JSONObject("""{"workspaces":[{"id":"old"},{"id":"new"}]}""")
        assertNull(createdGroupWorkspace(response))
        assertTrue(runCatching { TaskCreationResult.parse(response) }.isFailure)
    }
    @Test fun malformedOrAmbiguousResponsesCannotSelectAnotherWorkspace() {
        listOf("{}", """{"created_workspace_id":"missing","workspaces":[{"id":"old"}]}""",
            """{"created_workspace_id":12,"workspaces":[{"id":"12"}]}""",
            """{"created_workspace_id":"","workspaces":[]}""",
            """{"workspaces":[{"id":"same"},{"id":"same"}]}""").forEach {
            assertTrue(it, runCatching { createdGroupWorkspace(JSONObject(it)) }.isFailure)
        }
    }
}
