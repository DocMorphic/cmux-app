package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CloudWorkspaceCatalogTest {
    private fun String.bytes() = toByteArray(Charsets.UTF_8)
    @Test fun hierarchyOrdersTerminalsAndPreservesDetachedPoolAndDisplayNames() {
        val catalog = CloudWorkspaceDecoding.snapshot("""{
          "workspaces":[{"id":"ws_a","name":"Code"},{"id":"ws_b","name":"Ops"}],
          "screens":[{"id":"s_a","workspace_id":"ws_a","index":0},{"id":"s_b","workspace_id":"ws_b"}],
          "panes":[{"id":"p_a","screen_id":"s_a"},{"id":"p_b","screen_id":"s_b"}],
          "tabs":[{"id":"t_a","pane_id":"p_a","index":2,"name":" build "},
                  {"id":"t_b","pane_id":"p_a","index":1},{"id":"t_c","pane_id":"p_b"}],
          "terminals":[{"id":"term_pool","cwd":"/root"},{"id":"term_c","tab_ids":["t_c","t_b"],"cwd":"/home/me/logs"},
                       {"id":"term_a","tab_id":"t_a","title":"ignored"},{"id":"term_b","tab_id":"t_b","title":" editor "}]
        }""".bytes())
        assertEquals(listOf("ws_a", "ws_b"), catalog.workspaces.map { it.id })
        assertEquals(listOf("term_b", "term_a", "term_c", "term_pool"), catalog.terminals.map { it.id })
        assertEquals(listOf("ws_a", "ws_a", "ws_b", null), catalog.terminals.map { it.workspaceId })
        assertEquals(listOf("editor", "build", "~/logs", null), catalog.terminals.map { it.descriptiveName })
        val machine = CloudMachine("vm_one", "fixture", "running", "Personal", null, null)
        val rows = projectCloudWorkspaces(machine, catalog)
        assertEquals(listOf("Code", "Ops", "Personal"), rows.map { it.workspace.title })
        assertEquals("Terminal 1", rows.last().workspace.terminals.single().title)
        assertEquals("unassigned", rows.last().remoteId)
        assertTrue(rows.all { CloudAddress.parse(it.workspace.id)?.machineId == machine.id && !it.workspace.isPinned && it.workspace.browsers.isEmpty() })
    }
    @Test fun missingHierarchyBecomesUnassignedAndTieOrderIsStable() {
        val catalog = CloudWorkspaceDecoding.snapshot("""{"workspaces":[{"id":"ws_a"}],
          "terminals":[{"id":"term_b","tab_id":"missing"},{"id":"term_a"}]}""".bytes())
        assertEquals(listOf("term_b", "term_a"), catalog.terminals.map { it.id })
        assertTrue(catalog.terminals.all { it.workspaceId == null })
    }
    @Test fun legacyListsAndCreateResponsesUseTheirOwnWireShapes() {
        val bare = """[{"id":"ws_a","root":"/home/me/project/"}]"""
        assertEquals("project", CloudWorkspaceDecoding.workspaces(bare.bytes()).single().preferredName)
        assertEquals(CloudWorkspaceDecoding.workspaces(bare.bytes()), CloudWorkspaceDecoding.workspaces("""{"workspaces":$bare}""".bytes()))
        val terminals = CloudWorkspaceDecoding.terminals("""[{"id":"term_a","workspace_id":"ws_a","cwd":"/Users/person/repo"}]""".bytes())
        assertEquals("ws_a", terminals.single().workspaceId); assertEquals("~/repo", terminals.single().descriptiveName)
        assertEquals("ws_created", CloudWorkspaceDecoding.created("""{"value":{"workspace_id":"ws_created"}}""".bytes(), false))
        assertEquals("term_created", CloudWorkspaceDecoding.created("""{"value":{"terminal_id":"term_created"}}""".bytes(), true))
        assertThrows(Exception::class.java) { CloudWorkspaceDecoding.created("""{"terminal_id":"wrong-shape"}""".bytes(), true) }
    }
    @Test fun malformedCatalogsCannotBecomeAnAuthoritativeEmptyList() {
        for (json in listOf("{}", """{"workspaces":[],"terminals":null}""",
            """{"workspaces":[{"id":"same"},{"id":"same"}],"terminals":[]}""",
            """{"workspaces":[],"terminals":[{"id":"same"},{"id":"same"}]}""",
            """{"workspaces":[],"terminals":[{"id":12}]}""",
            """{"workspaces":[],"terminals":[]} trailing""")) {
            assertTrue(json, runCatching { CloudWorkspaceDecoding.snapshot(json.bytes()) }.isFailure)
        }
        assertTrue(runCatching { CloudWorkspaceDecoding.terminals(byteArrayOf(0xc3.toByte(), 0x28)) }.isFailure)
    }
    @Test fun cloudAddressIsDistinctFromMacAndKeepsTheWholeComponent() {
        val address = CloudAddress("vm_a", "term\u001drest")
        assertEquals(address, CloudAddress.parse(address.identifier))
        assertEquals(CloudAddress("vm_a"), CloudAddress.parse(address.host.identifier))
        assertNotEquals(address.identifier, CloudAddress("vm_b", address.component).identifier)
        for (invalid in listOf("mac\u001fdebug", "cmux-cloud\u001fvm_a", "cmux-cloud\u001d", "cmux-cloud\u001dvm\u001d"))
            assertNull(CloudAddress.parse(invalid))
    }
    @Test fun fallbackReadsBothListsButNeverRunsAfterCancellation() = runTest {
        val calls = mutableListOf<CloudCatalogOperation>()
        val catalog = loadCloudWorkspaceCatalog { operation ->
            calls += operation
            when (operation) {
                CloudCatalogOperation.SNAPSHOT -> error("old daemon")
                CloudCatalogOperation.WORKSPACES -> """{"workspaces":[{"id":"ws_a"}]}""".bytes()
                CloudCatalogOperation.TERMINALS -> """[{"id":"term_a"}]""".bytes()
                else -> error("mutation attempted")
            }
        }
        assertEquals(3, calls.size); assertEquals("ws_a", catalog.workspaces.single().id)
        calls.clear()
        try {
            loadCloudWorkspaceCatalog { calls += it; throw CancellationException("owner retired") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertEquals(listOf(CloudCatalogOperation.SNAPSHOT), calls) }
    }
}
