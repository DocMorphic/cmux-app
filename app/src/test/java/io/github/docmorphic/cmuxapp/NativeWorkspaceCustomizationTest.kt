package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeWorkspaceCustomizationTest {
    private val original = WorkspaceCustomizationDraft("Name", "Description", "#123456", false)
    @Test fun editsPreserveUnchangedMacFieldsAndUseFieldOrder() = runBlocking {
        var current = original.copy(color = "#ABCDEF")
        val written = mutableListOf<WorkspaceCustomizationField>()
        val submitted = original.copy(name = "New", description = "  Line one\r\nLine two  ", pinned = true)
        val result = saveWorkspaceCustomization(original, submitted, { current }) { field, draft ->
            written += field
            current = when (field) {
                WorkspaceCustomizationField.NAME -> current.copy(name = draft.name)
                WorkspaceCustomizationField.DESCRIPTION -> current.copy(description = draft.description)
                WorkspaceCustomizationField.PINNED -> current.copy(pinned = draft.pinned)
                else -> error("Untouched color was written")
            }
        }
        assertTrue(result.succeeded)
        assertEquals(listOf(WorkspaceCustomizationField.NAME, WorkspaceCustomizationField.DESCRIPTION, WorkspaceCustomizationField.PINNED), written)
        assertEquals("#ABCDEF", current.color); assertEquals("Line one\nLine two", current.description)
    }
    @Test fun conflictStopsBeforeWriteAndRebasesToMac() = runBlocking {
        val current = original.copy(description = "Changed on Mac")
        val result = saveWorkspaceCustomization(original, original.copy(description = "Phone"), { current }) { _, _ -> error("Conflicting write") }
        assertFalse(result.succeeded); assertEquals(current, result.baseline); assertEquals(current, result.display)
        assertTrue(result.message!!.contains("changed on your Mac"))
    }
    @Test fun partialFailureRetainsUnsentEditsAndRetryDoesNotRepeatAcceptedField() = runBlocking {
        var current = original
        val submitted = original.copy(name = "New name", description = "New description", color = "#FFFFFF")
        val attempted = mutableListOf<WorkspaceCustomizationField>()
        val first = saveWorkspaceCustomization(original, submitted, { current }) { field, draft ->
            attempted += field
            if (field == WorkspaceCustomizationField.DESCRIPTION) throw java.io.IOException("Fixture rejected description")
            current = current.copy(name = draft.name)
        }
        assertFalse(first.succeeded); assertEquals(current, first.baseline)
        assertEquals(submitted, first.display)
        attempted.clear()
        val retry = saveWorkspaceCustomization(first.baseline!!, first.display!!, { current }) { field, draft ->
            attempted += field
            current = when (field) {
                WorkspaceCustomizationField.DESCRIPTION -> current.copy(description = draft.description)
                WorkspaceCustomizationField.COLOR -> current.copy(color = draft.color)
                else -> error("Repeated accepted rename")
            }
        }
        assertTrue(retry.succeeded)
        assertEquals(listOf(WorkspaceCustomizationField.DESCRIPTION, WorkspaceCustomizationField.COLOR), attempted)
    }
    @Test fun droppedAcknowledgementDoesNotReplayAnAlreadyAppliedChange() = runBlocking {
        val submitted = original.copy(name = "Landed")
        var current = original
        val first = saveWorkspaceCustomization(original, submitted, { current }) { _, _ ->
            current = submitted; throw java.io.IOException("Lost acknowledgement")
        }
        assertFalse(first.succeeded); assertEquals(submitted, first.baseline)
        assertTrue(saveWorkspaceCustomization(original, submitted, { current }) { _, _ -> error("Duplicate mutation") }.succeeded)
    }
    @Test fun descriptionsUseUtf8ByteLimitAndTruncatedSnapshotsAreReadOnly() = runBlocking {
        val boundary = original.copy(description = "🙂".repeat(1024))
        boundary.validate(original)
        assertTrue(runCatching { boundary.copy(description = boundary.description + "a").validate(original) }.isFailure)
        val mac = original.copy(descriptionTruncated = true)
        val blocked = saveWorkspaceCustomization(mac, mac.copy(description = "replacement"), { mac }) { _, _ -> error("Truncated overwrite") }
        assertFalse(blocked.succeeded)
        val appeared = saveWorkspaceCustomization(original, original.copy(description = "replacement"), { mac }) { _, _ -> error("Newly truncated overwrite") }
        assertFalse(appeared.succeeded); assertTrue(appeared.message!!.contains("longer than Android"))
        val row = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"w","description":"preview","description_truncated":true}]}""")).single()
        assertTrue(WorkspaceCustomizationDraft.from(row).descriptionTruncated)
        assertEquals("null", parseWorkspaces(JSONObject("""{"workspaces":[{"id":"w","description":"null"}]}""")).single().description)
    }
    @Test fun ownerCancellationStopsRemainingWrites() = runBlocking {
        val cancellation = CancellationException("Owner retired")
        val result = runCatching { saveWorkspaceCustomization(original, original.copy(name = "Changed", pinned = true),
            { original }) { _, _ -> throw cancellation } }
        assertSame(cancellation, result.exceptionOrNull())
    }
    @Test fun metadataWireUsesOwnerWindowAndExplicitClearActions() = runBlocking {
        val wire = PoolTestTransport()
        val row = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"w","window_id":"window"}]}""")).single()
        MobileRpcClient(wire, { "fixture" }).use { client ->
            client.connect()
            for ((field, draft, action) in listOf(
                Triple(WorkspaceCustomizationField.DESCRIPTION, original.copy(description = "  \r\n  "), "clear_description"),
                Triple(WorkspaceCustomizationField.DESCRIPTION, original.copy(description = "A\r\nB"), "set_description"),
                Triple(WorkspaceCustomizationField.COLOR, original.copy(color = null), "clear_color"),
                Triple(WorkspaceCustomizationField.COLOR, original.copy(color = "#abcdef"), "set_color"))) {
                val request = async { client.customizeWorkspace(row, field, draft) }
                val frame = withTimeout(2000) { wire.sent.receive() }; val params = frame.getJSONObject("params")
                assertEquals("workspace.action", frame.getString("method")); assertEquals(action, params.getString("action"))
                assertEquals("w", params.getString("workspace_id")); assertEquals("window", params.getString("window_id"))
                assertTrue(params.has("client_id"))
                if (action == "set_description") assertEquals("A\nB", params.getString("description"))
                if (action == "set_color") assertEquals("#ABCDEF", params.getString("color"))
                if (action.startsWith("clear")) { assertFalse(params.has("description")); assertFalse(params.has("color")) }
                wire.answer(frame); withTimeout(2000) { request.await() }
            }
        }
    }
}
