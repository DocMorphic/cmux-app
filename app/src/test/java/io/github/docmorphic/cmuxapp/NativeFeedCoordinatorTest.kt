package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class NativeFeedCoordinatorTest {
    @Test fun panelAdmissionUsesExactMacAndDisplayedFileOnly() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            val target = NativePanelTarget("w", "panel", "/note.md", "markdown", "Notes")
            val listing = JSONObject("""{"workspaces":[{"id":"w","surfaces":[{"surface_id":"panel","kind":"markdown","title":"Notes","file_path":"/note.md"}]}]}""")
            a.panelArtifactsSupported = true; b.panelArtifactsSupported = true
            a.workspaceResponse = listing; b.workspaceResponse = listing
            var permitted = true
            val coordinator = NativeFeedCoordinator(this, { if (it.deviceId == "a") a.connect() else b.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.hasWorkspaceSnapshot } == 2 }
                val access = checkNotNull(coordinator.panelArtifactAccess(mac("b"), target) { permitted })
                assertEquals("b", access.rpc.stat(target.authorization, target.path).getString("owner"))
                assertTrue(runCatching { access.rpc.stat(target.copy(surface = "other").authorization, target.path) }.isFailure)
                assertTrue(runCatching { access.rpc.stat(target.copy(path = "/secret").authorization, "/secret") }.isFailure)
                assertTrue(runCatching { access.rpc.stat(ArtifactAuthorization.Session("other"), target.path) }.isFailure)
                assertNull(coordinator.panelArtifactAccess(mac("b").copy(instanceTag = "nightly"), target) { true })
                permitted = false; assertFalse(access.current())
                assertTrue(runCatching { access.rpc.stat(target.authorization, target.path) }.isFailure)
                assertTrue(a.requests.none { it.optString("method").contains(".artifact.") })
                assertEquals(1, b.requests.count { it.optString("method").contains(".artifact.") })
            } finally { coordinator.close() }
        } }
    }

    @Test fun panelRefreshIdentityIgnoresFocusButInvalidatesTitleKindAndPathChanges() = runBlocking {
        FeedPeer("a").use { peer ->
            peer.panelArtifactsSupported = true
            val target = NativePanelTarget("w", "panel", "/note.md", "markdown", "Notes")
            fun listing(target: NativePanelTarget, focused: Boolean = false) = JSONObject().put("workspaces", JSONArray().put(
                JSONObject().put("id", "w").put("surfaces", JSONArray().put(JSONObject().put("surface_id", target.surface)
                    .put("kind", target.kind).put("title", target.title).put("file_path", target.path).put("is_focused", focused)))))
            peer.workspaceResponse = listing(target)
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.hasWorkspaceSnapshot == true }
                for (replacement in listOf(target.copy(title = "Updated"), target.copy(path = "/new.md"), target.copy(kind = "filePreview"))) {
                    peer.workspaceResponse = listing(target); coordinator.refreshWorkspaceLists(listOf(mac("a")))
                    val access = checkNotNull(coordinator.panelArtifactAccess(mac("a"), target) { true })
                    peer.workspaceResponse = listing(target, true); coordinator.refreshWorkspaceLists(listOf(mac("a")))
                    assertTrue(access.current())
                    peer.workspaceResponse = listing(replacement); coordinator.refreshWorkspaceLists(listOf(mac("a")))
                    assertFalse(access.current()); assertNull(coordinator.panelArtifactAccess(mac("a"), target) { true })
                    assertTrue(runCatching { access.rpc.stat(target.authorization, target.path) }.isFailure)
                    assertNotNull(coordinator.panelArtifactAccess(mac("a"), replacement) { true })
                }
                peer.workspaceResponse = JSONObject("""{"workspaces":[{"id":"w","surfaces":[]}]}""")
                coordinator.refreshWorkspaceLists(listOf(mac("a")))
                assertNull(coordinator.panelArtifactAccess(mac("a"), target) { true })
            } finally { coordinator.close() }
        }
    }

    @Test fun panelReplyRejectsRevocationAndOldConnectionCannotBeReused() = runBlocking {
        FeedPeer("a").use { peer ->
            peer.panelArtifactsSupported = true
            val target = NativePanelTarget("w", "panel", "/note.md", "markdown", "Notes")
            peer.workspaceResponse = JSONObject("""{"workspaces":[{"id":"w","surfaces":[{"surface_id":"panel","kind":"markdown","title":"Notes","file_path":"/note.md"}]}]}""")
            var permitted = true; val gate = java.util.concurrent.CountDownLatch(1); peer.artifactGate = gate
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.hasWorkspaceSnapshot == true }
                val access = checkNotNull(coordinator.panelArtifactAccess(mac("a"), target) { permitted })
                val response = async { runCatching { access.rpc.stat(target.authorization, target.path) } }
                awaitState { peer.requests.any { it.optString("method").contains(".artifact.") } }
                permitted = false; gate.countDown(); assertTrue(response.await().isFailure)
                permitted = true; coordinator.pause(); coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.availability == NativeFeedAvailability.CONNECTED }
                assertFalse(access.current()); assertTrue(runCatching { access.rpc.stat(target.authorization, target.path) }.isFailure)
                assertNotNull(coordinator.panelArtifactAccess(mac("a"), target) { true })
            } finally { gate.countDown(); coordinator.close() }
        }
    }

    @Test fun retainedArtifactsUseExactMacAndRejectWithdrawnAdmission() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            val terminal = ArtifactAuthorization.Terminal("w", "t")
            val listing = JSONObject("""{"workspaces":[{"id":"w","terminals":[{"id":"t"}]}]}""")
            a.artifactsSupported = true; b.artifactsSupported = true
            a.workspaceResponse = listing; b.workspaceResponse = listing
            var allowed = true; var caller = true
            val coordinator = NativeFeedCoordinator(this, { if (it.deviceId == "a") a.connect() else b.connect() }, { allowed })
            try {
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.hasWorkspaceSnapshot } == 2 }
                val access = checkNotNull(coordinator.terminalArtifactAccess(mac("b"), terminal) { caller })
                assertEquals("b", access.rpc.stat(terminal, "/report.txt").getString("owner"))
                assertTrue(a.requests.none { it.optString("method").contains(".artifact.") })
                assertNull(coordinator.terminalArtifactAccess(mac("b").copy(instanceTag = "nightly"), terminal) { true })
                assertNull(coordinator.terminalArtifactAccess(mac("b"), terminal.copy(surfaceId = "other")) { true })
                caller = false; assertFalse(access.current())
                assertTrue(runCatching { access.rpc.stat(terminal, "/report.txt") }.isFailure)
                caller = true; allowed = false; assertFalse(access.current())
                assertTrue(runCatching { access.rpc.stat(terminal, "/report.txt") }.isFailure)
                assertEquals(1, b.requests.count { it.optString("method").contains(".artifact.") })
            } finally { coordinator.close() }
        } }
    }

    @Test fun retainedArtifactAdmissionDoesNotRebindAfterReconnectOrTerminalRemoval() = runBlocking {
        FeedPeer("a").use { peer ->
            val terminal = ArtifactAuthorization.Terminal("w", "t")
            peer.artifactsSupported = true
            peer.workspaceResponse = JSONObject("""{"workspaces":[{"id":"w","terminals":[{"id":"t"}]}]}""")
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.hasWorkspaceSnapshot == true }
                val original = checkNotNull(coordinator.terminalArtifactAccess(mac("a"), terminal) { true })
                coordinator.pause(); assertFalse(original.current())
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.availability == NativeFeedAvailability.CONNECTED }
                assertFalse(original.current())
                assertTrue(runCatching { original.rpc.stat(terminal, "/report.txt") }.isFailure)
                val replacement = checkNotNull(coordinator.terminalArtifactAccess(mac("a"), terminal) { true })
                assertTrue(replacement.current())
                peer.workspaceResponse = JSONObject("""{"workspaces":[{"id":"w","terminals":[]}]}""")
                coordinator.refreshWorkspaceLists(listOf(mac("a")))
                awaitState { !replacement.current() }
                assertTrue(runCatching { replacement.rpc.stat(terminal, "/report.txt") }.isFailure)
                assertTrue(peer.requests.none { it.optString("method").contains(".artifact.") })
            } finally { coordinator.close() }
        }
    }

    @Test fun artifactReplyIsRejectedWhenOwnerIsRevokedWhileRequestIsInFlight() = runBlocking {
        FeedPeer("a").use { peer ->
            peer.artifactsSupported = true
            peer.workspaceResponse = JSONObject("""{"workspaces":[{"id":"w","terminals":[{"id":"t"}]}]}""")
            val gate = java.util.concurrent.CountDownLatch(1)
            peer.artifactGate = gate
            var permitted = true
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.hasWorkspaceSnapshot == true }
                val terminal = ArtifactAuthorization.Terminal("w", "t")
                val access = checkNotNull(coordinator.terminalArtifactAccess(mac("a"), terminal) { permitted })
                val response = async { runCatching { access.rpc.stat(terminal, "/report.txt") } }
                awaitState { peer.requests.any { it.optString("method").contains(".artifact.") } }
                permitted = false; gate.countDown()
                assertTrue(response.await().isFailure); assertFalse(access.current())
            } finally { gate.countDown(); coordinator.close() }
        }
    }
    @Test fun legacyMacTicketEnablesGroupActionsAndExpiryRefreshesThePublishedUiAuthority() = runBlocking {
        FeedPeer("a").use { peer ->
            peer.accountMutationsSupported = false; peer.groupCreationSupported = true; peer.newGroupCreationSupported = true
            peer.ticket = MobileAttachTicketContext("", null, "legacy-fixture", System.currentTimeMillis() + 2000)
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.hasWorkspaceSnapshot == true }
                val source = coordinator.sources.value.values.single()
                assertTrue(source.canEditGroups()); assertTrue(source.canCreateInGroup()); assertTrue(source.canCreateGroup())
                coordinator.groupAction(mac("a"), "g", "rename", "Renamed")
                coordinator.createWorkspaceInGroup(mac("a"), "g")
                coordinator.createGroup(mac("a"))
                val writes = peer.requests.filter { it.optString("method") in setOf("workspace.group.action", "workspace.create", "workspace.group.create") }
                assertEquals(3, writes.size)
                assertTrue(writes.all { it.getJSONObject("auth").getString("attach_token") == "legacy-fixture" })
                awaitState { coordinator.sources.value.values.single().macMutationTicket == null }
                assertFalse(coordinator.sources.value.values.single().canEditGroups())
                assertTrue(runCatching { coordinator.createGroup(mac("a")) }.isFailure)
                assertEquals(1, peer.requests.count { it.optString("method") == "workspace.group.create" })
            } finally { coordinator.close() }
        }
    }
    @Test fun newGroupUsesExactMacDefaultNameAndReconcilesSuccessAndRejection() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            b.newGroupCreationSupported = true
            val coordinator = NativeFeedCoordinator(this, { if (it.deviceId == "a") a.connect() else b.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.hasWorkspaceSnapshot } == 2 }
                b.workspaceResponse = JSONObject("""{"workspaces":[],"groups":[{"id":"new","name":"Group 2"}]}""")
                coordinator.createGroup(mac("b"))
                assertEquals("Group 2", coordinator.sources.value.getValue(mac("b").origin).groups.single().name)
                val command = b.requests.single { it.optString("method") == "workspace.group.create" }
                assertEquals(0, command.getJSONObject("params").length())
                assertTrue(a.requests.none { it.optString("method") == "workspace.group.create" })
                b.workspaceResponse = JSONObject("""{"workspaces":[],"groups":[{"id":"external","name":"External group"}]}""")
                b.rejectedMethods = setOf("workspace.group.create")
                assertTrue(runCatching { coordinator.createGroup(mac("b")) }.isFailure)
                assertEquals("External group", coordinator.sources.value.getValue(mac("b").origin).groups.single().name)
                assertEquals(2, b.requests.count { it.optString("method") == "workspace.group.create" })
            } finally { coordinator.close() }
        } }
    }
    @Test fun newGroupRequiresItsOwnCapabilityAccountExactPairingAndCaller() = runBlocking {
        FeedPeer("a").use { peer ->
            peer.newGroupCreationSupported = true
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.hasWorkspaceSnapshot == true }
                assertTrue(runCatching { coordinator.createGroup(mac("a")) { false } }.isFailure)
                assertTrue(runCatching { coordinator.createGroup(mac("a").copy(instanceTag = "nightly")) }.isFailure)
                coordinator.pause(); peer.accountMutationsSupported = false
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.single().availability == NativeFeedAvailability.CONNECTED }
                assertFalse(coordinator.sources.value.values.single().canCreateGroup())
                assertTrue(runCatching { coordinator.createGroup(mac("a")) }.isFailure)
                coordinator.pause(); peer.accountMutationsSupported = true; peer.newGroupCreationSupported = false
                peer.groupCreationSupported = true // In-group creation does not imply New Group.
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.single().availability == NativeFeedAvailability.CONNECTED }
                assertTrue(coordinator.sources.value.values.single().canCreateInGroup())
                assertTrue(runCatching { coordinator.createGroup(mac("a")) }.isFailure)
                assertTrue(peer.requests.none { it.optString("method") == "workspace.group.create" })
            } finally { coordinator.close() }
        }
    }

    @Test fun callerWithdrawalPreventsPlainAndGroupCreationAtSendBoundary() = runBlocking {
        FeedPeer("a").use { peer ->
            peer.groupCreationSupported = true
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.hasWorkspaceSnapshot == true }
                assertTrue(runCatching { coordinator.createWorkspace(mac("a")) { false } }.isFailure)
                assertTrue(runCatching { coordinator.createWorkspaceInGroup(mac("a"), "g") { false } }.isFailure)
                assertTrue(peer.requests.none { it.optString("method") == "workspace.create" })
            } finally { coordinator.close() }
        }
    }

    @Test fun plainCreateTargetsExactMacAndAcceptsLegacyWithoutAccountGroupCapability() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            b.accountMutationsSupported = false; b.ticket = MobileAttachTicketContext("", null, "fixture-ticket", null)
            b.createResponse = JSONObject("""{"workspaces":[{"id":"legacy-created"}]}""")
            val coordinator = NativeFeedCoordinator(this, { if (it.deviceId == "a") a.connect() else b.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.hasWorkspaceSnapshot } == 2 }
                assertNull(createdPlainWorkspace(coordinator.createWorkspace(mac("b"))))
                assertTrue(a.requests.none { it.optString("method") == "workspace.create" })
                assertEquals(0, b.requests.single { it.optString("method") == "workspace.create" }.getJSONObject("params").length())
                b.rejectedMethods = setOf("workspace.create"); b.workspaceTitle = "Authoritative after rejection"
                assertTrue(runCatching { coordinator.createWorkspace(mac("b")) }.isFailure)
                assertEquals("Authoritative after rejection", coordinator.sources.value.getValue(mac("b").origin).workspaces.single().title)
            } finally { coordinator.close() }
        } }
    }

    @Test fun plainCreateRejectsReplacedPairingBuildAndRevokedAccountBeforeSending() = runBlocking {
        FeedPeer("a").use { peer ->
            var allowed = true
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { allowed })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.hasWorkspaceSnapshot == true }
                for (stale in listOf(mac("a").copy(code = "replaced"), mac("a").copy(instanceTag = "nightly"),
                    mac("a").copy(accountUserId = "other-user"))) {
                    assertTrue(runCatching { coordinator.createWorkspace(stale) }.isFailure)
                }
                allowed = false
                assertTrue(runCatching { coordinator.createWorkspace(mac("a")) }.isFailure)
                assertTrue(peer.requests.none { it.optString("method") == "workspace.create" })
            } finally { coordinator.close() }
        }
    }

    @Test fun groupDeleteAndCreateUseOnlyTheirOwnerAndReconcileRejections() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            b.groupCreationSupported = true
            val coordinator = NativeFeedCoordinator(this, { if (it.deviceId == "a") a.connect() else b.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.hasWorkspaceSnapshot } == 2 }
                coordinator.groupAction(mac("b"), "g", "delete")
                val sent = b.requests.single { it.optString("method") == "workspace.group.action" }.getJSONObject("params")
                assertEquals("g", sent.getString("group_id")); assertEquals("delete", sent.getString("action"))
                assertEquals(2, sent.length())
                coordinator.createWorkspaceInGroup(mac("b"), "g")
                assertEquals("g", b.requests.single { it.optString("method") == "workspace.create" }.getJSONObject("params").getString("group_id"))
                assertTrue(a.requests.none { it.optString("method") in setOf("workspace.create", "workspace.group.action", "workspace.close") })
                assertTrue(b.requests.none { it.optString("method") == "workspace.close" })
                b.rejectedMethods = setOf("workspace.group.action")
                b.workspaceTitle = "Changed after rejection"
                assertTrue(runCatching { coordinator.groupAction(mac("b"), "g", "delete") }.isFailure)
                assertEquals("Changed after rejection", coordinator.sources.value.getValue(mac("b").origin).workspaces.single().title)
            } finally { coordinator.close() }
        } }
    }

    @Test fun groupsRecheckPinnedMembershipAndCapturedOwnerBeforeWriting() = runBlocking {
        FeedPeer("a").use { peer ->
            peer.groupCreationSupported = true
            val allowed = mutableSetOf("a")
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { it.deviceId in allowed })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.hasWorkspaceSnapshot == true }
                peer.workspaceResponse = JSONObject("""{"workspaces":[],"groups":[{"id":"g","is_pinned":true}]}""")
                coordinator.refreshWorkspaceLists(listOf(mac("a")))
                assertTrue(coordinator.sources.value.values.single().groups.single().isPinned)
                assertTrue(runCatching { coordinator.groupAction(mac("a"), "g", "ungroup") }.isFailure)
                assertTrue(runCatching { coordinator.createWorkspaceInGroup(mac("a"), "missing") }.isFailure)
                allowed.clear()
                assertTrue(runCatching { coordinator.groupAction(mac("a"), "g", "delete") }.isFailure)
                assertTrue(runCatching { coordinator.createWorkspaceInGroup(mac("a"), "g") }.isFailure)
                assertTrue(peer.requests.none { it.optString("method") in setOf("workspace.create", "workspace.group.action") })
            } finally { coordinator.close() }
        }
    }

    @Test fun groupCreationCapabilityIsIndependentAndWithdrawsOnReconnect() = runBlocking {
        FeedPeer("a").use { peer ->
            peer.groupActionsSupported = false; peer.groupCreationSupported = true
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.hasWorkspaceSnapshot == true }
                coordinator.createWorkspaceInGroup(mac("a"), "g")
                assertTrue(runCatching { coordinator.groupAction(mac("a"), "g", "delete") }.isFailure)
                coordinator.pause(); peer.groupCreationSupported = false
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.single().availability == NativeFeedAvailability.CONNECTED }
                assertTrue(runCatching { coordinator.createWorkspaceInGroup(mac("a"), "g") }.isFailure)
                assertEquals(1, peer.requests.count { it.optString("method") == "workspace.create" })
                assertTrue(peer.requests.none { it.optString("method") == "workspace.group.action" })
            } finally { coordinator.close() }
        }
    }

    @Test fun groupActionsRequireAccountAuthorityEvenWhenTheVerbsAreAdvertised() = runBlocking {
        FeedPeer("a").use { peer ->
            peer.accountMutationsSupported = false; peer.groupCreationSupported = true
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.hasWorkspaceSnapshot == true }
                val source = coordinator.sources.value.values.single()
                assertFalse(source.canEditGroups()); assertFalse(source.canCreateInGroup())
                assertTrue(runCatching { coordinator.groupAction(mac("a"), "g", "delete") }.isFailure)
                assertTrue(runCatching { coordinator.createWorkspaceInGroup(mac("a"), "g") }.isFailure)
                assertTrue(peer.requests.none { it.optString("method") in setOf("workspace.create", "workspace.group.action") })
            } finally { coordinator.close() }
        }
    }

    @Test fun rowActionsRequireTheCurrentOwningMacCapability() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            a.rowActionsSupported = false
            val coordinator = NativeFeedCoordinator(this, { if (it.deviceId == "a") a.connect() else b.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.availability == NativeFeedAvailability.CONNECTED } == 2 }
                for (action in listOf("rename", "pin", "unpin", "mark_read", "mark_unread", "close")) {
                    assertTrue(runCatching { coordinator.workspaceAction(mac("a"), "w", action, "Name") }.isFailure)
                }
                assertTrue(a.requests.none { it.optString("method") in setOf("workspace.action", "workspace.close") })
                coordinator.workspaceAction(mac("b"), "w", "mark_unread")
                assertEquals("mark_unread", b.requests.single { it.optString("method") == "workspace.action" }
                    .getJSONObject("params").getString("action"))
                assertTrue(a.requests.none { it.optString("method") == "workspace.action" })
            } finally { coordinator.close() }
        } }
    }

    @Test fun changesChipsBelongToEachVerifiedMacAndDisappearOnDisconnect() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            a.changesSupported = true; b.changesSupported = true
            a.changedFiles = 2; b.changedFiles = 7
            val coordinator = NativeFeedCoordinator(this, { if (it.deviceId == "a") a.connect() else b.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.changes.isNotEmpty() } == 2 }
                assertEquals(2L, coordinator.sources.value.getValue(mac("a").origin).changes.getValue("w").files)
                assertEquals(7L, coordinator.sources.value.getValue(mac("b").origin).changes.getValue("w").files)
                a.changedFiles = 0; coordinator.refreshWorkspaceLists(listOf(mac("a")))
                awaitState { coordinator.sources.value.getValue(mac("a").origin).changes.isEmpty() }
                assertEquals(7L, coordinator.sources.value.getValue(mac("b").origin).changes.getValue("w").files)
                assertEquals(1, b.requests.count { it.optString("method") == "mobile.workspace.changes.summary" })
                b.disconnect()
                awaitState { coordinator.sources.value.getValue(mac("b").origin).availability == NativeFeedAvailability.OFFLINE }
                assertTrue(coordinator.sources.value.getValue(mac("b").origin).changes.isEmpty())
            } finally { coordinator.close() }
        } }
    }
    @Test fun summaryAuthorizationFailureRetiresTheFeedAndItsChips() = runBlocking {
        FeedPeer("a").use { a ->
            a.changesSupported = true
            val coordinator = NativeFeedCoordinator(this, { a.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value[mac("a").origin]?.changes?.isNotEmpty() == true }
                a.summaryError = "team_access_revoked"; coordinator.refresh()
                awaitState { coordinator.sources.value[mac("a").origin]?.availability == NativeFeedAvailability.OFFLINE }
                assertTrue(coordinator.sources.value.getValue(mac("a").origin).changes.isEmpty())
            } finally { coordinator.close() }
        }
    }
    @Test fun unsupportedMacDoesNotReceiveSummaryRequests() = runBlocking {
        FeedPeer("a").use { a ->
            val coordinator = NativeFeedCoordinator(this, { a.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value[mac("a").origin]?.availability == NativeFeedAvailability.CONNECTED }
                coordinator.refresh(); delay(300)
                assertTrue(a.requests.none { it.optString("method") == "mobile.workspace.changes.summary" })
            } finally { coordinator.close() }
        }
    }
    @Test fun explicitWorkspaceRecoveryRefreshesOnlyCapturedMacWithoutNotificationMutation() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            val coordinator = NativeFeedCoordinator(this, { if (it.deviceId == "a") a.connect() else b.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.availability == NativeFeedAvailability.CONNECTED } == 2 }
                val bReads = b.requests.count { it.optString("method") == "mobile.workspace.list" }
                val aNotifications = a.requests.count { it.optString("method") == "notification.feed.list" }
                a.workspaceTitle = "Fresh empty-list retry"
                withTimeout(2000) { coordinator.refreshWorkspaceLists(listOf(mac("a"))) }
                assertEquals("Fresh empty-list retry", coordinator.sources.value[mac("a").origin]?.workspaces?.single()?.title)
                assertEquals(bReads, b.requests.count { it.optString("method") == "mobile.workspace.list" })
                assertEquals(aNotifications, a.requests.count { it.optString("method") == "notification.feed.list" })
            } finally { coordinator.close() }
        } }
    }

    @Test fun emptyRecoveryWaitsForIdentityAndCannotFollowReplacementHandle() = runBlocking {
        FeedPeer("a").use { peer ->
            val gate = java.util.concurrent.CountDownLatch(1); peer.hostStatusGate = gate
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { peer.requests.any { it.optString("method") == "mobile.host.status" } }
                val pending = async { runCatching { coordinator.refreshWorkspaceLists(listOf(mac("a"))) } }
                yield()
                assertFalse(pending.isCompleted)
                assertTrue(peer.requests.none { it.optString("method") == "mobile.workspace.list" })
                coordinator.pause()
                gate.countDown()
                assertTrue(withTimeout(2000) { pending.await() }.isFailure)
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value[mac("a").origin]?.availability == NativeFeedAvailability.CONNECTED }
                coordinator.refreshWorkspaceLists(listOf(mac("a")))
            } finally { gate.countDown(); coordinator.close() }
        }
    }

    @Test fun cancellingEmptyRecoveryDoesNotRetireSharedConnection() = runBlocking {
        FeedPeer("a").use { peer ->
            val gate = java.util.concurrent.CountDownLatch(1); peer.hostStatusGate = gate
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { peer.requests.any { it.optString("method") == "mobile.host.status" } }
                val pending = launch { coordinator.refreshWorkspaceLists(listOf(mac("a"))) }
                yield(); pending.cancelAndJoin(); gate.countDown()
                awaitState { coordinator.sources.value[mac("a").origin]?.availability == NativeFeedAvailability.CONNECTED }
                coordinator.refreshWorkspaceLists(listOf(mac("a")))
                assertEquals(1, peer.requests.count { it.optString("method") == "mobile.host.status" })
            } finally { gate.countDown(); coordinator.close() }
        }
    }

    private suspend fun awaitState(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }
    private fun mac(id: String) = NativeCredentialStore.PairedMac(id, id, "Mac $id")

    @Test fun pausedVisibilityPruningDropsHiddenSnapshotsWithoutDialingSurvivor() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            var connects = 0
            val coordinator = NativeFeedCoordinator(this, { row -> connects++; (if (row.deviceId == "a") a else b).connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.availability == NativeFeedAvailability.CONNECTED } == 2 }
                coordinator.pause()
                val before = connects
                assertEquals(2, coordinator.sources.value.size)
                coordinator.retainMacs(listOf(mac("b")))
                assertEquals(setOf(mac("b").origin), coordinator.sources.value.keys)
                assertEquals(NativeFeedAvailability.OFFLINE, coordinator.sources.value[mac("b").origin]?.availability)
                coordinator.retainMacs(emptyList())
                assertTrue(coordinator.sources.value.isEmpty())
                assertEquals(before, connects)
            } finally { coordinator.close() }
        } }
    }

    @Test fun lastSeenCallbackRunsOnlyAfterExactHostVerification() = runBlocking {
        FeedPeer("a").use { peer ->
            val seen = mutableListOf<NativeCredentialStore.PairedMac>()
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true }, onVerified = { seen += it })
            try {
                coordinator.updateMacs(listOf(mac("wrong")))
                awaitState { coordinator.sources.value[mac("wrong").origin]?.availability == NativeFeedAvailability.OFFLINE }
                assertTrue(seen.isEmpty())
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value[mac("a").origin]?.availability == NativeFeedAvailability.CONNECTED }
                assertEquals(listOf(mac("a")), seen)
            } finally { coordinator.close() }
        }
    }

    @Test fun localDialTimeoutPublishesOfflineAndCanReconnectOnRefresh() = runBlocking {
        FeedPeer("a").use { peer ->
            var attempts = 0
            val coordinator = NativeFeedCoordinator(this, {
                if (++attempts == 1) withTimeout(30) { awaitCancellation() }
                peer.connect()
            }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value[mac("a").origin]?.availability == NativeFeedAvailability.OFFLINE }
                coordinator.refresh()
                awaitState { coordinator.sources.value[mac("a").origin]?.availability == NativeFeedAvailability.CONNECTED }
                assertEquals(2, attempts)
            } finally { coordinator.close() }
        }
    }

    @Test fun retiringTimedOutDialCannotReplaceItsNewerOwner() = runBlocking {
        FeedPeer("a").use { peer ->
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val oldFinished = CompletableDeferred<Unit>()
            var attempts = 0
            val coordinator = NativeFeedCoordinator(this, {
                if (++attempts == 1) {
                    try { withContext(NonCancellable) {
                        started.complete(Unit); release.await()
                        withTimeout(1) { awaitCancellation() }
                    } } finally { oldFinished.complete(Unit) }
                }
                peer.connect()
            }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a"))); started.await()
                coordinator.pause(); coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value[mac("a").origin]?.availability == NativeFeedAvailability.CONNECTED }
                release.complete(Unit); oldFinished.await(); yield()
                assertEquals(NativeFeedAvailability.CONNECTED, coordinator.sources.value[mac("a").origin]?.availability)
                assertEquals(2, attempts)
            } finally { release.complete(Unit); coordinator.close() }
        }
    }

    @Test fun replyUsesOnlyTheRequestedExistingMacAndCannotSurviveItsRetirement() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            val listing = JSONObject().put("workspaces", JSONArray().put(JSONObject().put("id", "w").put("title", "W")
                .put("terminals", JSONArray().put(JSONObject().put("id", "s").put("title", "S")))))
            a.workspaceResponse = listing; b.workspaceResponse = listing
            var connects = 0; var allowed = true
            val coordinator = NativeFeedCoordinator(this, { row -> connects++; (if (row.deviceId == "a") a else b).connect() }, { allowed })
            val target = PhoneReplyDirectTarget(NativeTeamScope("login", "user", "team", 0), mac("b").origin, "epoch",
                PhonePushPeer(PhonePushTuple("user", null, "fixture", "phone", "physical-b", "stable", "fixture.mac"),
                    PhonePushIdentity.generate().descriptor()), "w", "s", false)
            try {
                assertNull(coordinator.replyAttempt(mac("b"), target) { true }); assertEquals(0, connects)
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.availability == NativeFeedAvailability.CONNECTED } == 2 }
                assertNull(coordinator.replyAttempt(mac("a"), target) { true })
                val attempt = checkNotNull(coordinator.replyAttempt(mac("b"), target) { true })
                assertEquals(PhoneReplyDirectResult.DELIVERED, attempt.send(" literal λ 中\n") { true })
                assertEquals(2, connects); assertTrue(a.requests.none { it.optString("method") == "terminal.paste" })
                val sent = b.requests.single { it.optString("method") == "terminal.paste" }.getJSONObject("params")
                assertEquals("w", sent.getString("workspace_id")); assertEquals("s", sent.getString("surface_id"))
                assertEquals(" literal λ 中\n", sent.getString("text")); assertEquals("return", sent.getString("submit_key"))
                assertEquals(PhoneReplyDirectResult.UNKNOWN, attempt.send("must not repeat") { true })
                val retired = checkNotNull(coordinator.replyAttempt(mac("b"), target) { true })
                allowed = false
                assertEquals(PhoneReplyDirectResult.UNAVAILABLE, retired.send("must not write") { true })
                assertEquals(1, b.requests.count { it.optString("method") == "terminal.paste" })
            } finally { coordinator.close() }
        } }
    }

    @Test fun directReplyRespectsDetachOnSecondaryMacAndRechecksPreparedAttempts() = runBlocking {
        FeedPeer("b").use { peer ->
            peer.workspaceResponse = JSONObject().put("workspaces", JSONArray().put(JSONObject().put("id", "w").put("title", "W")
                .put("terminals", JSONArray().put(JSONObject().put("id", "s").put("title", "S")))))
            val detached = java.util.concurrent.atomic.AtomicBoolean(false)
            val coordinator = NativeFeedCoordinator(this, { peer.connect().also { client ->
                client.terminalTrafficAllowed = { surface -> surface != "s" || !detached.get() }
            } }, { true })
            val target = PhoneReplyDirectTarget(NativeTeamScope("login", "user", "team", 0), mac("b").origin, "epoch",
                PhonePushPeer(PhonePushTuple("user", null, "fixture", "phone", "physical-b", "stable", "fixture.mac"),
                    PhonePushIdentity.generate().descriptor()), "w", "s", false)
            try {
                coordinator.updateMacs(listOf(mac("b")))
                awaitState { coordinator.sources.value[mac("b").origin]?.availability == NativeFeedAvailability.CONNECTED }
                val prepared = checkNotNull(coordinator.replyAttempt(mac("b"), target) { true })
                detached.set(true)
                assertNull(coordinator.replyAttempt(mac("b"), target) { true })
                assertEquals(PhoneReplyDirectResult.UNAVAILABLE, prepared.send("must not write") { true })
                assertTrue(peer.requests.none { it.optString("method") == "terminal.paste" })
                detached.set(false) // Explicit reattach restores direct eligibility.
                val fresh = checkNotNull(coordinator.replyAttempt(mac("b"), target) { true })
                assertEquals(PhoneReplyDirectResult.DELIVERED, fresh.send("new reply") { true })
                assertEquals(1, peer.requests.count { it.optString("method") == "terminal.paste" })
            } finally { coordinator.close() }
        }
    }

    @Test fun malformedWorkspaceSnapshotPreservesLastInventoryUntilConfirmedEmpty() = runBlocking<Unit> {
        FeedPeer("a").use { peer ->
            val paired = mac("a")
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(paired))
                awaitState { coordinator.sources.value[paired.origin]?.let {
                    it.hasWorkspaceSnapshot && it.availability == NativeFeedAvailability.CONNECTED
                } == true }
                peer.workspaceResponse = JSONObject()
                coordinator.refresh()
                assertEquals("w", coordinator.sources.value[paired.origin]?.workspaces?.single()?.id)
                assertEquals("Original", coordinator.sources.value[paired.origin]?.workspaces?.single()?.title)
                peer.workspaceResponse = JSONObject().put("workspaces", JSONArray())
                coordinator.refresh()
                awaitState { coordinator.sources.value[paired.origin]?.let {
                    it.hasWorkspaceSnapshot && it.workspaces.isEmpty() && it.availability == NativeFeedAvailability.CONNECTED
                } == true }
            } finally { coordinator.close() }
        }
    }

    @Test fun savedRouteChangeWakesOnlyItsBuildWhileDiscoveryIsUnavailable() = runBlocking<Unit> {
        val device = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"
        val a = mac(device).copy(code = "cmux-ios://attach?v=3&i=endpoint-a&d=$device&b=default", instanceTag = "default")
        val b = a.copy(code = "cmux-ios://attach?v=3&i=endpoint-b&d=$device&b=debug", instanceTag = "debug")
        val attempts = mutableMapOf<String, Int>()
        val coordinator = NativeFeedCoordinator(this, { mac ->
            attempts[mac.instanceTag!!] = (attempts[mac.instanceTag] ?: 0) + 1
            error("fixture offline")
        }, { true })
        try {
            val key = NativeMacIdentity(canonicalMacDeviceId(device), "default")
            val initial = mapOf(key to "route-one", key.copy(buildTag = "debug") to "sibling-route")
            coordinator.updateMacs(listOf(a, b), localRouteKeys = initial)
            awaitState { attempts["default"] == 1 && attempts["debug"] == 1 }
            coordinator.updateMacs(listOf(a, b), localRouteKeys = initial + (key to "route-two"))
            withTimeout(1000) { while (attempts["default"] != 2) delay(10) }
            assertEquals(1, attempts["debug"])
        } finally { coordinator.close() }
    }

    @Test fun methodChangeWakesOnlyChangedMacWithoutWaitingForOfflineBackoff() = runBlocking<Unit> {
        val a = mac("a").copy(code = "cmux-ios://attach?v=3&i=endpoint-a&d=a")
        val b = mac("b").copy(code = "cmux-ios://attach?v=3&i=endpoint-b&d=b")
        val attempts = mutableMapOf<String, Int>()
        val coordinator = NativeFeedCoordinator(this, { mac ->
            attempts[mac.deviceId] = (attempts[mac.deviceId] ?: 0) + 1
            error("fixture offline")
        }, { true })
        try {
            val initial = mapOf("endpoint-a" to "iroh", "endpoint-b" to "iroh")
            coordinator.updateMacs(listOf(a, b), initial)
            awaitState { attempts["a"] == 1 && attempts["b"] == 1 }
            coordinator.updateMacs(listOf(a, b), initial + ("endpoint-a" to "tailscale"))
            withTimeout(1000) { while (attempts["a"] != 2) delay(10) }
            assertEquals(1, attempts["b"])
        } finally { coordinator.close() }
    }

    @Test fun keepAwakeIsSeededPerMacAndEventsNeverMutatePowerOrAnotherComputer() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            a.powerSupported = true; a.powerValue = true
            b.powerSupported = true; b.powerValue = false
            val first = mac("a").copy(instanceTag = "default")
            val second = mac("b").copy(instanceTag = "default")
            val coordinator = NativeFeedCoordinator(this, { if (it == first) a.connect() else b.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(first, second))
                awaitState { coordinator.sources.value[first.origin]?.keepAwake == true &&
                    coordinator.sources.value[second.origin]?.keepAwake == false }
                a.powerEvent(false, "unowned-stream"); a.powerEvent("false")
                delay(40)
                assertEquals(true, coordinator.sources.value[first.origin]?.keepAwake)
                a.powerEvent(false)
                awaitState { coordinator.sources.value[first.origin]?.keepAwake == false }
                assertEquals(false, coordinator.sources.value[second.origin]?.keepAwake)
                b.powerEvent(true)
                awaitState { coordinator.sources.value[second.origin]?.keepAwake == true }
                assertEquals(false, coordinator.sources.value[first.origin]?.keepAwake)
                assertTrue((a.requests + b.requests).none { it.optString("method") == "caffeine.set" })
                coordinator.workspaceAction(first, "w", "rename", "Still connected")
                assertEquals("Still connected", coordinator.sources.value[first.origin]?.workspaces?.single()?.title)
                b.disconnect()
                awaitState { coordinator.sources.value[second.origin]?.availability == NativeFeedAvailability.OFFLINE }
                assertNull(coordinator.sources.value[second.origin]?.keepAwake)
                coordinator.pause()
                assertTrue(coordinator.sources.value.values.all { it.keepAwake == null })
            } finally { coordinator.close() }
        } }
    }

    @Test fun failedPowerReadDoesNotBreakFeedAndPausedSnapshotNeverKeepsCup() = runBlocking {
        FeedPeer("a").use { peer ->
            peer.powerSupported = true; peer.powerValue = "true"
            val paired = mac("a").copy(instanceTag = "default")
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(paired))
                awaitState { coordinator.sources.value[paired.origin]?.availability == NativeFeedAvailability.CONNECTED &&
                    peer.requests.any { it.optString("method") == "caffeine.status" } }
                assertNull(coordinator.sources.value[paired.origin]?.keepAwake)
                coordinator.workspaceAction(paired, "w", "rename", "Power is optional")
                peer.powerEvent(true)
                awaitState { coordinator.sources.value[paired.origin]?.keepAwake == true }
                coordinator.pause()
                assertNull(coordinator.sources.value[paired.origin]?.keepAwake)
                val gate = java.util.concurrent.CountDownLatch(1)
                peer.hostStatusGate = gate
                try {
                    coordinator.updateMacs(listOf(paired))
                    assertNull(coordinator.sources.value[paired.origin]?.keepAwake)
                    assertEquals(NativeFeedAvailability.CONNECTING, coordinator.sources.value[paired.origin]?.availability)
                } finally { gate.countDown() }
                coordinator.updateMacs(emptyList())
                assertTrue(coordinator.sources.value.isEmpty())
            } finally { coordinator.close() }
        }
    }

    @Test fun repeatedPauseRemovesBothOwnedStreamsAndPreservesAnotherConsumersWire() = runBlocking {
        FeedPeer("a").use { peer ->
            peer.powerSupported = true; peer.powerValue = true
            val paired = mac("a").copy(instanceTag = "default")
            peer.connect().use { retainedClient ->
                val coordinator = NativeFeedCoordinator(this, { retainedClient.lease {} }, { true })
                try {
                    repeat(3) { cycle ->
                        coordinator.updateMacs(listOf(paired))
                        awaitState { coordinator.sources.value[paired.origin]?.let {
                            it.keepAwake == true && it.availability == NativeFeedAvailability.CONNECTED
                        } == true }
                        val subscribed = peer.requests.filter { it.optString("method") == "mobile.events.subscribe" }
                            .map { it.getJSONObject("params").getString("stream_id") }.toSet()
                        assertEquals((cycle + 1) * 2, subscribed.size)
                        coordinator.pause()
                        assertNull(coordinator.sources.value[paired.origin]?.keepAwake)
                        awaitState {
                            peer.requests.filter { it.optString("method") == "mobile.events.unsubscribe" }
                                .map { it.getJSONObject("params").getString("stream_id") }.toSet().containsAll(subscribed)
                        }
                        assertFalse(retainedClient.isClosed)
                        assertEquals("a", retainedClient.hostStatus().getString("mac_device_id"))
                    }
                } finally { coordinator.close() }
            }
        }
    }

    @Test fun legacyPairingNeedsARealHostBuildBeforeObservingPower() = runBlocking {
        for (build in listOf(JSONObject.NULL, 12, "", "default")) FeedPeer("a").use { peer ->
            peer.powerSupported = true; peer.powerValue = true; peer.hostBuild = build
            val paired = mac("a")
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(paired))
                awaitState { coordinator.sources.value[paired.origin]?.availability == NativeFeedAvailability.CONNECTED }
                if (build == "default") awaitState { coordinator.sources.value[paired.origin]?.keepAwake == true }
                else {
                    assertNull(coordinator.sources.value[paired.origin]?.keepAwake)
                    assertTrue(peer.requests.none { it.optString("method").startsWith("caffeine.") })
                }
            } finally { coordinator.close() }
        }
    }

    @Test fun unsupportedMacDoesNotReceivePowerSubscriptionOrRead() = runBlocking {
        FeedPeer("a").use { peer ->
            val paired = mac("a").copy(instanceTag = "default")
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(paired))
                awaitState { coordinator.sources.value[paired.origin]?.availability == NativeFeedAvailability.CONNECTED }
                assertNull(coordinator.sources.value[paired.origin]?.keepAwake)
                assertTrue(peer.requests.none { it.optString("method").startsWith("caffeine.") })
                assertTrue(peer.requests.filter { it.optString("method") == "mobile.events.subscribe" }
                    .none { it.getJSONObject("params").getJSONArray("topics").toString().contains("caffeine") })
            } finally { coordinator.close() }
        }
    }

    @Test fun sidebarCallerCanWithdrawWorkspaceAndGroupWritesBeforeRpc() = runBlocking {
        FeedPeer("a").use { peer ->
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.hasWorkspaceSnapshot == true }
                assertTrue(runCatching { coordinator.workspaceAction(mac("a"), "w", "rename", "Blocked") { false } }.isFailure)
                assertTrue(runCatching { coordinator.groupAction(mac("a"), "g", "delete") { false } }.isFailure)
                assertTrue(peer.requests.none { it.optString("method") in setOf("workspace.action", "workspace.group.action") })
            } finally { coordinator.close() }
        }
    }

    @Test fun workspaceActionsRefreshOnlyTheirOwnerEvenWithCollidingIdsAndRejectedWrites() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            val coordinator = NativeFeedCoordinator(this, { m -> (if (m.deviceId == "a") a else b).connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.hasWorkspaceSnapshot } == 2 }
                coordinator.workspaceAction(mac("b"), "w", "rename", "Renamed")
                assertEquals("Renamed", coordinator.sources.value[mac("b").origin]!!.workspaces.single().title)
                assertEquals("Original", coordinator.sources.value[mac("a").origin]!!.workspaces.single().title)
                assertTrue(a.requests.none { it.optString("method") == "workspace.action" })
                assertEquals("window-b", b.requests.single { it.optString("method") == "workspace.action" }
                    .getJSONObject("params").getString("window_id"))
                b.rejectWorkspaceAction = true
                b.workspaceTitle = "Changed elsewhere"
                val failure = runCatching { coordinator.workspaceAction(mac("b"), "w", "pin") }.exceptionOrNull()
                assertTrue(failure?.message?.contains("Rejected by fixture") == true)
                assertEquals("Changed elsewhere", coordinator.sources.value[mac("b").origin]!!.workspaces.single().title)
                coordinator.updateMacs(listOf(mac("a")))
                assertTrue(runCatching { coordinator.workspaceAction(mac("b"), "w", "close") }.isFailure)
                assertTrue(a.requests.none { it.optString("method") == "workspace.close" })
            } finally { coordinator.close() }
        } }
    }

    @Test fun retainedRowsCannotMutateBeforeReconnectedHostIdentityIsVerified() = runBlocking {
        FeedPeer("a").use { peer ->
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            val gate = java.util.concurrent.CountDownLatch(1)
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.items?.isNotEmpty() == true }
                coordinator.pause()
                peer.hostStatusGate = gate
                val statuses = peer.requests.count { it.optString("method") == "mobile.host.status" }
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { peer.requests.count { it.optString("method") == "mobile.host.status" } > statuses }
                assertTrue(runCatching { coordinator.workspaceAction(mac("a"), "w", "pin") }.isFailure)
                assertTrue(peer.requests.none { it.optString("method") == "workspace.action" })
                gate.countDown()
                awaitState { coordinator.sources.value.values.single().availability == NativeFeedAvailability.CONNECTED }
                coordinator.workspaceAction(mac("a"), "w", "pin")
                assertEquals(1, peer.requests.count { it.optString("method") == "workspace.action" })
            } finally { gate.countDown(); coordinator.close() }
        }
    }

    @Test fun workspaceSnapshotsAdvanceWhileNotificationRevisionIsStale() = runBlocking {
        FeedPeer("a").use { peer ->
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.items?.isNotEmpty() == true }
                peer.mutationRevision.set(5); peer.overrideFeedRevision = 1
                coordinator.setRead(aggregateNativeFeed(coordinator.sources.value.values).single(), true)
                peer.workspaceTitle = "New workspace state"
                coordinator.refresh()
                val source = coordinator.sources.value.values.single()
                assertEquals("New workspace state", source.workspaces.single().title)
                assertEquals("Group a", source.groups.single().name)
                assertTrue("workspace.group_actions.v1" in source.capabilities)
                assertTrue(source.items.single().isRead)
                assertEquals(1L, source.revision)
            } finally { coordinator.close() }
        }
    }

    @Test fun independentMacReadsOfflineRetentionAndForgetting() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            val allowed = mutableSetOf("a", "b")
            val coordinator = NativeFeedCoordinator(this, { m -> (if (m.deviceId == "a") a else b).connect() }, { it.deviceId in allowed })
            try {
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.availability == NativeFeedAvailability.CONNECTED } == 2 }
                val entries = aggregateNativeFeed(coordinator.sources.value.values)
                assertEquals(2, entries.size)
                coordinator.setRead(entries.single { it.source.mac.deviceId == "b" }, true)
                assertFalse(coordinator.sources.value[mac("a").origin]!!.items.single().isRead)
                assertTrue(coordinator.sources.value[mac("b").origin]!!.items.single().isRead)
                assertTrue(a.requests.none { it.optString("method") == "notification.feed.mark_read" })
                assertEquals("shared", b.requests.single { it.optString("method") == "notification.feed.mark_read" }
                    .getJSONObject("params").getJSONArray("notification_ids").getString(0))
                b.disconnect()
                awaitState { coordinator.sources.value[mac("b").origin]?.availability == NativeFeedAvailability.OFFLINE }
                assertEquals(1, coordinator.sources.value[mac("b").origin]!!.items.size)
                allowed.remove("b")
                coordinator.updateMacs(listOf(mac("a")))
                assertNull(coordinator.sources.value[mac("b").origin])
            } finally { coordinator.close() }
        } }
    }

    @Test fun bulkReadStaysWithinCapturedComputerScopeIncludingHiddenRetainedRows() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            val coordinator = NativeFeedCoordinator(this, { m -> (if (m.deviceId == "a") a else b).connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.availability == NativeFeedAvailability.CONNECTED } == 2 }
                coordinator.markAllRead(mac("b").origin)
                assertTrue(a.requests.none { it.optString("method") == "notification.feed.mark_all_read" })
                assertEquals(1, b.requests.count { it.optString("method") == "notification.feed.mark_all_read" })
                coordinator.markAllRead("forgotten-origin")
                assertTrue(a.requests.none { it.optString("method") == "notification.feed.mark_all_read" })
                assertFalse(coordinator.sources.value[mac("a").origin]!!.items.single().isRead)
            } finally { coordinator.close() }
        } }
    }

    @Test fun replacementComputerAtSameRouteCannotAcceptOldRowActions() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            val first = mac("a").copy(code = "same-route")
            val replacement = mac("b").copy(code = "same-route")
            val coordinator = NativeFeedCoordinator(this, { m -> (if (m.deviceId == "a") a else b).connect() }, { true })
            try {
                coordinator.updateMacs(listOf(first))
                awaitState { coordinator.sources.value[first.origin]?.items?.isNotEmpty() == true }
                val old = aggregateNativeFeed(coordinator.sources.value.values).single()
                coordinator.updateMacs(listOf(replacement))
                assertNull(coordinator.sources.value[first.origin])
                awaitState { coordinator.sources.value[replacement.origin]?.items?.isNotEmpty() == true }
                assertTrue(runCatching { coordinator.setRead(old, true) }.isFailure)
                assertTrue(b.requests.none { it.optString("method") == "notification.feed.mark_read" })
                assertFalse(coordinator.sources.value[replacement.origin]!!.items.single().isRead)
            } finally { coordinator.close() }
        } }
    }

    @Test fun notificationWritesRejectReplacedPairingAndWithdrawnPresentationBeforeSending() = runBlocking {
        FeedPeer("a").use { peer ->
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.items?.isNotEmpty() == true }
                val entry = aggregateNativeFeed(coordinator.sources.value.values).single()
                val replaced = entry.copy(source = entry.source.copy(mac = entry.source.mac.copy(code = "old-code")))
                assertTrue(runCatching { coordinator.setRead(replaced, true) }.isFailure)
                assertTrue(runCatching { coordinator.setRead(entry, true) { false } }.isFailure)
                assertTrue(runCatching { coordinator.markNotificationsRead(listOf(entry.source.mac)) { false } }.isFailure)
                assertTrue(runCatching { coordinator.markNotificationsRead(listOf(replaced.source.mac)) }.isFailure)
                assertTrue(peer.requests.none { it.optString("method") in setOf("notification.feed.mark_read", "notification.feed.mark_all_read") })
            } finally { coordinator.close() }
        }
    }

    @Test fun staleFeedCannotUndoAcknowledgedReadOrUnread() = runBlocking {
        FeedPeer("a").use { peer ->
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.items?.isNotEmpty() == true }
                peer.mutationRevision.set(3)
                peer.overrideFeedRevision = 2
                peer.forceUnread = true
                val entry = aggregateNativeFeed(coordinator.sources.value.values).single()
                coordinator.setRead(entry, true)
                coordinator.refresh()
                assertEquals(1, coordinator.sources.value.values.single().revision)
                assertTrue(coordinator.sources.value.values.single().items.single().isRead)
                peer.mutationRevision.set(4)
                coordinator.setRead(entry, false)
                coordinator.refresh()
                assertEquals(1, coordinator.sources.value.values.single().revision)
                assertFalse(coordinator.sources.value.values.single().items.single().isRead)
                assertEquals(1, peer.requests.count { it.optString("method") == "notification.feed.mark_unread" })
            } finally { coordinator.close() }
        }
    }

    @Test fun pauseRetainsSnapshotAndMutationFloorUntilFreshReconnection() = runBlocking {
        FeedPeer("a").use { peer ->
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.items?.isNotEmpty() == true }
                peer.mutationRevision.set(5); peer.overrideFeedRevision = 1; peer.forceUnread = true
                coordinator.setRead(aggregateNativeFeed(coordinator.sources.value.values).single(), true)
                coordinator.pause()
                assertTrue(coordinator.sources.value.values.single().items.single().isRead)
                assertEquals(NativeFeedAvailability.OFFLINE, coordinator.sources.value.values.single().availability)
                val previousLists = peer.requests.count { it.optString("method") == "notification.feed.list" }
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { peer.requests.count { it.optString("method") == "notification.feed.list" } > previousLists }
                coordinator.refresh()
                assertTrue(coordinator.sources.value.values.single().items.single().isRead)
                peer.overrideFeedRevision = 6
                coordinator.refresh()
                awaitState { coordinator.sources.value.values.single().revision == 6L }
                assertFalse(coordinator.sources.value.values.single().items.single().isRead)
                coordinator.close()
                assertTrue(coordinator.sources.value.isEmpty())
            } finally { coordinator.close() }
        }
    }

    @Test fun bulkReadReportsOfflineMacWithoutLosingSuccessfulMacUpdate() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            val coordinator = NativeFeedCoordinator(this, { m -> (if (m.deviceId == "a") a else b).connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.items.isNotEmpty() } == 2 }
                b.disconnect()
                awaitState { coordinator.sources.value[mac("b").origin]?.availability == NativeFeedAvailability.OFFLINE }
                val result = runCatching { coordinator.markAllRead() }
                assertTrue(result.exceptionOrNull()?.message?.contains("Mac b") == true)
                assertTrue(coordinator.sources.value[mac("a").origin]!!.items.single().isRead)
                assertFalse(coordinator.sources.value[mac("b").origin]!!.items.single().isRead)
                assertEquals(1, a.requests.count { it.optString("method") == "notification.feed.mark_all_read" })
                assertEquals(0, b.requests.count { it.optString("method") == "notification.feed.mark_all_read" })
            } finally { coordinator.close() }
        } }
    }
}

private class FeedPeer(private val id: String) : AutoCloseable {
    private val server = ServerSocket(0)
    private val sockets = CopyOnWriteArrayList<Socket>()
    val requests = CopyOnWriteArrayList<JSONObject>()
    val mutationRevision = AtomicInteger(2)
    @Volatile var overrideFeedRevision: Int? = null
    @Volatile var forceUnread = false
    @Volatile var powerSupported = false
    @Volatile var rowActionsSupported = true
    var ticket: MobileAttachTicketContext? = null
    @Volatile var accountMutationsSupported = true
    @Volatile var groupActionsSupported = true
    @Volatile var groupCreationSupported = false
    @Volatile var newGroupCreationSupported = false
    @Volatile var rejectedMethods = emptySet<String>()
    @Volatile var createResponse: JSONObject? = null
    @Volatile var changesSupported = false
    @Volatile var artifactsSupported = false
    @Volatile var panelArtifactsSupported = false
    @Volatile var artifactGate: java.util.concurrent.CountDownLatch? = null
    @Volatile var changedFiles = 2
    @Volatile var summaryError: String? = null
    @Volatile var hostBuild: Any = "default"
    @Volatile var powerValue: Any = false
    private val powerStreams = CopyOnWriteArrayList<Pair<Socket, String>>()
    @Volatile var hostStatusGate: java.util.concurrent.CountDownLatch? = null
    @Volatile var workspaceTitle = "Original"
    @Volatile var workspaceResponse: JSONObject? = null
    @Volatile var rejectWorkspaceAction = false
    @Volatile private var read = false
    @Volatile private var revision = 1
    @Volatile private var closed = false
    init {
        Thread {
            while (!closed) try {
                val socket = server.accept(); sockets += socket
                Thread { serve(socket) }.apply { isDaemon = true; start() }
            } catch (_: Exception) { break }
        }.apply { isDaemon = true; start() }
    }
    suspend fun connect() = MobileRpcClient(PairingCode.Route("127.0.0.1", server.localPort), { "fixture" }, ticket).also { it.connect() }
    private fun serve(socket: Socket) {
        try { socket.use {
            val input = socket.getInputStream()
            while (!closed) {
                val header = input.readNBytes(4)
                if (header.size != 4) break
                val size = header.fold(0) { n, b -> (n shl 8) or (b.toInt() and 255) }
                val request = JSONObject(String(input.readNBytes(size), Charsets.UTF_8)); requests += request
                if (request.getString("method") == "mobile.host.status") hostStatusGate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
                val result = when (request.getString("method")) {
                    "mobile.host.status" -> JSONObject().put("mac_device_id", id).put("mac_instance_tag", hostBuild)
                        .put("capabilities", JSONArray().also {
                            if (accountMutationsSupported) it.put(WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY)
                            if (groupActionsSupported) it.put("workspace.group_actions.v1")
                            if (groupCreationSupported) it.put("workspace.create_in_group.v1")
                            if (newGroupCreationSupported) it.put("workspace.group_create.v1")
                            if (rowActionsSupported) it.put("workspace.actions.v1").put("workspace.read_state.v1").put("workspace.close.v1")
                            if (powerSupported) it.put("caffeine.control.v1")
                            if (changesSupported) it.put(WORKSPACE_CHANGES_CAPABILITY)
                            if (artifactsSupported) it.put("terminal.artifact.v1").put("chat.artifact.gallery.v1")
                            if (panelArtifactsSupported) it.put("panel.artifact.v1")
                        })
                    "mobile.workspace.changes.summary" -> JSONObject().put("summaries", JSONArray().put(JSONObject()
                        .put("workspace_id", "w").put("is_repo", true).put("files_changed", changedFiles).put("additions", 4).put("deletions", 1)))
                    "caffeine.status" -> JSONObject().put("enabled", powerValue)
                    "mobile.events.subscribe" -> JSONObject().also {
                        val params = request.getJSONObject("params")
                        if (params.getJSONArray("topics").toString().contains("caffeine.status.changed"))
                            powerStreams += socket to params.getString("stream_id")
                    }
                    "mobile.events.unsubscribe" -> JSONObject().also {
                        val stream = request.getJSONObject("params").getString("stream_id")
                        powerStreams.removeAll { it.first === socket && it.second == stream }
                    }
                    "mobile.workspace.list" -> workspaceResponse ?: JSONObject().put("workspaces", JSONArray().put(JSONObject().put("id", "w")
                        .put("window_id", "window-" + id).put("title", workspaceTitle)))
                        .put("groups", JSONArray().put(JSONObject().put("id", "g").put("name", "Group " + id)))
                    "workspace.create" -> createResponse ?: JSONObject().put("workspaces", JSONArray())
                    "terminal.paste" -> JSONObject().put("submitted", true)
                    "workspace.action" -> JSONObject().also {
                        if (!rejectWorkspaceAction && request.getJSONObject("params").optString("action") == "rename")
                            workspaceTitle = request.getJSONObject("params").getString("title")
                    }
                    "notification.feed.list" -> JSONObject().put("revision", overrideFeedRevision ?: revision)
                        .put("notifications", JSONArray().put(JSONObject().put("id", "shared").put("workspace_id", "w")
                            .put("created_at", 1).put("is_read", if (forceUnread) false else read)))
                    "notification.feed.mark_read", "notification.feed.mark_unread", "notification.feed.mark_all_read" -> {
                        read = request.getString("method") != "notification.feed.mark_unread"
                        revision = mutationRevision.get()
                        JSONObject().put("revision", revision)
                    }
                    else -> if (request.getString("method").contains(".artifact.")) {
                        artifactGate?.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        JSONObject().put("owner", id)
                    } else JSONObject()
                }
                val summaryFailure = summaryError.takeIf { request.getString("method") == "mobile.workspace.changes.summary" }
                val rejected = (request.getString("method") == "workspace.action" && rejectWorkspaceAction) || summaryFailure != null || request.getString("method") in rejectedMethods
                val response = JSONObject().put("id", request.getString("id")).put("ok", !rejected)
                if (rejected) response.put("error", JSONObject().put("code", summaryFailure ?: "fixture_rejected").put("message", "Rejected by fixture"))
                else response.put("result", result)
                send(socket, response)
            }
        } } catch (_: Exception) { }
    }
    private fun send(socket: Socket, frame: JSONObject) = synchronized(socket) {
        socket.getOutputStream().write(MobileFrameCodec.encode(frame.toString().toByteArray()))
        socket.getOutputStream().flush()
    }
    fun powerEvent(value: Any, overrideStream: String? = null) {
        powerStreams.forEach { (socket, stream) -> if (!socket.isClosed) send(socket,
            JSONObject().put("kind", "event").put("stream_id", overrideStream ?: stream)
                .put("topic", "caffeine.status.changed").put("payload", JSONObject().put("enabled", value))) }
    }
    fun disconnect() { sockets.forEach { runCatching { it.close() } } }
    override fun close() { closed = true; disconnect(); server.close() }
}
