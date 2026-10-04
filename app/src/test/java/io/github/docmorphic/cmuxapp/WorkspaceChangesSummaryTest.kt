package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceChangesSummaryTest {
    private fun response(ids: List<String>, files: Int = 2) = JSONObject().put("summaries", JSONArray(ids.map {
        JSONObject().put("workspace_id", it).put("is_repo", true).put("files_changed", files).put("additions", 4).put("deletions", 1)
    }))
    @Test fun decoderDropsMalformedOrForeignRowsAndClearsEmptyOrNonRepositories() {
        val rows = response(listOf("a", "empty", "foreign")).getJSONArray("summaries")
        rows.getJSONObject(1).put("files_changed", 0)
        rows.put(JSONObject().put("workspace_id", "broken").put("is_repo", "true"))
        rows.put(JSONObject().put("workspace_id", "notRepo").put("is_repo", false).put("files_changed", 9))
        rows.put(JSONObject().put("workspace_id", "binary").put("is_repo", true).put("files_changed", 1).put("additions", "4").put("deletions", -9))
        val parsed = parseWorkspaceChangesSummaries(JSONObject().put("summaries", rows), setOf("a", "empty", "broken", "notRepo", "binary"))
        assertEquals(setOf("a", "empty", "notRepo", "binary"), parsed.keys)
        assertNull(parsed["empty"]); assertNull(parsed["notRepo"])
        assertEquals(WorkspaceChangesChip(1, 0, 0), parsed["binary"])
        assertEquals("1 file", parsed["binary"]!!.fileText)
    }
    @Test fun uniqueIdsAreBatchedAndIdleRefreshStops() = runTest {
        val requests = mutableListOf<List<String>>()
        val session = WorkspaceChangesSummarySession(backgroundScope, { true }, { ids, _ -> requests += ids; response(ids) }, {}, { testScheduler.currentTime })
        session.retain((0..129).map { "w$it" } + listOf("w0", ""))
        advanceTimeBy(251); runCurrent()
        assertEquals(listOf(64, 64, 2), requests.map { it.size })
        advanceTimeBy(60_000); runCurrent()
        val settled = requests.size
        assertTrue(settled in 3..9)
        advanceTimeBy(120_000); runCurrent()
        assertEquals("No perpetual Git polling on an idle Mac", settled, requests.size)
        session.close()
    }
    @Test fun recentEventsReuseFreshEntriesAndForceBypassesReuse() = runTest {
        val requests = mutableListOf<Pair<List<String>, Boolean>>()
        val session = WorkspaceChangesSummarySession(backgroundScope, { true }, { ids, force -> requests += ids to force; response(ids) }, {}, { testScheduler.currentTime })
        session.retain(listOf("a", "b")); advanceTimeBy(251); runCurrent()
        session.request(listOf("a")); advanceTimeBy(251); runCurrent()
        assertEquals(1, requests.size)
        session.request(listOf("b"), force = true); advanceTimeBy(251); runCurrent()
        assertEquals(listOf("b") to true, requests.last())
        advanceTimeBy(16_000); runCurrent()
        assertTrue(requests.size > 2)
        // Separate expiries may still have their bounded minimum-delay trailing passes queued.
        advanceTimeBy(60_000); runCurrent()
        val count = requests.size
        assertTrue(count in 3..5)
        advanceTimeBy(90_000); runCurrent()
        assertEquals(count, requests.size)
        session.close()
    }
    @Test fun eventsCoalesceDuringFetchAndDeletedIdsCannotReappear() = runTest {
        val gate = CompletableDeferred<Unit>()
        val requests = mutableListOf<List<String>>()
        var published = emptyMap<String, WorkspaceChangesChip>()
        val session = WorkspaceChangesSummarySession(backgroundScope, { true }, { ids, _ ->
            requests += ids; if (requests.size == 1) gate.await(); response(ids)
        }, { published = it }, { testScheduler.currentTime })
        session.retain(listOf("a", "b")); advanceTimeBy(251); runCurrent()
        session.request(listOf("a"), force = true); session.request(listOf("b"))
        session.retain(listOf("a")); gate.complete(Unit); runCurrent()
        assertEquals(listOf(listOf("a", "b"), listOf("a")), requests)
        assertEquals(setOf("a"), published.keys)
        session.close()
    }
    @Test fun failedRefreshKeepsLastGoodValueAndOwnerRetirementDropsLateResponse() = runTest {
        var admitted = true; var fail = false; var late = false
        val gate = CompletableDeferred<Unit>()
        var published = emptyMap<String, WorkspaceChangesChip>()
        val session = WorkspaceChangesSummarySession(backgroundScope, { admitted }, { ids, _ ->
            if (fail) error("Fixture summary failure")
            if (late) gate.await()
            response(ids, if (late) 99 else 2)
        }, { published = it }, { testScheduler.currentTime })
        session.retain(listOf("a")); advanceTimeBy(251); runCurrent()
        fail = true; session.request(force = true); advanceTimeBy(251); runCurrent()
        assertEquals(2L, published["a"]!!.files)
        fail = false; late = true; session.request(force = true); advanceTimeBy(251); runCurrent()
        admitted = false; gate.complete(Unit); runCurrent()
        assertEquals(2L, published["a"]!!.files)
        session.close()
    }
    @Test fun reusedSnapshotDoesNotActLikeWorkspaceActivity() = runTest {
        var requests = 0
        val session = WorkspaceChangesSummarySession(backgroundScope, { true }, { ids, _ -> requests++; response(ids) }, {}, { testScheduler.currentTime })
        session.retain(listOf("a")); advanceTimeBy(60_000); runCurrent()
        val idleCount = requests
        repeat(4) { session.retain(listOf("a")); advanceTimeBy(30_000); runCurrent() }
        assertEquals(idleCount, requests)
        session.retain(listOf("a", "b")); advanceTimeBy(251); runCurrent()
        assertEquals(idleCount + 1, requests)
        session.close()
    }
    @Test fun oneBatchDeadlineDoesNotCancelOtherEligibleWorkspaces() = runTest {
        var requests = 0
        var published = emptyMap<String, WorkspaceChangesChip>()
        val session = WorkspaceChangesSummarySession(backgroundScope, { true }, { ids, _ ->
            requests++
            if (requests == 1) withTimeout(5) { awaitCancellation() }
            response(ids)
        }, { published = it }, { testScheduler.currentTime })
        session.retain((0..64).map { "w$it" }); advanceTimeBy(1000); runCurrent()
        assertEquals(2, requests)
        assertEquals(setOf("w64"), published.keys)
        session.close()
    }
    @Test fun summaryRpcBoundsAndForcedWireFlag() = runBlocking {
        val wire = PoolTestTransport()
        MobileRpcClient(wire, { "fixture" }).use { client ->
            client.connect()
            val pending = async { client.workspaceChangesSummaries(listOf("a", "a", ""), true) }
            val request = withTimeout(2000) { wire.sent.receive() }
            assertEquals("mobile.workspace.changes.summary", request.getString("method"))
            assertEquals("[\"a\"]", request.getJSONObject("params").getJSONArray("workspace_ids").toString())
            assertTrue(request.getJSONObject("params").getBoolean("force"))
            wire.answer(request); pending.await()
            assertTrue(runCatching { client.workspaceChangesSummaries(emptyList()) }.isFailure)
            assertTrue(runCatching { client.workspaceChangesSummaries((0..64).map { "$it" }) }.isFailure)
        }
    }
}
