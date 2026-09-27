package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TaskTemplatesTest {
    private fun custom(name: String = "Custom", command: String = "") = TaskTemplate(UUID.randomUUID().toString(), name, "👩🏽‍💻", command)

    @Test fun openDirectoriesPreferSelectedWorkspaceThenActivityAndFocusedTerminal() {
        val workspaces = parseWorkspaces(JSONObject("""{"workspaces":[
            {"id":"old","last_activity_at":1,"current_directory":"/old","terminals":[]},
            {"id":"active","last_activity_at":10,"current_directory":"/workspace","terminals":[
                {"id":"one","current_directory":"/other"},
                {"id":"focused","current_directory":"/focused","is_focused":true}]},
            {"id":"new-empty","last_activity_at":20,"terminals":[{"id":"empty","current_directory":"  "}]}]}"""))
        assertEquals(listOf("/focused", "/workspace", "/other", "/old"), preferredTaskDirectories(workspaces, null))
        assertEquals("/old", preferredTaskDirectories(workspaces, "old").first())
        assertEquals("/focused", workspaces[1].terminals[1].directory)
        assertTrue(workspaces[1].terminals[1].isFocused)
        assertNull(workspaces[2].terminals.first().directory)
    }

    @Test fun builtInIdentitySurvivesEditableFieldsAndCannotBeDeletedOrForged() {
        val templates = TaskTemplates()
        val original = templates.state.value.entries.first()
        templates.apply(TaskTemplateChange.Save(original.copy(name = "Renamed", command = "printf custom", builtInKind = null), false))
        val edited = templates.state.value.entries.first()
        assertEquals(original.builtInKind, edited.builtInKind)
        assertEquals("Renamed", edited.name)
        assertThrows(IllegalArgumentException::class.java) { templates.apply(TaskTemplateChange.Delete(edited.id)) }
        val clone = custom().copy(builtInKind = TaskCommand.Agent.CLAUDE)
        templates.apply(TaskTemplateChange.Save(clone, true))
        assertNull(templates.state.value.entries.last().builtInKind)
        templates.apply(TaskTemplateChange.Delete(clone.id))
        assertEquals(4, templates.state.value.entries.size)
    }

    @Test fun rawScriptsEmojiAndDefaultDirectoryPersistWithoutShellInterpolation() {
        val script = "  printf '%s\\n' \"\$CMUX_TASK_PROMPT\"\nprintf 'done'\n"
        val templates = TaskTemplates()
        val template = custom("  Review 中 ", script).copy(defaultDirectory = "  /tmp/my project  ")
        templates.apply(TaskTemplateChange.Save(template, true))
        val restored = TaskTemplates(templates.state.value.json()).state.value.selected(template.id)
        assertEquals("Review 中", restored.name)
        assertEquals("👩🏽‍💻", restored.icon)
        assertEquals(script, restored.command)
        assertEquals("/tmp/my project", restored.defaultDirectory)
        val params = TaskCommand.parameters(restored.command, "\nUser 'quotes' and $(data) 中\n", restored.defaultDirectory, UUID.randomUUID())
        assertEquals(script, params.getString("initial_command"))
        assertEquals("User 'quotes' and $(data) 中", params.getJSONObject("initial_env").getString("CMUX_TASK_PROMPT"))
    }

    @Test fun successfulDefaultsAreScopedBoundedAndExactUnicodePathsRemainDistinct() {
        val templates = TaskTemplates()
        val template = custom()
        templates.apply(TaskTemplateChange.Save(template, true))
        templates.recordSuccess(template.id, "mac-one", "/café", 1)
        templates.recordSuccess(template.id, "mac-one", "/cafe\u0301", 2)
        templates.recordSuccess(template.id, "mac-one", "/café", 3)
        assertEquals(listOf("/café", "/cafe\u0301"), templates.state.value.recent.getValue("mac-one").map { it.path })
        assertEquals(2L, templates.state.value.recent.getValue("mac-one").first().useCount)
        repeat(22) { templates.recordSuccess(template.id, "mac-two", "/$it", it.toLong()) }
        val restored = TaskTemplates(templates.state.value.json()).state.value
        assertEquals("mac-two", restored.lastOrigin)
        assertEquals(template.id, restored.selected().id)
        assertEquals(20, restored.recent.getValue("mac-two").size)
        assertEquals("/21", restored.recent.getValue("mac-two").first().path)
        assertEquals(2, restored.recent.getValue("mac-one").size)
        templates.apply(TaskTemplateChange.Delete(template.id))
        assertNull(templates.state.value.lastTemplateId)
        assertEquals(TaskCommand.Agent.CLAUDE, templates.state.value.selected().builtInKind)
    }

    @Test fun suggestedDirectoryUsesTemplateThenOpenThenLastThenHomeAndPreservesTypedPath() {
        val templates = TaskTemplates()
        val template = templates.state.value.entries.first()
        assertEquals("~", templates.state.value.suggestedDirectory(template, "mac", null))
        templates.recordSuccess(template.id, "mac", "/last")
        assertEquals("/last", templates.state.value.suggestedDirectory(template, "mac", null))
        assertEquals("/open", templates.state.value.suggestedDirectory(template, "mac", " /open "))
        assertEquals("/template", templates.state.value.suggestedDirectory(template.copy(defaultDirectory = "/template"), "mac", "/open"))
        val draft = TaskDraft(UUID.randomUUID().toString(), "mac", "Mac", 1, directory = " /typed ", didEditDirectory = true)
        assertEquals(" /typed ", draft.selecting(template, "/template").directory)
        assertEquals("/template", draft.copy(didEditDirectory = false).selecting(template, "/template").directory)
    }

    @Test fun templateCommandsDriveProviderDiscoveryAndChangingProviderResetsSelections() {
        val explicit = TaskModel("model", "Model")
        val template = custom(command = "codex --full-auto -- \"\$CMUX_TASK_PROMPT\"")
        val draft = TaskDraft(UUID.randomUUID().toString(), "mac", "Mac", 1,
            selection = TaskModelSelection(explicit, "high"))
        val selected = draft.selecting(template, "/repo")
        assertNull(selected.selection.explicit)
        assertEquals(TaskAgentCommand.CODEX, TaskAgentCommand.detect(selected.command!!))
        val params = TaskCommand.parameters(selected.command, "Work", "/repo", UUID.randomUUID(), "codex-model", "low")
        assertTrue(params.getString("initial_command").contains("--full-auto"))
        assertTrue(params.getString("initial_command").contains("'codex-model'"))
        assertTrue(params.getString("initial_command").contains("model_reasoning_effort='low'"))
        val restored = TaskDraft.read(selected.copy(prompt = "Saved", didEditDirectory = true).json())
        assertEquals(template.id, restored.templateId)
        assertEquals(template.command, restored.command)
        assertTrue(restored.didEditDirectory)
    }

    @Test fun plainShellAndBlankNormalizationFollowEditorRules() {
        val value = custom("  Shell custom  ", "  \n\t").copy(defaultDirectory = " ").normalized()
        assertEquals("", value.command)
        assertNull(value.defaultDirectory)
        val params = TaskCommand.parameters(value.command, "", null, UUID.randomUUID())
        assertFalse(params.has("initial_command")); assertFalse(params.has("initial_env"))
        assertThrows(IllegalArgumentException::class.java) { custom(" ").normalized() }
    }

    @Test fun absentShippedRowsAreRestoredAndInvalidStorageFailsExplicitly() {
        val state = TaskTemplates().state.value.json()
        state.getJSONArray("entries").remove(0)
        assertEquals(4, TaskTemplates(state).state.value.entries.size)
        assertThrows(IllegalArgumentException::class.java) { TaskTemplates(state.put("version", 2)) }
        val duplicate = TaskTemplates().state.value.json()
        duplicate.getJSONArray("entries").put(duplicate.getJSONArray("entries").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { TaskTemplates(duplicate) }
    }

    @Test fun closedAccountCannotEditOrRepopulateSuccessfulDefaults() {
        val templates = TaskTemplates()
        val id = templates.state.value.entries.first().id
        templates.close()
        assertThrows(IllegalStateException::class.java) { templates.apply(TaskTemplateChange.Save(custom(), true)) }
        templates.recordSuccess(id, "old-account", "/private")
        assertNull(templates.state.value.lastOrigin)
        assertTrue(templates.state.value.recent.isEmpty())
    }
}
