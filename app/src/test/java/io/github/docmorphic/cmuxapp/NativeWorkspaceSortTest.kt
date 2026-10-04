package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class NativeWorkspaceSortTest {
    private fun source(id: String, rows: String, groups: List<NativeGroup> = emptyList()) = NativeFeedSource(
        NativeCredentialStore.PairedMac(id, id, id), workspaces = parseWorkspaces(JSONObject("""{"workspaces":$rows}""")), groups = groups)
    private fun sorted(sources: List<NativeFeedSource>, state: NativeWorkspaceSortState = NativeWorkspaceSortState("recentActivity"),
        filtering: Boolean = false, all: Boolean = true, collapsed: Map<String, Boolean> = emptyMap()): List<WorkspaceListEntry> =
        sortedWorkspaceRows(sources, emptyList(), sources.map { NativeSortComputer(workspaceMacFilterId(it.mac.deviceId, null)!!, it.mac.name) },
            state, all, sources.flatMap { s -> s.workspaces.map { workspaceSearchId(s, it) } }.toSet(), filtering, false, collapsed, Locale.US)
            .filterIsInstance<NativeWorkspaceDisplayRow.Mac>().map { it.entry }

    @Test fun recentGroupsStayAtomicKeepSidebarOrderAndOriginalActionSource() {
        val a = source("a", """[{"id":"anchor","last_activity_at":1},{"id":"old","group_id":"g","last_activity_at":2},
          {"id":"new","group_id":"g","last_activity_at":100},{"id":"loose","last_activity_at":50}]""",
            listOf(NativeGroup("g", "Group", false, false, "anchor")))
        val b = source("b", """[{"id":"other","last_activity_at":75}]""")
        val rows = sorted(listOf(a,b))
        assertTrue(rows.first() is WorkspaceListEntry.Header)
        assertEquals(listOf("old","new","other","loose"), rows.filterIsInstance<WorkspaceListEntry.Workspace>().map { it.workspace.id })
        assertEquals(1, rows.filterIsInstance<WorkspaceListEntry.Footer>().size)
        assertTrue(rows.take(4).all { it.source === a })
        assertEquals(4, a.workspaces.size); assertNull(a.workspaces.first().groupId)
    }
    @Test fun pinnedGroupMembersPromoteWholeGroupAndDurableEmptyHeadersSurvive() {
        val a = source("a", """[{"id":"loose","last_activity_at":900},{"id":"anchor","group_id":"g"},
          {"id":"pin","group_id":"g","is_pinned":true,"last_activity_at":1}]""",
            listOf(NativeGroup("g","Group",true,false,"anchor"), NativeGroup("empty","Empty",false,true)))
        val rows = sorted(listOf(a))
        assertEquals(listOf("g","empty"), rows.take(2).map { (it as WorkspaceListEntry.Header).group.id })
        assertEquals(listOf("loose"), rows.filterIsInstance<WorkspaceListEntry.Workspace>().map { it.workspace.id })
        val expanded = sorted(listOf(a), collapsed = mapOf(WorkspaceListEntry.Header(a,a.groups.first()).key to false))
        assertEquals(listOf("pin","loose"), expanded.filterIsInstance<WorkspaceListEntry.Workspace>().map { it.workspace.id })
    }
    @Test fun flatFiltersRevealMembersAndSortPinnedThenTimeThenStableTies() {
        val a = source("a", """[{"id":"old","group_id":"g","last_activity_at":1},{"id":"new","group_id":"g","last_activity_at":5},
          {"id":"tie","last_activity_at":5},{"id":"pin","is_pinned":true},{"id":"missing"}]""",
            listOf(NativeGroup("g","G",true,false,"old")))
        val rows = sorted(listOf(a), filtering = true)
        assertEquals(listOf("pin","new","tie","old","missing"), rows.map { (it as WorkspaceListEntry.Workspace).workspace.id })
    }
    @Test fun recentActivityNeverChangesSingleComputerSidebarOrder() {
        val a = source("a", """[{"id":"old","last_activity_at":1},{"id":"new","last_activity_at":5}]""")
        assertEquals(listOf("old","new"), sorted(listOf(a), all = false).map { (it as WorkspaceListEntry.Workspace).workspace.id })
    }
    @Test fun lastOpenedAndPriorityUseExactBuildsAndForegroundAndDeterministicFallbacks() {
        val stable = workspaceMacFilterId("mac", "default")!!; val nightly = workspaceMacFilterId("mac", "nightly")!!
        val a = NativeSortComputer(stable, "Z", true); val b = NativeSortComputer(nightly, "A"); val c = NativeSortComputer("c", "B")
        val state = NativeWorkspaceSortState(opened = mapOf(nightly to 100L))
        assertEquals(listOf(stable,nightly,"c"), orderWorkspaceComputers(listOf(c,b,a),state,Locale.US).map { it.id })
        assertEquals(listOf(nightly,stable,"c"), orderWorkspaceComputers(listOf(c,b,a),state.copy(rawMode="computerPriority",priority=listOf(nightly)),Locale.US).map { it.id })
        assertEquals(listOf("a","b"), orderWorkspaceComputers(listOf(NativeSortComputer("b","same"),NativeSortComputer("a","SAME")),NativeWorkspaceSortState(),Locale.US).map { it.id })
    }
    @Test fun sortPreferencesPersistUnknownModeAndOfflineSlotsWithoutRewritingOnRead() {
        var raw: String? = """{"mode":"future","priority":["a","offline","b"]}"""; var writes=0
        fun store()=NativeWorkspaceSortStore({raw},{raw=it;writes++})
        val s=store();assertEquals(NativeWorkspaceSortMode.AUTOMATIC,s.state.value.mode);assertEquals(0,writes)
        s.recordOpened("a",10);s.recordOpened("a",5);assertEquals(1,writes);assertEquals("future",s.state.value.rawMode)
        s.setPriority(listOf("b","a"));assertEquals(listOf("b","offline","a"),s.state.value.priority)
        s.setMode(NativeWorkspaceSortMode.PRIORITY)
        assertEquals(s.state.value,store().state.value)
    }
    @Test fun mixedMacAndSshOrderIsUnifiedAndNeverInventsSshActivityTimestamps() {
        val a=source("a","""[{"id":"old","last_activity_at":1},{"id":"new","last_activity_at":2}]""")
        val host=SshHostRecord(name="SSH",endpoint=SshEndpoint("fixture.test",22,"user"))
        val ssh=sshTmuxFeedRows(host,listOf(SshTmuxWorkspace(1,1,1,"Session",emptyList())))
        val macId=workspaceMacFilterId("a",null)!!;val sshId=workspaceSshFilterId(host.id)
        val computers=listOf(NativeSortComputer(macId,"a",true),NativeSortComputer(sshId,"SSH"))
        fun rows(state:NativeWorkspaceSortState)=sortedWorkspaceRows(listOf(a),ssh,computers,state,true,
            a.workspaces.map { workspaceSearchId(a,it) }.toSet(),false,false,emptyMap(),Locale.US)
        assertTrue(rows(NativeWorkspaceSortState("computerPriority",listOf(sshId))).first() is NativeWorkspaceDisplayRow.Ssh)
        assertTrue(rows(NativeWorkspaceSortState("recentActivity")).last() is NativeWorkspaceDisplayRow.Ssh)
        assertSame(a,(rows(NativeWorkspaceSortState()).first() as NativeWorkspaceDisplayRow.Mac).entry.source)
    }
    @Test fun identicalGroupAndWorkspaceIdsStayWithinTheirOwningComputer() {
        val a=source("a","""[{"id":"same","group_id":"g","last_activity_at":1}]""",listOf(NativeGroup("g","A",false,false,"same")))
        val b=source("b","""[{"id":"same","group_id":"g","last_activity_at":2}]""",listOf(NativeGroup("g","B",false,false,"same")))
        assertEquals(listOf("b","a"),sorted(listOf(a,b)).map { it.source.mac.deviceId })
    }
    @Test fun computerMoveUsesInsertionSlotsAndIgnoresStaleTargets() {
        assertEquals(listOf("b","c","a"),moveWorkspaceComputer(listOf("a","b","c"),"a",3))
        assertEquals(listOf("c","a","b"),moveWorkspaceComputer(listOf("a","b","c"),"c",0))
        assertEquals(listOf("a"),moveWorkspaceComputer(listOf("a"),"gone",0))
    }
}
