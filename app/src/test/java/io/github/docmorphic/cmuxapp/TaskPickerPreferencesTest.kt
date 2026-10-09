package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TaskPickerPreferencesTest {
    private val templates = TaskTemplates()
    private val claude = templates.state.value.entries.first()
    private val codex = templates.state.value.entries[1]
    private val model = TaskModel("local-model", "Local model", listOf(TaskEffort("high", "High")), "high")
    private fun draft(origin: String, template: TaskTemplate = claude) =
        TaskDraft(UUID.randomUUID().toString(), origin, "Mac", 1).selecting(template, "/open")

    @Test fun exactPairingsRestoreIndependentChoicesIncludingExplicitDefault() {
        val stable = draft("mac:stable").copy(directory = "/stable typed", didEditDirectory = true,
            selection = TaskModelSelection(null, "high"), defaultModel = model, groupId = "stable-group")
        val nightly = draft("mac:nightly", codex).copy(directory = "/nightly typed", didEditDirectory = true,
            selection = TaskModelSelection(model, "high"), defaultModel = model, groupId = "nightly-group")
        templates.rememberPickers(stable); templates.rememberPickers(nightly)
        val saved = TaskTemplates(templates.state.value.json()).state.value
        val a = saved.restorePickers(draft(stable.origin), "/different")
        val b = saved.restorePickers(draft(nightly.origin), "/different")
        assertEquals(claude.id, a.templateId); assertEquals(codex.id, b.templateId)
        assertNull(a.selection.explicit); assertEquals(model, a.defaultModel); assertEquals("high", a.selection.effortId)
        assertEquals(model, b.selection.explicit)
        assertEquals("/stable typed", a.directory); assertEquals("/nightly typed", b.directory)
        assertEquals("stable-group", a.groupId); assertEquals("nightly-group", b.groupId)
        assertEquals(nightly.origin, saved.lastOrigin)
    }

    @Test fun switchingToUnseenMacClearsMachineChoicesButPreservesTaskAndRecoveryOwner() {
        val source = draft("first", codex).copy(prompt = "Fix this", workspaceName = "Work",
            directory = "/first/private", didEditDirectory = true, selection = TaskModelSelection(model, "high"),
            defaultModel = model, groupId = "first-group", lastRequest = "{}")
        templates.rememberPickers(source)
        val changed = templates.state.value.restorePickers(source.onMac("second", "Second", "/second"), "/second", source.templateId)
        assertEquals(codex.id, changed.templateId); assertEquals("/second", changed.directory)
        assertFalse(changed.didEditDirectory); assertNull(changed.groupId)
        assertEquals(TaskModelSelection(), changed.selection); assertNull(changed.defaultModel)
        assertEquals(source.prompt, changed.prompt); assertEquals(source.workspaceName, changed.workspaceName)
        assertEquals(source.id, changed.id); assertEquals("first", changed.lastRequestOrigin)
        val returned = templates.state.value.restorePickers(changed.onMac("first", "First", "~"), null, changed.templateId)
        assertEquals(source.directory, returned.directory); assertEquals(source.selection, returned.selection)
    }

    @Test fun missingRememberedTemplateFallsBackWithoutLeakingItsModels() {
        val custom = TaskTemplate(UUID.randomUUID().toString(), "Custom", command = "codex")
        templates.apply(TaskTemplateChange.Save(custom, true))
        templates.rememberPickers(draft("mac", custom).copy(selection = TaskModelSelection(model, "high"),
            defaultModel = model, directory = "/typed", didEditDirectory = true, groupId = "pending-group"))
        templates.apply(TaskTemplateChange.Delete(custom.id))
        val state = TaskTemplates(templates.state.value.json()).state.value
        val moved = state.restorePickers(draft("mac"), "/open", codex.id)
        assertEquals(codex.id, moved.templateId); assertNull(moved.selection.explicit); assertNull(moved.defaultModel)
        assertEquals("/typed", moved.directory); assertEquals("pending-group", moved.groupId)
        templates.recordSuccess(codex.id, "other", null)
        assertEquals(codex.id, templates.state.value.restorePickers(draft("mac"), null).templateId)
    }

    @Test fun automaticDirectoryIsRecomputedFromCurrentHostInventory() {
        templates.rememberPickers(draft("mac").copy(directory = "/stale suggested"))
        assertEquals("/fresh", templates.state.value.restorePickers(draft("mac"), "/fresh").directory)
        templates.apply(TaskTemplateChange.Save(claude.copy(defaultDirectory = "/template"), false))
        assertEquals("/template", templates.state.value.restorePickers(draft("mac"), "/fresh").directory)
    }

    @Test fun legacyStorageAndClosedAccountsDoNotRestoreOtherAccountChoices() {
        val old = templates.state.value.json().also { it.remove("pickers") }
        assertTrue(TaskTemplates(old).state.value.pickers.isEmpty())
        templates.rememberPickers(draft("old-account"))
        templates.close(); templates.rememberPickers(draft("old-account"))
        assertTrue(templates.state.value.pickers.isEmpty()); assertNull(templates.state.value.lastOrigin)
    }

    @Test fun sameMacRetargetKeepsTypedChoices() {
        val original = draft("mac").copy(directory = "/typed", didEditDirectory = true,
            selection = TaskModelSelection(model, "high"), defaultModel = model, groupId = "group")
        val renamed = original.onMac("mac", "Renamed", "/new suggestion")
        assertEquals(original.copy(macName = "Renamed"), renamed)
    }
}
