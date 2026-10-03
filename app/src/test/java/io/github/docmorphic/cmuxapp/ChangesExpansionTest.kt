package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChangesExpansionTest {
    private val fingerprint = "stat:20:123:18446744073709551615:4:5"
    private fun document(raw: String, truncated: Boolean = false) = ChangesDiffDocument.read(JSONObject()
        .put("unified_diff", raw).put("truncated", truncated).put("content_fingerprint", fingerprint), "a")
    @Test fun leadingInnerAndTrailingExpansionHasCorrectOldAndNewCoordinates() {
        val doc = document("@@ -3 +3,2 @@\n same\n+added\n@@ -8 +9 @@\n-before\n+after\n")
        val gaps = ChangesGap.gaps(doc, 12)
        assertEquals(listOf(ChangesLineRange(1, 3), ChangesLineRange(5, 9), ChangesLineRange(10, 13)), gaps.map { it.range })
        var state = ChangesExpansion(current = ChangesCurrentFile((1..12).map { "line$it" }, fingerprint))
        gaps.forEach { state = state.reveal(it, ChangesExpandDirection.DOWN, null) }
        val rows = projectChanges(doc, ChangeKind.MODIFIED, state)
        val context = rows.filterIsInstance<ChangesDiffRowContent.Code>().filter { it.id.startsWith("context:") }
        assertEquals(listOf(1, 2, 5, 6, 7, 8, 10, 11, 12), context.map { it.line.newNumber })
        assertEquals(listOf(1, 2, 4, 5, 6, 7, 9, 10, 11), context.map { it.line.oldNumber })
        assertEquals("line10", context.first { it.line.newNumber == 10 }.line.text)
        assertTrue(context.all { it.hunk.isEmpty() })
    }
    @Test fun largeGapExpandsFromBothEdgesThenMergesWithoutDuplicates() {
        val gap = ChangesGap(1, ChangesLineRange(1, 351), 0, ChangesExpandDirection.entries)
        var state = ChangesExpansion().reveal(gap, ChangesExpandDirection.DOWN, null)
        state = state.reveal(gap, ChangesExpandDirection.UP, null)
        assertEquals(listOf(ChangesLineRange(101, 251)), state.hidden(gap))
        state = state.reveal(gap, ChangesExpandDirection.DOWN, null)
        assertEquals(listOf(ChangesLineRange(201, 251)), state.hidden(gap))
        state = state.reveal(gap, ChangesExpandDirection.UP, null)
        assertEquals(listOf(ChangesLineRange(1, 351)), state.revealed[1])
        assertTrue(state.hidden(gap).isEmpty())
    }
    @Test fun zeroCountHunksDeletedFilesAndTruncatedDiffsHaveNoFalseTrailingContext() {
        val doc = document("@@ -3,2 +2,0 @@\n-a\n-b\n")
        assertEquals(ChangesLineRange(1, 3), ChangesGap.gaps(doc, 4).first().range)
        assertTrue(projectChanges(doc, ChangeKind.DELETED).none { it is ChangesDiffRowContent.Expand })
        val truncated = doc.copy(truncated = true)
        assertTrue(ChangesGap.gaps(truncated, null).none { it.id == 1 })
        assertTrue(ChangesGap.gaps(document("@@ -1 +1 @@\n-a\n+b\n"), 1).isEmpty())
    }
    @Test fun cachedExpansionLoadsOnceAndRefreshInvalidatesItsContent() = runBlocking {
        var loads = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = store(scope, { fingerprint }) { loads++; ChangesCurrentFile(listOf("first", "second", "new", "last"), fingerprint) }
        try {
            store.refresh().join(); store.load("a")!!.join()
            store.expand("a", 0, ChangesExpandDirection.UP)!!.join()
            store.expand("a", 1, ChangesExpandDirection.DOWN)!!.join()
            assertEquals(1, loads)
            assertEquals(listOf("first", "second", "last"), store.page("a").value.rows.filterIsInstance<ChangesDiffRowContent.Code>().filter { it.id.startsWith("context:") }.map { it.line.text })
            store.load("a", force = true)!!.join()
            assertNull(store.page("a").value.expansion.current)
        } finally { store.close(); scope.cancel() }
    }
    @Test fun differentFileIdentityReloadsDiffWithoutPublishingMixedLines() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var version = fingerprint
        val store = store(scope, { version }) { version = "stat:21:124:2:4:5"; ChangesCurrentFile(listOf("WRONG"), version) }
        try {
            store.refresh().join(); store.load("a")!!.join()
            store.expand("a", 0, ChangesExpandDirection.UP)!!.join()
            withTimeout(2000) { while (store.page("a").value.loading) yield() }
            assertEquals(version, store.page("a").value.document!!.fingerprint)
            assertNull(store.page("a").value.expansion.current)
            assertFalse(store.page("a").value.rows.any { it is ChangesDiffRowContent.Code && it.line.text == "WRONG" })
        } finally { store.close(); scope.cancel() }
    }
    @Test fun lateExpansionCannotReplaceAnExplicitlyRefreshedDiff() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val store = store(scope, { fingerprint }) { withContext(NonCancellable) { release.await() }; ChangesCurrentFile(listOf("stale"), fingerprint) }
        try {
            store.refresh().join(); store.load("a")!!.join()
            val old = store.expand("a", 0, ChangesExpandDirection.UP)!!
            store.load("a", force = true)!!.join(); release.complete(Unit); old.join()
            assertNull(store.page("a").value.expansion.current)
            assertNull(store.page("a").value.expansion.pending)
        } finally { store.close(); scope.cancel() }
    }
    @Test fun recoverableFailuresRetryAndOversizedContentDisablesFurtherExpansion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var attempts = 0
        val store = store(scope, { fingerprint }) { if (++attempts == 1) error("disconnected") else throw ChangesContentTooLarge() }
        try {
            store.refresh().join(); store.load("a")!!.join()
            store.expand("a", 0, ChangesExpandDirection.UP)!!.join()
            assertEquals(0, store.page("a").value.expansion.failed)
            assertNotNull(store.page("a").value.document)
            store.expand("a", 0, ChangesExpandDirection.UP)!!.join()
            assertTrue(store.page("a").value.expansion.tooLarge)
            assertNull(store.expand("a", 0, ChangesExpandDirection.UP))
        } finally { store.close(); scope.cancel() }
    }
    private fun store(scope: CoroutineScope, version: () -> String, lines: suspend (String) -> ChangesCurrentFile) = ChangesStore(scope, "ws",
        { JSONObject().put("files", JSONArray().put(JSONObject().put("path", "a").put("status", "modified"))) },
        { _, _ -> JSONObject().put("unified_diff", "@@ -3 +3 @@\n-old\n+new\n").put("content_fingerprint", version()) }, lines)
}
