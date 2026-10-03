package io.github.docmorphic.cmuxapp

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeWhatsNewFileStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun markersAndCacheSurviveNewStoreAndUnrelatedOriginsAreRetained() {
        val root = temporary.newFolder()
        val first = NativeWhatsNewFileStore(root)
        assertTrue(first.write(mapOf("marker" to "new", "origin-a" to "{}", "origin-b" to "[]")))
        val second = NativeWhatsNewFileStore(root)
        assertEquals("new", second.read("marker"))
        assertTrue(second.write(mapOf("marker" to "newer", "origin-a" to null)))
        assertEquals("newer", first.read("marker")); assertNull(first.read("origin-a"))
        assertEquals("[]", first.read("origin-b"))
        assertEquals(listOf("notices-v1.json"), root.list()!!.toList())
    }
    @Test fun failedAtomicReplacementKeepsEntireOldGenerationAndRemovesTemporaryFile() {
        val root = temporary.newFolder()
        val first = NativeWhatsNewFileStore(root)
        assertTrue(first.write(mapOf("marker" to "old", "cache" to "old cache")))
        val failing = NativeWhatsNewFileStore(root) { _, _ -> throw IOException("disk failure") }
        assertFalse(failing.write(mapOf("marker" to "new", "cache" to "new cache")))
        val restarted = NativeWhatsNewFileStore(root)
        assertEquals("old", restarted.read("marker")); assertEquals("old cache", restarted.read("cache"))
        assertEquals(listOf("notices-v1.json"), root.list()!!.toList())
    }
    @Test fun blockedDirectoryCannotPretendToPersistAndCorruptFileRecovers() {
        val blocked = temporary.newFile()
        assertFalse(NativeWhatsNewFileStore(File(blocked, "child")).write(mapOf("marker" to "new")))
        val root = temporary.newFolder()
        File(root, "notices-v1.json").writeText("not json")
        val store = NativeWhatsNewFileStore(root)
        assertNull(store.read("marker"))
        assertTrue(store.write(mapOf("marker" to "fresh")))
        assertEquals("fresh", NativeWhatsNewFileStore(root).read("marker"))
    }
}
