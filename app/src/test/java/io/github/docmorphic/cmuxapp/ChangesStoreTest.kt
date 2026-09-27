package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChangesStoreTest {
    private fun files(vararg paths: String) = JSONObject().put("workspace_id", "ws")
        .put("files", JSONArray(paths.map { JSONObject().put("path", it) }))
    private fun diff(path: String, text: String = "new", truncated: Boolean = false) = JSONObject().put("path", path)
        .put("unified_diff", "@@ -1 +1 @@\n-old\n+$text\n").put("truncated", truncated)
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test fun lateCancelledListingCannotReplaceTheRefreshedWorkspaceSnapshot() = runBlocking {
        val scope = scope(); val release = CompletableDeferred<Unit>(); var calls = 0
        val store = ChangesStore(scope, "ws", {
            if (++calls == 1) { withContext(NonCancellable) { release.await() }; files("old") } else files("new")
        }, { path, _ -> diff(path) })
        try {
            val old = store.refresh(); store.refresh().join(); release.complete(Unit); old.join()
            assertEquals(listOf("new"), store.listing.value.snapshot!!.files.map { it.path })
            assertNull(store.listing.value.error)
        } finally { store.close(); scope.cancel() }
    }
    @Test fun lateCancelledPageCannotReplaceAnExplicitRefresh() = runBlocking {
        val scope = scope(); val release = CompletableDeferred<Unit>(); var calls = 0
        val store = ChangesStore(scope, "ws", { files("a") }, { path, _ ->
            if (++calls == 1) { withContext(NonCancellable) { release.await() }; diff(path, "stale") } else diff(path, "fresh")
        })
        try {
            store.refresh().join()
            val old = store.load("a")!!; store.load("a", force = true)!!.join()
            release.complete(Unit); old.join()
            assertEquals("fresh", store.page("a").value.document!!.hunks.single().lines.last().text)
            assertFalse(store.page("a").value.loading)
        } finally { store.close(); scope.cancel() }
    }
    @Test fun failedContinuationKeepsTheReadableDocumentAndRetriesTheSameBudget() = runBlocking {
        val scope = scope(); val budgets = mutableListOf<Int>(); var reject = true
        val store = ChangesStore(scope, "ws", { files("a") }, { path, budget ->
            budgets += budget
            if (budget > 6000 && reject) { reject = false; error("temporary failure") }
            diff(path, truncated = true)
        })
        try {
            store.refresh().join(); store.load("a")!!.join()
            val original = store.page("a").value.document
            store.load("a", more = true)!!.join()
            assertEquals(original, store.page("a").value.document)
            assertTrue(store.page("a").value.failedContinuation); assertEquals(6000, store.page("a").value.budget)
            store.load("a", more = true)!!.join()
            assertEquals(listOf(6000, 24000, 24000), budgets)
            assertTrue(store.page("a").value.ceiling); assertNull(store.page("a").value.error)
        } finally { store.close(); scope.cancel() }
    }
    @Test fun cacheIsBoundedAndRemovedFilesCannotBeRequestedAgain() = runBlocking {
        val scope = scope(); val paths = (0..10).map { "file$it" }; var snapshot = files(*paths.toTypedArray())
        val store = ChangesStore(scope, "ws", { snapshot }, { path, _ -> diff(path) })
        try {
            store.refresh().join(); paths.forEach { store.load(it)!!.join() }
            assertEquals(7, paths.count { store.page(it).value.document != null })
            snapshot = files(); store.refresh().join()
            assertNull(store.load("file0")); assertTrue(store.listing.value.snapshot!!.files.isEmpty())
        } finally { store.close(); scope.cancel() }
    }
    @Test fun notRepositoryIsDistinctFromTransportFailureAndCanRecover() = runBlocking {
        val scope = scope(); var code: String? = "not_a_repo"
        val store = ChangesStore(scope, "ws", { code?.let { throw MobileRpcException(it, "fixture") }; files("a") }, { path, _ -> diff(path) })
        try {
            store.refresh().join(); assertTrue(store.listing.value.notRepository)
            code = "disconnected"; store.refresh().join(); assertFalse(store.listing.value.notRepository); assertNotNull(store.listing.value.error)
            code = null; store.refresh().join(); assertNull(store.listing.value.error); assertEquals("a", store.listing.value.snapshot!!.files.single().path)
        } finally { store.close(); scope.cancel() }
    }
    @Test fun refreshedListingInvalidatesOldDiffEvenWhenPathIsUnchanged() = runBlocking {
        val scope = scope(); var version = "old"
        val store = ChangesStore(scope, "ws", { files("a") }, { path, _ -> diff(path, version) })
        try {
            store.refresh().join(); store.load("a")!!.join(); version = "new"
            store.refresh().join(); assertNull(store.page("a").value.document)
            store.load("a")!!.join(); assertEquals("new", store.page("a").value.document!!.hunks.single().lines.last().text)
        } finally { store.close(); scope.cancel() }
    }
    @Test fun explicitRefreshFailureDoesNotPresentTheOldDiffAsCurrent() = runBlocking {
        val scope = scope(); var reject = false
        val store = ChangesStore(scope, "ws", { files("a") }, { path, _ ->
            if (reject) error("repository unavailable") else diff(path)
        })
        try {
            store.refresh().join(); store.load("a")!!.join(); assertNotNull(store.page("a").value.document)
            reject = true; store.load("a", force = true)!!.join()
            assertNull(store.page("a").value.document); assertNotNull(store.page("a").value.error)
            assertFalse(store.page("a").value.failedContinuation)
        } finally { store.close(); scope.cancel() }
    }

}
