package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.zip.GZIPInputStream

class TaskDirectoriesTest {
    private fun entry(name: String, path: String = "/repo/$name") = JSONObject().put("name", name).put("path", path)
        .put("is_hidden", false).put("is_package", false).put("is_symbolic_link", false).put("is_readable", true)
    private fun page(names: List<String>, offset: Long = 0, limit: Int = names.size.coerceAtLeast(1), total: Long = names.size.toLong(), path: String = "/repo") =
        JSONObject().put("current_path", path).put("parent_path", if (path == "/") JSONObject.NULL else "/")
            .put("entries", JSONArray(names.map { entry(it, if (path == "/") "/$it" else "$path/$it") }))
            .put("offset", offset).put("limit", limit).put("total_count", total)
            .put("next_offset", (offset + names.size).takeIf { it < total })

    @Test fun wireRejectsTruncatedUnsortedDuplicateAndOversizedPages() {
        assertEquals(2, TaskDirectoryPage.read(page(listOf("a", "b"))).entries.size)
        for (bad in listOf(page(listOf("a"), limit = 2, total = 2), page(listOf("b", "a")), page(listOf("a", "a")),
            page(listOf("a")).put("next_offset", 1), page(listOf("a")).put("offset", "0"),
            page(listOf("a")).put("parent_path", JSONObject.NULL), page(List(101) { "$it" }, limit = 101),
            page(listOf("a")).put("limit", 1.5), page(listOf("a")).put("total_count", -1))) {
            assertThrows(Exception::class.java) { TaskDirectoryPage.read(bad) }
        }
        val badBoolean = entry("a").put("is_readable", "true")
        assertThrows(Exception::class.java) { TaskDirectoryEntry.read(badBoolean) }
        for (badName in listOf("", ".", "..", "a/b", "a\u0000b", "中".repeat(342)))
            assertThrows(Exception::class.java) { TaskDirectoryEntry.read(entry(badName)) }
    }

    @Test fun pathsAndPagesKeepBytewiseUnicodeAndSixtyFourBitCounts() {
        val paths = listOf("/repo/cafe\u0301", "/repo/café", "/repo/\uE000", "/repo/😀")
        val decoded = TaskDirectoryPage.read(page(paths.map { it.substringAfterLast('/') }))
        assertEquals(paths, decoded.entries.map { it.path })
        val large = TaskDirectoryPage.read(page(listOf("last"), offset = Long.MAX_VALUE - 1, limit = 1, total = Long.MAX_VALUE))
        assertNull(large.next)
        assertTrue(TaskDirectoryPaths.browsable("~/my path")); assertFalse(TaskDirectoryPaths.browsable("relative"))
        assertFalse(TaskDirectoryPaths.browsable("/" + "中".repeat(1366)))
        assertFalse(TaskDirectoryPaths.browsable("/nul\u0000path"))
        assertEquals("/a/c", TaskDirectoryPaths.normalizedAbsolute("//a/./b/../c/"))
        assertEquals(listOf("~", "~/a", "~/a/b"), TaskDirectoryPaths.ancestry(" ~/a//b/ "))
        assertEquals(12, TaskDirectoryPaths.ancestry("/" + (1..30).joinToString("/")).size)
    }

    @Test fun browserDiscardsStaleResponsesAndPreservesEarlierPageOnRejectedAppend() {
        val initial = TaskDirectoryBrowse().navigate("/repo")
        val old = initial.pending!!
        val moved = initial.navigate("/another")
        assertEquals(moved, moved.receive(old, TaskDirectoryPage.read(page(emptyList()))))
        val loaded = initial.receive(old, TaskDirectoryPage.read(page(listOf("a", "b"), limit = 2, total = 4)))
        val loading = loaded.next(); val append = loading.pending!!
        val changedTotal = loading.receive(append, TaskDirectoryPage.read(page(listOf("c", "d"), offset = 2, limit = 2, total = 5)))
        assertNotNull(changedTotal.error); assertEquals(loaded.snapshot, changedTotal.snapshot)
        val retry = changedTotal.retry(); assertNotEquals(append, retry.pending)
        val complete = retry.receive(retry.pending!!, TaskDirectoryPage.read(page(listOf("c", "d"), offset = 2, limit = 2, total = 4)))
        assertEquals(listOf("a", "b", "c", "d"), complete.snapshot!!.entries.map { it.name }); assertNull(complete.snapshot.next)
        val duplicate = loading.receive(append, TaskDirectoryPage.read(page(listOf("b", "c"), offset = 2, limit = 2, total = 4)))
        assertNotNull(duplicate.error)
        val wrongOffset = initial.receive(old, TaskDirectoryPage.read(page(listOf("b"), offset = 1, limit = 1, total = 2)))
        assertNotNull(wrongOffset.error)
    }

    @Test fun indexedSearchAcceptsLegacyMetadataButCannotClaimCompleteFilesystemCoverage() {
        val legacy = TaskDirectorySearch.read(JSONObject().put("directories", JSONArray(listOf("/a", " ", "中".repeat(2000), "/b"))))
        assertEquals(listOf("/a", "/b"), legacy.paths); assertNotNull(legacy.status)
        val limited = JSONObject().put("directories", JSONArray((1..70).map { "/$it" })).put("search_scope", "all_indexed_volumes")
            .put("gathering_complete", true).put("filesystem_complete", false).put("indexed_match_count", 99)
        assertEquals(64, TaskDirectorySearch.read(limited).paths.size); assertTrue(TaskDirectorySearch.read(limited).truncated)
        assertThrows(Exception::class.java) { TaskDirectorySearch.read(JSONObject(limited.toString()).put("filesystem_complete", true)) }
        assertThrows(Exception::class.java) { TaskDirectorySearch.read(JSONObject(limited.toString()).put("indexed_match_count", -1)) }
        assertThrows(Exception::class.java) { TaskDirectorySearch.read(JSONObject(limited.toString()).put("search_scope", "invented")) }
    }

    @Test fun suggestionsMatchPinnedSwiftReference() {
        val source = javaClass.getResourceAsStream("/tasks/ios-directories.json.gz")!!
        val root = GZIPInputStream(source).bufferedReader().use { JSONObject(it.readText()) }
        val raw = root.getJSONArray("candidates")
        val candidates = (0 until raw.length()).map { raw.getJSONObject(it).let { row ->
            TaskDirectoryCandidate(row.getString("path"), TaskDirectorySource.entries[row.getInt("source")],
                row.opt("context") as? String, (row.opt("time") as? Number)?.toLong()?.times(1000), row.getLong("uses")) } }
        val index = TaskDirectorySuggestions(candidates, root.getLong("now") * 1000)
        val cases = root.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val row = cases.getJSONObject(i); val expected = row.getJSONArray("expected")
            assertEquals("query=" + row.getString("query"), (0 until expected.length()).map { expected.getString(it) },
                index.suggestions(row.getString("query"), row.getInt("limit")).map { it.path })
        }
    }

    @Test fun titleAndGroupAffectRequestIdentityWithoutSplittingGraphemes() {
        val longPrompt = "👩🏽‍💻".repeat(59) + "e\u0301🇩🇪tail"
        val generated = TaskCommand.parameters(TaskCommand.Agent.SHELL, longPrompt, "/repo", UUID.randomUUID())
        assertEquals("👩🏽‍💻".repeat(59) + "e\u0301", generated.getString("title"))
        val explicit = TaskCommand.parameters(TaskCommand.Agent.SHELL, "prompt", "/repo", UUID.randomUUID(), workspaceName = "  Name 中  ", groupId = "group-one")
        assertEquals("Name 中", explicit.getString("title")); assertEquals("group-one", explicit.getString("group_id"))
        val identity = TaskSubmissionIdentity(); val sent = identity.resolve("mac", explicit); identity.submitted("mac", sent)
        assertNotEquals(sent.getString("operation_id"), identity.resolve("mac", JSONObject(explicit.toString()).put("group_id", "group-two")).getString("operation_id"))
        assertEquals(sent.getString("operation_id"), identity.resolve("mac", explicit).getString("operation_id"))
    }

    @Test fun unresolvedGroupCannotSilentlyBecomeUngrouped() {
        val groups = listOf(NativeGroup("one", "One", false, false))
        assertFalse(TaskGroupSelection("one", groups, true, false).valid)
        assertTrue(TaskGroupSelection("one", groups, true, true).valid)
        assertTrue(TaskGroupSelection("one", emptyList(), true, true).missing)
        assertTrue(TaskGroupSelection("one", groups, false, true).missing)
        assertTrue(TaskGroupSelection(null, groups, false, false).valid)
        assertTrue(TaskGroupSelection("one", groups + groups, true, true).missing)
    }

    @Test fun disconnectedGroupsStayPendingUntilAnAuthoritativeHandshake() {
        val cached = listOf(NativeGroup("one", "One", false, false))
        for (loaded in listOf(false, true)) {
            val offline = TaskGroupSelection("one", cached, null, loaded)
            assertTrue(offline.pending)
            assertFalse(offline.missing)
            assertFalse(offline.valid)
            assertTrue(offline.visible)
        }
        assertTrue(TaskGroupSelection(null, cached, null, false).valid)
        assertTrue(TaskGroupSelection("one", cached, true, true).valid)
        assertTrue(TaskGroupSelection("one", emptyList(), true, true).missing)
        assertTrue(TaskGroupSelection("one", cached, false, false).missing)
    }

    @Test fun macRetargetPreservesDraftAndPinsRetryRecoveryToOriginalMac() {
        val drafts = TaskDrafts(); val id = UUID.randomUUID().toString()
        val editor = drafts.begin(id, "first", "First", "/first")
        val request = TaskCommand.parameters(TaskCommand.Agent.SHELL, "Prompt", "/typed", UUID.randomUUID())
        val retired = JSONObject(request.toString()).put("operation_id", UUID.randomUUID().toString())
        drafts.edit(editor) { it.copy(prompt = "Prompt", workspaceName = "Named", groupId = "old-group", directory = "/typed", didEditDirectory = true,
            lastRequest = retired.toString(), completedRequest = request.toString()) }
        val moved = drafts.state.value.getValue(id).onMac("second", "Second", "/second")
        drafts.retarget(editor, moved)
        assertFalse(drafts.isCurrent(editor)); assertNull(moved.groupId); assertEquals("/typed", moved.directory)
        val restored = TaskDrafts(drafts.saved()).state.value.getValue(id)
        assertEquals("second", restored.origin); assertEquals("first", restored.lastRequestOrigin); assertEquals("first", restored.completedOrigin)
        val recovery = TaskCompletedRecovery(restored.completedOrigin!!, restored.completedRequest!!)
        assertFalse(recovery.appliesTo("second", request)); assertTrue(recovery.appliesTo("first", request))
        assertThrows(IllegalStateException::class.java) { drafts.edit(editor) { it.copy(prompt = "late") } }
        assertFalse(restored.copy(prompt = "", lastRequest = null, completedRequest = null).isEmpty)
    }
}
