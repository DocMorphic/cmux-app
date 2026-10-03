package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TaskDraftsTest {
    @Test fun completedAnchorAndRetiredIdentitySurviveStorageAndOldDraftsStillLoad() {
        val drafts = TaskDrafts()
        val editor = begin(drafts)
        val old = TaskCommand.parameters(TaskCommand.Agent.CLAUDE, "Recover", "/repo", UUID.randomUUID())
        val fresh = TaskSubmissionIdentity().retire("mac-one", old)
        drafts.edit(editor) { it.copy(prompt = "Recover", lastRequest = fresh.toString(), completedRequest = old.toString()) }
        val restored = TaskDrafts(drafts.saved()).state.value.getValue(editor.id)
        assertEquals(old.getString("operation_id"), JSONObject(restored.completedRequest!!).getString("operation_id"))
        assertEquals(fresh.getString("operation_id"), JSONObject(restored.lastRequest!!).getString("operation_id"))
        assertTrue(TaskCompletedRecovery(restored.origin, restored.completedRequest).appliesTo("mac-one", fresh))
        val oldFormat = restored.json().apply { remove("completed_request") }
        assertNull(TaskDraft.read(oldFormat).completedRequest)
        assertThrows(IllegalArgumentException::class.java) { TaskDraft.read(restored.json().put("last_request", old)) }
        assertThrows(IllegalArgumentException::class.java) { TaskDraft.read(restored.json().apply { remove("last_request") }) }
    }

    private fun begin(drafts: TaskDrafts, origin: String = "mac-one") = drafts.begin(UUID.randomUUID().toString(), origin, "My Mac", "/repo")
    private val model = TaskModel("selected-model", "Selected model", listOf(TaskEffort("low", "Low", "Quick"), TaskEffort("high", "High")), "high")

    @Test fun savesMultipleDraftsNewestFirstWithoutDuplicatingOneSession() {
        val drafts = TaskDrafts(now = { 100L })
        val one = begin(drafts); val two = begin(drafts, "mac-two")
        drafts.edit(one) { it.copy(prompt = "First") }
        drafts.edit(two) { it.copy(prompt = "Second") }
        drafts.edit(one) { it.copy(prompt = "Edited first") }
        val restored = TaskDrafts(drafts.saved()).state.value.values.toList()
        assertEquals(listOf(one.id, two.id), restored.map { it.id })
        assertEquals(listOf("Edited first", "Second"), restored.map { it.prompt })
        assertEquals(listOf("mac-one", "mac-two"), restored.map { it.origin })
    }

    @Test fun modelEffortUnicodeRawTextAndExactRetrySurviveRoundTrip() {
        val drafts = TaskDrafts()
        val editor = begin(drafts)
        val parameters = TaskCommand.parameters(TaskCommand.Agent.CODEX, "  Fix 中\n'quotes'  ", " /project ", UUID.randomUUID(), model.id, "low")
        drafts.edit(editor) { it.copy(agent = TaskCommand.Agent.CODEX, prompt = "  Fix 中\n'quotes'  ", directory = " /project ",
            selection = TaskModelSelection(model, "low"), defaultModel = model, lastRequest = parameters.toString()) }
        val restored = TaskDrafts(JSONObject(drafts.saved().toString())).state.value.getValue(editor.id)
        assertEquals(drafts.state.value[editor.id], restored)
        assertEquals("low", restored.selection.reconcile(restored.restoredModels()).effortId)
        val identity = TaskSubmissionIdentity().apply { submitted(restored.origin, JSONObject(restored.lastRequest!!)) }
        val retry = identity.resolve(restored.origin, TaskCommand.parameters(restored.agent, restored.prompt,
            restored.directory, UUID.randomUUID(), restored.selection.explicit?.id, restored.selection.effortId))
        assertEquals(parameters.getString("operation_id"), retry.getString("operation_id"))
        assertEquals(parameters.getString("initial_command"), retry.getString("initial_command"))
        assertNotEquals(parameters.getString("operation_id"), identity.resolve(restored.origin,
            TaskCommand.parameters(restored.agent, "Changed task", restored.directory, UUID.randomUUID())).getString("operation_id"))
    }

    @Test fun implicitDefaultEffortSurvivesWithoutBecomingAnExplicitModel() {
        val drafts = TaskDrafts(); val editor = begin(drafts)
        drafts.edit(editor) { it.copy(prompt = "Default task", selection = TaskModelSelection(effortId = "low"), defaultModel = model) }
        val restored = TaskDrafts(drafts.saved()).state.value.getValue(editor.id)
        val choice = restored.selection.reconcile(restored.restoredModels())
        assertNull(choice.explicit); assertEquals("low", choice.effortId)
        assertEquals(model, choice.effective(restored.restoredModels()))
    }

    @Test fun delayedMetadataForPreviousProviderCannotModifyNewProviderDraft() {
        val draft = TaskDraft(UUID.randomUUID().toString(), "mac", "Mac", 1L, TaskCommand.Agent.CODEX, prompt = "Task")
        val delayed = TaskModelResult(listOf(model), TaskModelSource.DISCOVERED, model)
        assertEquals(draft, draft.reconcileModels(TaskAgentCommand.CLAUDE, delayed))
        assertNull(draft.reconcileModels(TaskAgentCommand.CLAUDE, delayed).defaultModel)
        assertEquals(model, draft.reconcileModels(TaskAgentCommand.CODEX, delayed).defaultModel)
    }

    @Test fun refreshedExplicitMetadataIsPersistedWithNewlyAvailableEffort() {
        val refreshed = model.copy(name = "Updated name", efforts = model.efforts + TaskEffort("new", "New effort"))
        val draft = TaskDraft(UUID.randomUUID().toString(), "mac", "Mac", 1L, TaskCommand.Agent.CODEX,
            prompt = "Task", selection = TaskModelSelection(model, "new"))
        val reconciled = draft.reconcileModels(TaskAgentCommand.CODEX, TaskModelResult(listOf(refreshed), TaskModelSource.DISCOVERED))
        val restored = TaskDraft.read(reconciled.json())
        assertEquals("new", restored.selection.reconcile(restored.restoredModels()).effortId)
        assertEquals(refreshed, restored.selection.explicit)
    }

    @Test fun emptySelectionsAreNotSavedButPendingUntitledShellIsRetained() {
        val drafts = TaskDrafts(); val editor = begin(drafts)
        drafts.edit(editor) { it.copy(prompt = " \n", directory = "/changed", selection = TaskModelSelection(model, "low")) }
        assertEquals(0, drafts.saved().getJSONArray("drafts").length())
        drafts.edit(editor) { it.copy(agent = TaskCommand.Agent.SHELL,
            lastRequest = TaskCommand.parameters(TaskCommand.Agent.SHELL, "", "/changed", UUID.randomUUID()).toString()) }
        assertEquals(1, drafts.saved().getJSONArray("drafts").length())
        drafts.end(editor)
        assertTrue(editor.id in drafts.state.value)
    }

    @Test fun editingThenEmptyingRemovesPersistedWork() {
        val drafts = TaskDrafts(); val editor = begin(drafts)
        drafts.edit(editor) { it.copy(prompt = "Draft") }
        assertEquals(1, drafts.saved().getJSONArray("drafts").length())
        drafts.edit(editor) { it.copy(prompt = "") }
        drafts.end(editor)
        assertTrue(drafts.state.value.isEmpty())
        assertEquals(0, drafts.saved().getJSONArray("drafts").length())
    }

    @Test fun lateEditorCannotOverwriteDeleteOrResurrectResumedDraft() {
        val drafts = TaskDrafts(); val old = begin(drafts)
        drafts.edit(old) { it.copy(prompt = "Saved") }
        val newer = drafts.begin(old.id, "mac-one", "My Mac", "/repo")
        drafts.edit(newer) { it.copy(prompt = "Resumed") }
        assertThrows(IllegalStateException::class.java) { drafts.edit(old) { it.copy(prompt = "Stale") } }
        assertNull(drafts.editIfCurrent(old) { it.copy(prompt = "Late IME event") })
        assertNull(drafts.remove(old))
        drafts.end(old)
        assertEquals("Resumed", drafts.state.value.getValue(newer.id).prompt)
        val removed = drafts.remove(newer)!!
        drafts.clear()
        drafts.restore(newer, removed)
        assertTrue(drafts.state.value.isEmpty())
    }

    @Test fun failedDeletionCanRollbackOnlyBeforeAnotherEditorTakesOwnership() {
        val drafts = TaskDrafts(); val editor = begin(drafts)
        drafts.edit(editor) { it.copy(prompt = "Keep when disk fails") }
        val removed = drafts.remove(editor)!!
        drafts.restore(editor, removed)
        assertEquals(removed, drafts.state.value[editor.id])
        drafts.remove(editor)
        val fresh = drafts.begin(editor.id, "mac-one", "My Mac", "/different")
        drafts.edit(fresh) { it.copy(prompt = "New content") }
        drafts.restore(editor, removed)
        assertEquals("New content", drafts.state.value.getValue(editor.id).prompt)
    }

    @Test fun collectionsAreBoundedToTwentyNewestSavedDrafts() {
        val drafts = TaskDrafts(now = { 100 })
        repeat(25) { index -> drafts.edit(begin(drafts)) { it.copy(prompt = "Task $index") } }
        assertEquals(20, drafts.state.value.size)
        assertEquals((24 downTo 5).map { "Task $it" }, TaskDrafts(drafts.saved()).state.value.values.map { it.prompt })
    }

    @Test fun wrongMacCannotResumeAndUnknownStorageCannotBeSilentlyReplaced() {
        val drafts = TaskDrafts(); val editor = begin(drafts)
        assertThrows(IllegalArgumentException::class.java) { drafts.begin(editor.id, "other", "Other", "/repo") }
        assertThrows(IllegalArgumentException::class.java) { TaskDrafts(JSONObject().put("version", 2).put("drafts", org.json.JSONArray())) }
        drafts.edit(editor) { it.copy(prompt = "Do not lose") }
        val corrupted = drafts.saved()
        corrupted.getJSONArray("drafts").put(corrupted.getJSONArray("drafts").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { TaskDrafts(corrupted) }
    }
}
