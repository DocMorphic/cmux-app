package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChangesNavigationMemoryTest {
    private val key = NativeWorkspaceTabKey("account", "team", "mac-build", "workspace")
    @Test fun roundTripRetainsPathAndFoldersForTheExactOwner() {
        val memory = ChangesNavigationMemory()
        val state = memory.bind("login", key)
        state.selected = "src/中.kt"; state.collapsed = setOf("docs", "tests/unit")
        assertSame(state, memory.bind("login", key))
        val restored = ChangesNavigationMemory.decode(memory.encode()).bind("login", key)
        assertEquals(state.selected, restored.selected); assertEquals(state.collapsed, restored.collapsed)
    }
    @Test fun everyOwnerDimensionResetsAndOldCallbacksCannotChangeTheNewState() {
        val owners = listOf("other-login" to key, "login" to key.copy(accountId = "other"),
            "login" to key.copy(teamId = "other"), "login" to key.copy(computerId = "other-build"),
            "login" to key.copy(workspaceId = "other"))
        owners.forEach { (login, changed) ->
            val memory = ChangesNavigationMemory()
            val previous = memory.bind("login", key).also { it.selected = "old.txt"; it.collapsed = setOf("private") }
            val current = memory.bind(login, changed)
            previous.selected = "late.txt"
            assertNull(current.selected); assertTrue(current.collapsed.isEmpty())
            assertNull(ChangesNavigationMemory.decode(memory.encode()).bind(login, changed).selected)
        }
    }
    @Test fun logoutAndExplicitExitForgetTheDetail() {
        val memory = ChangesNavigationMemory()
        memory.bind("login", key).selected = "file.txt"
        memory.retainLogin(null)
        assertEquals("", memory.encode()); assertNull(memory.bind("login", key).selected)
        memory.bind("login", key).selected = "other.txt"
        memory.clear(); assertNull(memory.bind("login", key).selected)
    }
    @Test fun oversizedAndMalformedTaskStateCannotRestorePaths() {
        val memory = ChangesNavigationMemory()
        memory.bind("login", key).apply {
            selected = "a".repeat(4096)
            collapsed = (0 until 100).map { "folder-$it/" + "b".repeat(4000) }.toSet()
        }
        val encoded = memory.encode()
        assertTrue(encoded.isNotEmpty() && encoded.length <= 49_152)
        val restored = ChangesNavigationMemory.decode(encoded).bind("login", key)
        assertEquals("a".repeat(4096), restored.selected); assertTrue(restored.collapsed.size <= 32)
        for (invalid in listOf("{" , "x".repeat(50_000), JSONObject(encoded).put("version", 2).toString(),
            JSONObject(encoded).put("selected", 12).toString(), JSONObject(encoded).put("selected", "x\u0000y").toString())) {
            assertNull(ChangesNavigationMemory.decode(invalid).bind("login", key).selected)
        }
    }
}
