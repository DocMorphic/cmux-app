package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSurfaceTest {
    @Test fun inventoryPreservesUnknownKindsFocusPathsAndTodoWithoutInventingTerminals() {
        val workspace = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"w","terminals":[],"surfaces":[
            {"surface_id":"a","kind":"future.canvas","title":"Canvas","is_focused":true,"file_path":"/tmp/a"},
            {"surface_id":"b","kind":"todo","title":"Todo","todo":{"status":"review","status_hidden":false,"items":[]}},
            {"surface_id":"c","kind":"markdown","title":"Notes","file_path":"/tmp/notes"}
        ]}]}""")).single()
        assertTrue(workspace.terminals.isEmpty()); assertTrue(workspace.browsers.isEmpty()); assertTrue(workspace.hasPanes)
        assertEquals(3, workspace.macSurfaces.size)
        assertEquals("future.canvas", workspace.surfaces[0].kind); assertTrue(workspace.surfaces[0].isFocused)
        assertEquals("/tmp/a", workspace.surfaces[0].filePath)
        assertEquals("review", JSONObject(workspace.surfaces[1].todoJson!!).getString("status"))
        assertTrue(workspace.surfaces[2].isPanelFile)
    }

    @Test fun invalidDescriptorsAreDroppedAndDuplicateSurfaceIdsDoNotCreateAmbiguousRows() {
        val workspace = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"w","surfaces":[
            {"kind":"todo"},{"surface_id":"s"},{"surface_id":"s","kind":false},
            {"surface_id":"s","kind":"markdown","file_path":"relative"},
            {"surface_id":"s","kind":"future.canvas"}
        ]}]}""")).single()
        assertEquals(1, workspace.surfaces.size); assertFalse(workspace.surfaces.single().isPanelFile)
        assertTrue(parseWorkspaces(JSONObject("""{"workspaces":[{"id":"old"}]}""")).single().surfaces.isEmpty())
    }

    @Test fun panelOperationsUseOnlyTheirExactWorkspaceSurfaceAndFile() = runBlocking<Unit> {
        val scope = ArtifactAuthorization.Panel("w", "s", "/tmp/notes.md")
        val methods = mutableListOf<String>()
        val rpc = ArtifactRpc(ArtifactCapabilities(false, false, false, false, panel = true)) { method, params ->
            methods += method
            assertEquals("w", params.getString("workspace_id")); assertEquals("s", params.getString("surface_id"))
            assertEquals(scope.displayedPath, params.getString("path")); assertFalse(params.has("session_id"))
            JSONObject()
        }
        rpc.stat(scope, scope.displayedPath); rpc.fetch(scope, scope.displayedPath, 0, 1024); rpc.thumbnail(scope, scope.displayedPath, 200)
        assertEquals(listOf("stat", "fetch", "thumbnail").map { "mobile.panel.artifact.$it" }, methods)
        assertTrue(runCatching { rpc.list(scope, "/tmp") }.isFailure)
        assertTrue(runCatching { rpc.stat(scope, "/tmp/secret") }.isFailure)
        assertTrue(runCatching { rpc.fetch(scope, "/tmp/secret", 0, 10) }.isFailure)
        assertTrue(runCatching { rpc.thumbnail(scope, "/tmp/secret", 10) }.isFailure)
        assertEquals(3, methods.size)
    }

    @Test fun panelCapabilityCannotFallBackToTerminalOrChatAccess() = runBlocking<Unit> {
        val rpc = ArtifactRpc(ArtifactCapabilities(true, true, true, true)) { _, _ -> fail("Must reject locally"); JSONObject() }
        val panel = ArtifactAuthorization.Panel("w", "s", "/tmp/a")
        assertTrue(runCatching { rpc.stat(panel, panel.displayedPath) }.isFailure)
        assertTrue(runCatching { rpc.fetch(panel, panel.displayedPath, 0, 1) }.isFailure)
        assertTrue(runCatching { rpc.thumbnail(panel, panel.displayedPath, 20) }.isFailure)
    }

    @Test fun movedNotificationCanResolveSurfaceOwnerWithoutOpeningAnUnrelatedTerminal() {
        val workspaces = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"old"},{"id":"new","surfaces":[
            {"surface_id":"s","kind":"markdown","title":"Release notes","file_path":"/tmp/a.md"}]}]}"""))
        val notification = NativeNotification("n", "old", "s", "Ready", "", false, retargetsToLiveSurfaceOwner = true)
        assertEquals("new", notification.destination(workspaces)?.id)
        assertTrue(notification.searchFields(workspaces, "Mac").contains("Release notes"))
        assertNull(notification.copy(surfaceId = "gone").destination(workspaces))
    }
}
