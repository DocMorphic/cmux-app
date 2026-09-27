package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TaskSubmissionTest {
    private fun parameters(prompt: String = "Fix this", directory: String = "/project") =
        TaskCommand.parameters(TaskCommand.Agent.CODEX, prompt, directory, UUID.randomUUID())
    private fun JSONObject.id() = getString("operation_id")
    private fun listing(id: Any = "created", workspaces: String = """[{"id":"created","title":"New task"}]""") =
        JSONObject().put("created_workspace_id", id).put("workspaces", org.json.JSONArray(workspaces))

    @Test fun unchangedAndCosmeticEditsReuseSubmittedIdentity() {
        val identity = TaskSubmissionIdentity()
        val first = identity.resolve("mac:one", parameters())
        identity.submitted("mac:one", first)
        assertEquals(first.id(), identity.resolve("mac:one", parameters()).id())
        assertEquals(first.id(), identity.resolve("mac:one", parameters("  Fix this \n", " /project ")).id())
    }

    @Test fun effectiveEditsRotateButRevertingRestoresBaseline() {
        val identity = TaskSubmissionIdentity()
        val original = identity.resolve("mac:one", parameters())
        identity.submitted("mac:one", original)
        val edited = identity.resolve("mac:one", parameters("Different task"))
        assertNotEquals(original.id(), edited.id())
        assertEquals(original.id(), identity.resolve("mac:one", parameters()).id())
        assertEquals(edited.id(), identity.resolve("mac:one", parameters("Different task")).id())
        identity.submitted("mac:one", edited)
        assertEquals(edited.id(), identity.resolve("mac:one", parameters("Different task")).id())
        assertNotEquals(original.id(), identity.resolve("mac:one", parameters()).id())
    }

    @Test fun macInstanceDirectoryCommandAndEnvironmentArePartOfIdentity() {
        val identity = TaskSubmissionIdentity()
        val original = identity.resolve("mac:one", parameters())
        for ((origin, params) in listOf(
            "mac:two" to parameters(),
            "mac:one" to parameters(directory = "/different"),
            "mac:one" to parameters().put("initial_command", "claude"),
            "mac:one" to parameters().put("initial_env", JSONObject().put("CMUX_TASK_PROMPT", "Different")),
            "mac:one" to parameters().put("workspace_group_id", "group")
        )) assertNotEquals(original.id(), identity.resolve(origin, params).id())
    }

    @Test fun environmentKeyOrderDoesNotMatterAndCallerCannotMutateBaseline() {
        val identity = TaskSubmissionIdentity()
        val input = parameters().put("initial_env", JSONObject().put("A", "1").put("B", "2"))
        val original = identity.resolve("mac", input)
        val id = original.id()
        input.put("title", "mutated input")
        original.getJSONObject("initial_env").put("A", "mutated output")
        assertEquals(id, identity.resolve("mac", parameters().put("initial_env",
            JSONObject().put("B", "2").put("A", "1"))).id())
    }

    @Test fun unicodeIsComparedExactlyWithoutCanonicalNormalization() {
        val identity = TaskSubmissionIdentity()
        assertNotEquals(identity.resolve("mac", parameters("caf\u00e9")).id(),
            identity.resolve("mac", parameters("cafe\u0301")).id())
    }

    @Test fun acceptsExactCreatedWorkspaceAndMergesWithoutDroppingOthers() {
        val existing = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"old","title":"Keep me"},
            {"id":"updated","title":"Old title"}]}"""))
        val result = TaskCreationResult.parse(listing(workspaces = """[
            {"id":"updated","title":"Changed title"},{"id":"created","title":"New task"}]"""))
        val merged = result.merge(existing)
        assertEquals(listOf("old", "updated", "created"), merged.map { it.id })
        assertEquals(listOf("Keep me", "Changed title", "New task"), merged.map { it.title })
        assertEquals("created", result.created.id)
        assertEquals(merged, result.merge(merged))
    }

    @Test fun rejectsMissingWrongTypeBlankAndUnknownCreatedIds() {
        for (value in listOf(JSONObject(), JSONObject().put("created_workspace_id", "created"),
            listing(JSONObject.NULL), listing(3), listing(" "), listing("other"), listing(workspaces = "[]"))) {
            assertThrows(IllegalArgumentException::class.java) { TaskCreationResult.parse(value) }
        }
    }

    @Test fun rejectsMalformedAndDuplicateWorkspaceIdentities() {
        for (items in listOf("[null]", "[4]", "[{\"id\":5}]", "[{\"id\":null}]",
            "[{\"id\":\"\"}]", "[{\"id\":\"created\"},{\"id\":\"created\"}]")) {
            assertThrows(IllegalArgumentException::class.java) { TaskCreationResult.parse(listing(workspaces = items)) }
        }
    }
}
