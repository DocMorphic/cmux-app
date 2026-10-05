package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtifactGalleryStoreTest {
    private val terminal = ArtifactAuthorization.Terminal("workspace", "surface")
    private val all = ArtifactCapabilities(true, true, true, true)
    private fun scan(session: String? = "session") = JSONObject().put("session_id", session)
        .put("artifacts", JSONArray().put(JSONObject().put("path", "/visible")))
    private fun page(vararg names: String, cursor: String? = null, generation: String = "g1") = JSONObject()
        .put("session_id", "session").put("generation", generation).put("next_cursor", cursor)
        .put("referenced", JSONArray(names.map { JSONObject().put("path", "/$it") }))
    private fun parent() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test fun replacementKeepsRowsSessionQueryAndCursorButRejectsLateOldPage() = runBlocking {
        val scope = parent(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val store = ArtifactGalleryStore(scope, terminal, ArtifactRpc(all) { method, params ->
            if (method.endsWith("scan")) scan() else if (params.has("cursor")) {
                entered.complete(Unit); withContext(NonCancellable) { release.await() }; page("late")
            } else page("first", cursor = "next")
        })
        try {
            store.initialize().join(); store.isAtTopOrFits = false
            val snapshot = store.session.value.snapshot
            val pending = store.loadMore()!!; entered.await()
            store.replaceConnection(ArtifactRpc(all) { method, params ->
                calls += method
                assertEquals("session", params.getString("session_id"))
                assertEquals("next", params.getString("cursor")); page("second")
            })
            release.complete(Unit); pending.join()
            assertSame(snapshot, store.session.value.snapshot); assertTrue(calls.isEmpty())
            assertFalse(store.session.value.loadingMore); assertNotNull(store.session.value.error)
            assertEquals(ArtifactAuthorization.Session("session"), store.sessionAuthorization.value)
            assertFalse(store.isAtTopOrFits)
            store.loadMore()!!.join()
            assertEquals(listOf("/first", "/second"), store.session.value.snapshot!!.items.map { it.path })
            assertEquals(1, calls.size)
        } finally { release.complete(Unit); store.close(); scope.cancel() }
    }

    @Test fun interruptedInitialScanCanResolveSessionOnExplicitRefresh() = runBlocking {
        val scope = parent(); val entered = CompletableDeferred<Unit>()
        val store = ArtifactGalleryStore(scope, terminal, ArtifactRpc(all) { _, _ -> entered.complete(Unit); awaitCancellation() })
        try {
            store.initialize(); entered.await(); store.connectionLost()
            assertFalse(store.inView.value.loading); assertNotNull(store.inView.value.error)
            store.replaceConnection(ArtifactRpc(all) { method, _ -> if (method.endsWith("scan")) scan() else page("recovered") })
            assertNull(store.inView.value.scan)
            store.refreshInView().join()
            assertEquals(ArtifactAuthorization.Session("session"), store.sessionAuthorization.value)
            assertEquals("/recovered", store.session.value.snapshot!!.items.single().path)
        } finally { store.close(); scope.cancel() }
    }

    @Test fun failedInViewRefreshKeepsTheScanThatAdmittedTheOpenPreview() = runBlocking {
        val scope = parent(); var fail = false
        val store = ArtifactGalleryStore(scope, terminal, ArtifactRpc(all) { method, _ ->
            if (fail) error("Mac disconnected")
            if (method.endsWith("scan")) scan() else page("file")
        })
        try {
            store.initialize().join(); val original = store.inView.value.scan
            fail = true; store.refreshInView().join()
            assertSame(original, store.inView.value.scan)
            assertEquals(ArtifactAuthorization.Session("session"), store.sessionAuthorization.value)
            assertFalse(store.inView.value.loading); assertNotNull(store.inView.value.error)
        } finally { store.close(); scope.cancel() }
    }

    @Test fun initialScanBindsSessionButDirectTerminalAuthorizationStaysDistinct() = runBlocking {
        val scope = parent()
        val store = ArtifactGalleryStore(scope, terminal, ArtifactRpc(all) { method, _ ->
            if (method.endsWith("scan")) scan() else page("session-file")
        })
        try {
            store.initialize().join()
            assertEquals(listOf("/visible"), store.inView.value.scan!!.items.map { it.path })
            assertEquals(listOf("/session-file"), store.session.value.snapshot!!.items.map { it.path })
            assertEquals(ArtifactAuthorization.Session("session"), store.sheetAuthorization())
            assertEquals(terminal, store.terminal)
        } finally { store.close(); scope.cancel() }
    }
    @Test fun unsupportedGalleryOrAbsentSessionKeepsOnlyInView() = runBlocking {
        for (supported in listOf(true, false)) {
            val scope = parent(); var requests = 0
            val store = ArtifactGalleryStore(scope, terminal, ArtifactRpc(all.copy(gallery = supported)) { method, _ ->
                requests++; assertTrue(method.endsWith("scan")); scan(if (supported) null else "session")
            })
            try {
                store.initialize().join(); assertEquals(1, requests)
                assertNull(store.sessionAuthorization.value); assertNull(store.session.value.snapshot)
                assertEquals(terminal, store.sheetAuthorization())
            } finally { store.close(); scope.cancel() }
        }
    }
    @Test fun replacedSearchIgnoresLateNonCooperativeReplyAndBlankRestoresSessionSnapshot() = runBlocking {
        val scope = parent(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val store = ArtifactGalleryStore(scope, terminal, ArtifactRpc(all) { method, params ->
            if (method.endsWith("scan")) scan() else when (params.optString("query")) {
                "old" -> { entered.complete(Unit); withContext(NonCancellable) { release.await() }; page("old") }
                "new" -> page("new")
                else -> page("session-file")
            }
        }, searchDebounceMillis = 0)
        try {
            store.initialize().join()
            val old = store.setQuery("old")!!; entered.await(); store.setQuery("new")!!.join()
            release.complete(Unit); old.join()
            assertEquals(listOf("/new"), store.search.value.snapshot!!.items.map { it.path })
            assertEquals("new", store.query.value)
            store.setQuery("  ")
            assertNull(store.search.value.snapshot); assertEquals(listOf("/session-file"), store.session.value.snapshot!!.items.map { it.path })
        } finally { release.complete(Unit); store.close(); scope.cancel() }
    }
    @Test fun debounceSendsOnlyLatestWholeSessionQuery() = runBlocking {
        val scope = parent(); val queries = mutableListOf<String>()
        val store = ArtifactGalleryStore(scope, terminal, ArtifactRpc(all) { method, params ->
            if (method.endsWith("scan")) scan() else {
                if (params.has("query")) queries += params.getString("query")
                page("result")
            }
        }, searchDebounceMillis = 25)
        try {
            store.initialize().join(); store.setQuery("r"); store.setQuery("re"); store.setQuery(" report ")!!.join()
            assertEquals(listOf("report"), queries)
        } finally { store.close(); scope.cancel() }
    }
    @Test fun failedNextPageRetainsReadableRowsAndRetriesSameCursor() = runBlocking {
        val scope = parent(); var reject = true; val cursors = mutableListOf<String>()
        val store = ArtifactGalleryStore(scope, terminal, ArtifactRpc(all) { method, params ->
            if (method.endsWith("scan")) scan() else if (!params.has("cursor")) page("first", cursor = "next") else {
                cursors += params.getString("cursor")
                if (reject) { reject = false; error("temporary failure") }
                page("second")
            }
        })
        try {
            store.initialize().join(); store.loadMore()!!.join()
            assertEquals(listOf("/first"), store.session.value.snapshot!!.items.map { it.path })
            assertEquals("next", store.session.value.snapshot!!.nextCursor); assertNotNull(store.session.value.error)
            assertFalse(store.session.value.loadingMore)
            store.loadMore()!!.join()
            assertEquals(listOf("next", "next"), cursors)
            assertEquals(listOf("/first", "/second"), store.session.value.snapshot!!.items.map { it.path })
            assertNull(store.session.value.error)
        } finally { store.close(); scope.cancel() }
    }
    @Test fun staleCursorRebasesWhileScrolledAndDefersNewRowsUntilApplied() = runBlocking {
        val scope = parent(); var fresh = false
        val store = ArtifactGalleryStore(scope, terminal, ArtifactRpc(all) { method, params ->
            if (method.endsWith("scan")) scan() else if (params.has("cursor")) {
                fresh = true; page().put("requires_paging_restart", true)
            } else if (fresh) page("new", cursor = "fresh-cursor", generation = "g2") else page("old", cursor = "stale")
        })
        try {
            store.initialize().join(); store.isAtTopOrFits = false; store.loadMore()!!.join()
            assertEquals(listOf("/old"), store.session.value.snapshot!!.items.map { it.path })
            assertEquals("fresh-cursor", store.session.value.snapshot!!.nextCursor)
            assertEquals(1, store.pendingNewFiles.value)
            store.applyPending()
            assertEquals(listOf("/new", "/old"), store.session.value.snapshot!!.items.map { it.path })
            assertEquals(0, store.pendingNewFiles.value)
        } finally { store.close(); scope.cancel() }
    }
    @Test fun liveRefreshWaitsForReaderButAppliesImmediatelyAtTop() = runBlocking {
        val scope = parent(); var version = 1
        val store = ArtifactGalleryStore(scope, terminal, ArtifactRpc(all) { method, _ ->
            if (method.endsWith("scan")) scan() else page("v$version", generation = "g$version")
        })
        try {
            store.initialize().join(); store.isAtTopOrFits = false; version = 2; store.refreshLive()!!.join()
            assertEquals(listOf("/v1"), store.session.value.snapshot!!.items.map { it.path }); assertEquals(1, store.pendingNewFiles.value)
            store.isAtTopOrFits = true; version = 3; store.refreshLive()!!.join()
            assertEquals(listOf("/v3", "/v1"), store.session.value.snapshot!!.items.map { it.path }); assertEquals(0, store.pendingNewFiles.value)
        } finally { store.close(); scope.cancel() }
    }
    @Test fun closedSheetRejectsLateScanAndDoesNotStartSessionRead() = runBlocking {
        val scope = parent(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val store = ArtifactGalleryStore(scope, terminal, ArtifactRpc(all) { _, _ ->
            calls++; entered.complete(Unit); withContext(NonCancellable) { release.await() }; scan()
        })
        try {
            val job = store.initialize(); entered.await(); store.close(); release.complete(Unit); job.join()
            assertNull(store.sessionAuthorization.value); assertNull(store.inView.value.scan)
            assertEquals(1, calls); assertNull(store.session.value.snapshot)
        } finally { release.complete(Unit); store.close(); scope.cancel() }
    }
    @Test fun refreshedFirstPageWinsAgainstLatePagination() = runBlocking {
        val scope = parent(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var fresh = false
        val store = ArtifactGalleryStore(scope, terminal, ArtifactRpc(all) { method, params ->
            if (method.endsWith("scan")) scan() else if (params.has("cursor")) {
                entered.complete(Unit); withContext(NonCancellable) { release.await() }; page("late")
            } else if (fresh) page("fresh") else page("first", cursor = "next")
        })
        try {
            store.initialize().join(); val old = store.loadMore()!!; entered.await()
            fresh = true; store.refreshSession()!!.join(); release.complete(Unit); old.join()
            assertEquals(listOf("/fresh"), store.session.value.snapshot!!.items.map { it.path })
            assertFalse(store.session.value.loadingMore)
        } finally { release.complete(Unit); store.close(); scope.cancel() }
    }
    @Test fun lateLiveRefreshCannotRollbackGenerationAfterCursorRecovery() = runBlocking {
        val scope = parent(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var firstPages = 0
        val cursorEntered = CompletableDeferred<Unit>(); val cursorRelease = CompletableDeferred<Unit>()
        val store = ArtifactGalleryStore(scope, terminal, ArtifactRpc(all) { method, params ->
            if (method.endsWith("scan")) scan() else if (params.has("cursor")) {
                cursorEntered.complete(Unit); cursorRelease.await(); page().put("requires_paging_restart", true)
            }
            else when (++firstPages) {
                1 -> page("initial", cursor = "stale", generation = "g1")
                2 -> { entered.complete(Unit); withContext(NonCancellable) { release.await() }; page("older", generation = "g2") }
                else -> page("newest", generation = "g3")
            }
        })
        try {
            store.initialize().join()
            // Start pagination first so the refresh observes the same request revision.
            // A delayed cursor response lets the live first-page request start before recovery.
            val paging = store.loadMore()!!; cursorEntered.await()
            val live = store.refreshLive()!!; entered.await()
            cursorRelease.complete(Unit); paging.join(); release.complete(Unit); live.join()
            assertEquals("g3", store.session.value.snapshot!!.generation)
            assertFalse(store.session.value.snapshot!!.items.any { it.path == "/older" })
        } finally { cursorRelease.complete(Unit); release.complete(Unit); store.close(); scope.cancel() }
    }
}
