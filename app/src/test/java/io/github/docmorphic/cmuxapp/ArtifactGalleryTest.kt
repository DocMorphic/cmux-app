package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtifactGalleryTest {
    private fun item(name: String, kind: String = "text") = JSONObject().put("path", "/$name").put("kind", kind)
    private fun row(name: String) = ArtifactItem("/$name")
    private fun page(value: JSONObject, folders: Boolean = true) = ArtifactGalleryPage.read(value, "session", folders)
    private fun rows(vararg names: String) = JSONArray(names.map { item(it) })

    @Test fun lenientMetadataPreservesUnicodeAndUnknownTypesWithoutAcceptingRelativePaths() {
        val value = JSONObject().put("referenced", JSONArray().put(item("résumé 🌱.txt", "future"))
            .put(item("missing").put("exists", false).put("size", JSONObject.NULL))
            .put(item("stamp").put("modified_at", "2026-09-28T10:00:00Z"))
            .put(JSONObject().put("path", "relative.txt")).put(JSONObject().put("path", 17)))
        val snapshot = page(value).snapshot
        assertEquals(3, snapshot.referenced.size)
        assertEquals("résumé 🌱.txt", snapshot.referenced[0].displayName)
        assertEquals(ArtifactKind.BINARY, snapshot.referenced[0].kind)
        assertFalse(snapshot.referenced[1].exists); assertNull(snapshot.referenced[1].size)
        assertNotNull(snapshot.referenced[2].modifiedAt)
    }
    @Test fun folderCompatibilityAdjustsTotalsButKeepsCursorIdentity() {
        val value = JSONObject().put("created", JSONArray().put(item("folder", "directory")).put(item("file")))
            .put("created_total", 8).put("next_cursor", "opaque").put("generation", "g1")
        val snapshot = page(value, folders = false).snapshot
        assertEquals(listOf("/file"), snapshot.created.map { it.path }); assertEquals(7, snapshot.createdTotal)
        assertEquals("opaque", snapshot.nextCursor); assertEquals("g1", snapshot.generation)
    }
    @Test(expected = IllegalArgumentException::class) fun foreignSessionCannotSeedGallery() {
        page(JSONObject().put("session_id", "someone-else").put("referenced", rows("secret")))
    }
    @Test fun appendDeduplicatesAcrossSectionsAndHonorsOpaqueCursor() {
        val initial = ArtifactGallerySnapshot(created = listOf(row("created")), referenced = listOf(row("ref")))
        val next = page(JSONObject().put("created", rows("created", "new"))
            .put("attached", rows("new", "attachment")).put("referenced", rows("ref", "attachment", "last"))
            .put("next_cursor", "not-an-offset").put("generation", "g2"))
        val result = initial.append(next)
        assertEquals(listOf("/created", "/new", "/attachment", "/ref", "/last"), result.items.map { it.path })
        assertEquals("not-an-offset", result.nextCursor); assertEquals("g2", result.generation)
        assertEquals(initial, initial.append(next.copy(requiresPagingRestart = true)))
    }
    @Test fun refreshPoliciesProtectScrollAndMoveProvenanceOnlyWhenApplied() {
        val initial = ArtifactGallerySnapshot(referenced = listOf(row("old"), row("moved")), nextCursor = "old-cursor")
        val fresh = ArtifactGallerySnapshot(created = listOf(row("moved")), referenced = listOf(row("new")),
            nextCursor = "fresh-cursor", generation = "fresh")
        val deferred = initial.refresh(fresh, ArtifactRefreshPolicy.DEFER)
        assertEquals(initial.items, deferred.items); assertEquals("fresh-cursor", deferred.nextCursor)
        val preserved = initial.refresh(fresh, ArtifactRefreshPolicy.PRESERVE)
        assertTrue(preserved.created.isEmpty()); assertEquals(listOf("/old", "/moved", "/new"), preserved.referenced.map { it.path })
        val applied = initial.refresh(fresh, ArtifactRefreshPolicy.APPLY)
        assertEquals(listOf("/moved"), applied.created.map { it.path })
        assertEquals(listOf("/new", "/old"), applied.referenced.map { it.path })
        assertEquals(2, applied.referencedTotal)
    }
    @Test fun repeatedCursorStopsWithoutLosingRetryPosition() = runBlocking {
        var calls = 0
        val initial = ArtifactGallerySnapshot(referenced = listOf(row("a")), nextCursor = "repeat")
        val result = loadRemainingArtifacts(initial) {
            calls++; ArtifactGalleryPage(ArtifactGallerySnapshot(referenced = listOf(row("b")), nextCursor = "repeat"))
        }
        assertEquals(1, calls); assertEquals(listOf("/a", "/b"), result.snapshot.items.map { it.path })
        assertEquals("repeat", result.snapshot.nextCursor); assertFalse(result.reachedSafetyCap)
    }
    @Test fun staleCursorDiscardsPartialAccumulationAndReturnsOriginalSnapshot() = runBlocking {
        val initial = ArtifactGallerySnapshot(referenced = listOf(row("a")), nextCursor = "p1")
        val result = loadRemainingArtifacts(initial) { cursor ->
            if (cursor == "p1") ArtifactGalleryPage(ArtifactGallerySnapshot(referenced = listOf(row("b")), nextCursor = "p2"))
            else ArtifactGalleryPage(ArtifactGallerySnapshot(referenced = listOf(row("wrong"))), requiresPagingRestart = true)
        }
        assertTrue(result.requiresPagingRestart); assertEquals(initial, result.snapshot); assertFalse(result.reachedSafetyCap)
    }
    @Test fun safetyCapOnlyTruncatesReferencedRowsAndReportsEvenWhenLastPageHasNoCursor() = runBlocking {
        val initial = ArtifactGallerySnapshot(created = (1..8).map { row("created$it") }, nextCursor = "p1")
        val result = loadRemainingArtifacts(initial, maximumReferencedRows = 2) {
            ArtifactGalleryPage(ArtifactGallerySnapshot(referenced = (1..4).map { row("ref$it") }))
        }
        assertEquals(8, result.snapshot.created.size); assertEquals(2, result.snapshot.referenced.size)
        assertTrue(result.reachedSafetyCap); assertNull(result.snapshot.nextCursor)
    }
    @Test fun cancellationRejectsLatePageFromNonCooperativeTransport() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var published = false
        val job = launch {
            loadRemainingArtifacts(ArtifactGallerySnapshot(nextCursor = "p")) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                ArtifactGalleryPage(ArtifactGallerySnapshot(referenced = listOf(row("late"))))
            }
            published = true
        }
        entered.await(); job.cancel(); release.complete(Unit); job.join()
        assertFalse(published)
    }
    @Test fun directoryChildrenCannotEscapeParentAndKeepRealPosixNames() {
        val names = listOf("..", ".", "a/b", "", "bad\u0000name", "子 folder", "back\\slash", "file")
        val listing = ArtifactDirectoryListing.read(JSONObject().put("entries", JSONArray(names.map { name ->
            JSONObject().put("name", name).put("is_directory", name == "子 folder").put("size", 3)
        })).put("is_truncated", true), "/root/")
        assertEquals(listOf("/root/子 folder", "/root/back\\slash", "/root/file"), listing.entries.map { it.path })
        assertEquals(ArtifactKind.DIRECTORY, listing.entries.first().kind); assertTrue(listing.truncated)
    }
}
