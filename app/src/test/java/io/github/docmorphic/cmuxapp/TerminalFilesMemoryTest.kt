package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TerminalFilesMemoryTest {
    private val key = NativeWorkspaceTabKey("account", "team", "mac-build", "workspace")
    private val terminal = ArtifactAuthorization.Terminal("workspace", "terminal")
    private fun populated(memory: TerminalFilesMemory): TerminalFilesState = memory.bind("login", key, "terminal").apply {
        showing = true; path = "./folder"
        gallery.apply {
            sessionScope = false; searchText = "archive"; grid = true; filter = ArtifactFilter.DOCS; sort = ArtifactSort.NAME
            folded = setOf("Created", "Referenced")
            open(ArtifactDestination.Folder(ArtifactItem("/folder", ArtifactKind.DIRECTORY), ArtifactAuthorization.Session("session")))
            open(ArtifactDestination.Preview(listOf(ArtifactItem("/folder/a.txt"), ArtifactItem("/folder/b.txt")), "/folder/a.txt", ArtifactAuthorization.Session("session")))
            selectedPath = "/folder/b.txt"
        }
        direct.open(ArtifactDestination.Folder(ArtifactItem("./folder", ArtifactKind.DIRECTORY), terminal))
    }
    @Test fun roundTripKeepsNestedRoutesCurrentPagerPathAndControls() {
        val memory = TerminalFilesMemory(); populated(memory)
        val restored = TerminalFilesMemory.decode(memory.encode()).bind("login", key, "terminal")
        assertTrue(restored.showing); assertEquals("./folder", restored.path)
        restored.gallery.apply {
            assertFalse(sessionScope); assertEquals("archive", searchText); assertTrue(grid)
            assertEquals(ArtifactFilter.DOCS, filter); assertEquals(ArtifactSort.NAME, sort)
            assertEquals(setOf("Created", "Referenced"), folded)
            assertEquals(2, destinations.size); assertEquals("/folder/b.txt", selectedPath)
            assertEquals("/folder/b.txt", (destinations.last() as ArtifactDestination.Preview).initialPath)
            assertTrue(matches(terminal, ArtifactAuthorization.Session("session")))
            assertFalse(matches(terminal, ArtifactAuthorization.Session("replacement-session")))
            assertFalse(matches(terminal, null))
        }
        assertTrue(restored.direct.matches(terminal, null))
        assertFalse(restored.direct.matches(terminal.copy(surfaceId = "other"), null))
    }
    @Test fun ownerChangesAndLateCallbacksCannotReviveOldOverlay() {
        for ((login, key, surface) in listOf(Triple("other", key, "terminal"), Triple("login", key.copy(accountId = "other"), "terminal"),
            Triple("login", key.copy(teamId = "other"), "terminal"), Triple("login", key.copy(computerId = "other"), "terminal"),
            Triple("login", key.copy(workspaceId = "other"), "terminal"), Triple("login", key, "other"))) {
            val memory = TerminalFilesMemory(); val old = populated(memory)
            val next = memory.bind(login, key, surface); old.showing = true; old.openPath("late.txt")
            assertFalse(next.showing); assertNull(next.path); assertTrue(next.gallery.destinations.isEmpty())
        }
        val memory = TerminalFilesMemory(); populated(memory); memory.retainLogin(null); assertEquals("", memory.encode())
    }
    @Test fun explicitCloseResetsRoutesAndControls() {
        val state = populated(TerminalFilesMemory()); state.closeGallery(); state.closePath()
        assertFalse(state.showing); assertNull(state.path); assertTrue(state.gallery.destinations.isEmpty())
        assertTrue(state.direct.destinations.isEmpty()); assertEquals("", state.gallery.searchText)
    }
    @Test fun malformedStateIsDiscardedAndLargePagerFallsBackToOwningSheet() {
        val memory = TerminalFilesMemory(); val state = populated(memory)
        val valid = memory.encode()
        for (invalid in listOf("{", "x".repeat(100_000), JSONObject(valid).put("version", 99).toString(),
            JSONObject(valid).put("path", "x\u0000y").toString(), JSONObject(valid).put("showing", 1).toString())) {
            val restored = TerminalFilesMemory.decode(invalid).bind("login", key, "terminal")
            assertFalse(restored.showing); assertNull(restored.path)
        }
        state.gallery.clearRoutes()
        val files = (0 until 2000).map { ArtifactItem("/folder/$it/" + "a".repeat(100)) }
        state.gallery.open(ArtifactDestination.Preview(files, files.first().path, terminal))
        val encoded = memory.encode(); assertTrue(encoded.isNotEmpty() && encoded.length <= 98_304)
        val restored = TerminalFilesMemory.decode(encoded).bind("login", key, "terminal")
        assertTrue(restored.showing); assertEquals("archive", restored.gallery.searchText)
        assertTrue(restored.gallery.destinations.isEmpty())
    }
}
