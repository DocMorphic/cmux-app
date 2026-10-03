package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class SshHostEditTest {
    @Test fun concurrentRouteChangeAndDeletionRejectOldEditorWithoutWriting() {
        var writes = 0
        val store = SshHostStore({ null }, { writes++ })
        val original = SshHostRecord(name = "Original", endpoint = SshEndpoint("fixture.invalid", username = "test"))
        store.saveEdit(null, original)
        val changed = original.copy(endpoint = original.endpoint.copy(port = 2222))
        store.upsert(changed)
        assertThrows(SshHostEditConflict::class.java) { store.saveEdit(original, original.copy(name = "Stale")) }
        assertEquals(2, writes); assertEquals(changed, store.state.value.host(original.id))
        store.delete(original.id)
        assertThrows(SshHostEditConflict::class.java) { store.saveEdit(original, original.copy(name = "Recreated")) }
        assertEquals(3, writes); assertNull(store.state.value.host(original.id))
    }
    @Test fun duplicateNewEditorCannotReplaceExistingIdButCurrentBaseCanRename() {
        val store = SshHostStore({ null }, {})
        val original = SshHostRecord(name = "Original", endpoint = SshEndpoint("fixture.invalid", username = "test"))
        store.saveEdit(null, original)
        assertThrows(SshHostEditConflict::class.java) { store.saveEdit(null, original.copy(name = "Collision")) }
        val plan = store.dialPlan(original.id)
        store.saveEdit(original, original.copy(name = "Renamed"))
        assertEquals("Renamed", store.state.value.host(original.id)!!.name)
        assertTrue(store.isCurrent(plan))
    }
}
