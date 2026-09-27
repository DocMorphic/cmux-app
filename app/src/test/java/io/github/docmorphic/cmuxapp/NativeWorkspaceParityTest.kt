package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Expected results are produced by unmodified upstream Swift, not this Kotlin implementation. */
class NativeWorkspaceParityTest {
    private val golden = JSONObject(java.util.zip.GZIPInputStream(javaClass.getResourceAsStream("/workspaces/ios-moves.json.gz")!!).bufferedReader().use { it.readText() })
    private fun JSONObject.nullable(key: String) = if (isNull(key)) null else getString(key)
    private fun JSONObject.intent() = NativeWorkspaceMove(nullable("group_id"), nullable("before_workspace_id"), getBoolean("move_group"))
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun source(fixture: JSONObject) = NativeFeedSource(
        NativeCredentialStore.PairedMac("test", "Test Mac", "test"),
        workspaces = parseWorkspaces(fixture), groups = parseGroups(fixture))
    private fun order(rows: JSONArray) = rows.objects().map {
        NativeWorkspaceOrderEntry(it.getString("id"), it.nullable("group_id"), it.getBoolean("is_pinned"))
    }
    private fun signature(rows: List<NativeWorkspace>) = rows.map { NativeWorkspaceOrderEntry(it.id, it.groupId, it.isPinned) }

    @Test fun hierarchyMatchesPinnedIosSpatialProjection() {
        assertEquals("4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0", golden.getString("upstream"))
        for (fixture in golden.getJSONArray("cases").objects()) {
            val name = fixture.getString("name")
            val entries = workspaceHierarchy(source(fixture))
            val expected = fixture.getJSONArray("items").objects()
            assertEquals(name, expected.size, entries.size)
            expected.zip(entries).forEach { (item, entry) ->
                when (item.getString("kind")) {
                    "group" -> {
                        assertTrue("$name: $item", entry is WorkspaceListEntry.Header)
                        entry as WorkspaceListEntry.Header
                        assertEquals(name, item.getString("id"), entry.group.id)
                        assertEquals(name, item.getBoolean("has_unread"), entry.hasUnread)
                        assertEquals(name, if (item.isNull("unread_count")) null else item.getLong("unread_count"), entry.unread.count)
                        assertEquals(name, item.nullable("anchor"), entry.group.liveAnchorWorkspaceId)
                    }
                    "workspace" -> {
                        assertTrue("$name: $item", entry is WorkspaceListEntry.Workspace)
                        entry as WorkspaceListEntry.Workspace
                        assertEquals(name, item.getString("id"), entry.workspace.id)
                        assertEquals(name, item.getBoolean("indented"), entry.indented)
                        assertEquals(name, item.getBoolean("has_unread"), entry.workspace.unreadState.isUnread)
                        assertEquals(name, if (item.isNull("unread_count")) null else item.getLong("unread_count"), entry.workspace.unreadState.count)
                    }
                    "footer" -> {
                        assertTrue("$name: $item", entry is WorkspaceListEntry.Footer)
                        assertEquals(name, item.getString("id"), (entry as WorkspaceListEntry.Footer).group.id)
                    }
                    else -> error("Unknown reference item")
                }
            }
            assertEquals(name, entries.size, entries.map { it.key }.distinct().size)
        }
    }

    @Test fun everyRenderedDropAndPredictedOrderMatchesPinnedIos() {
        for (fixture in golden.getJSONArray("cases").objects()) {
            val source = source(fixture)
            val entries = workspaceHierarchy(source)
            val policy = NativeWorkspaceMovePolicy(source.workspaces, source.groups)
            for (drop in fixture.getJSONArray("drops").objects()) {
                val from = drop.getInt("from"); val to = drop.getInt("to")
                val label = "${fixture.getString("name")} drop $from → $to"
                val actual = workspaceDropIntent(source, entries, from, to)
                if (drop.isNull("intent")) assertNull(label, actual)
                else {
                    assertNotNull(label, actual)
                    assertEquals(label, drop.getString("moved"), actual!!.first)
                    assertEquals(label, drop.getJSONObject("intent").intent(), actual.second)
                    assertEquals(label, order(drop.getJSONArray("order")), signature(policy.applying(actual.second, actual.first)))
                }
            }
        }
    }

    @Test fun mutationNormalizationMatchesPinnedIosIncludingInvalidTargetsAndPins() {
        for (fixture in golden.getJSONArray("cases").objects()) {
            val source = source(fixture)
            val policy = NativeWorkspaceMovePolicy(source.workspaces, source.groups)
            for (proposal in fixture.getJSONArray("proposals").objects()) {
                val moved = proposal.getString("moved")
                val proposed = proposal.getJSONObject("proposed").intent()
                val label = "${fixture.getString("name")} move $moved: $proposed"
                val normalized = policy.normalized(proposed, moved)
                if (proposal.isNull("intent")) assertNull(label, normalized)
                else {
                    assertEquals(label, proposal.getJSONObject("intent").intent(), normalized)
                    assertEquals(label, order(proposal.getJSONArray("order")), signature(policy.applying(normalized!!, moved)))
                }
            }
        }
    }

    @Test fun accessibleGroupStepSkipsItsOwnMembersAndFooter() {
        val fixture = golden.getJSONArray("cases").objects().single { it.getString("name") == "group-boundaries-False" }
        val source = source(fixture)
        val entries = workspaceHierarchy(source)
        val index = entries.indexOfFirst { it is WorkspaceListEntry.Header }
        val down = workspaceStepIntent(source, entries, index, true)!!
        assertEquals("anchor" to NativeWorkspaceMove(null, null, true), down)
        val up = workspaceStepIntent(source, entries, index, false)!!
        assertEquals("anchor" to NativeWorkspaceMove(null, "root", true), up)
        val footer = entries.indexOfFirst { it is WorkspaceListEntry.Footer }
        assertNull(workspaceStepIntent(source, entries, footer, true))
    }
}
