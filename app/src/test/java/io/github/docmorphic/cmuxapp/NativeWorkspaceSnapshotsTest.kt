package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeWorkspaceSnapshotsTest {
    private var login = "first-login"
    private val snapshots = NativeWorkspaceSnapshots { login }
    private val mac = NativeCredentialStore.PairedMac("route", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA", "Mac", "stable", "user", "team")
    private fun listing(title: String = "Workspace") = JSONObject("""{"workspaces":[{"id":"workspace","title":"$title","terminals":[]}]}""")

    @Test fun lateCreateCallbacksUseNewerInventoryAndCannotResurrectADeletedWorkspace() = runTest {
        val returned = parseWorkspaces(listing("create response")).single()
        assertTrue(snapshots.read(mac) { listing("newer host state") }.accept())
        assertEquals("newer host state", snapshots.createdWorkspace(mac, returned).title)
        assertTrue(snapshots.read(mac) { JSONObject("{\"workspaces\":[]}") }.accept())
        assertTrue(runCatching { snapshots.createdWorkspace(mac, returned) }.isFailure)
        snapshots.mutate(mac) { }
        assertNull(snapshots.latest(mac))
        assertEquals(returned, snapshots.createdWorkspace(mac, returned))
    }
    @Test fun aCompletedOldReadCannotPublishAfterANewerRead() = runTest {
        val old = snapshots.read(mac) { listing("old") }
        val newer = snapshots.read(mac) { listing("new") }
        assertTrue(newer.accept()); assertFalse(old.accept())
        assertTrue(newer.accept()) // The same synchronous publication can update multiple UI axes.
    }
    @Test fun aLaterFailedReadDoesNotSuppressAnEarlierValidSnapshot() = runTest {
        val old = snapshots.read(mac) { listing() }
        assertTrue(runCatching { snapshots.read(mac) { JSONObject() } }.isFailure)
        assertTrue(old.accept())
    }
    @Test fun responseAfterANewerPublicationRefetchesWithoutReturningOldData() = runTest {
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        val slow = async { snapshots.read(mac) {
            if (++requests == 1) { gate.await(); listing("old") } else listing("fresh")
        } }
        runCurrent()
        assertTrue(snapshots.read(mac) { listing("new") }.accept())
        gate.complete(Unit)
        assertEquals("fresh", slow.await().workspaces.single().title); assertEquals(2, requests)
    }
    @Test fun pendingMutationInvalidatesOldReadsAndDefersNewReadsUntilCompletion() = runTest {
        val old = snapshots.read(mac) { listing() }
        val gate = CompletableDeferred<Unit>()
        val mutation = launch { snapshots.mutate(mac) { gate.await() } }
        runCurrent(); assertFalse(old.accept()); assertTrue(snapshots.hasMutation(mac))
        assertFalse(snapshots.hasMutation(mac.copy(instanceTag = "nightly")))
        var reads = 0
        val waiting = async { snapshots.read(mac) { reads++; listing("after create") } }
        runCurrent(); assertEquals(0, reads)
        gate.complete(Unit); mutation.join()
        assertTrue(waiting.await().accept()); assertEquals(1, reads); assertFalse(snapshots.hasMutation(mac))
    }
    @Test fun rejectedAndCancelledMutationsStillInvalidateEarlierInventories() = runTest {
        val old = snapshots.read(mac) { listing() }
        assertTrue(runCatching { snapshots.mutate(mac) { throw java.io.IOException("Unknown result") } }.isFailure)
        assertFalse(old.accept())
        val middle = snapshots.read(mac) { listing() }
        val cancelled = launch { snapshots.mutate(mac) { awaitCancellation() } }
        runCurrent(); cancelled.cancelAndJoin()
        assertFalse(middle.accept()); assertTrue(snapshots.read(mac) { listing() }.accept())
    }
    @Test fun overlappingMutationsKeepReadsWaitingUntilBothFinish() = runTest {
        val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        val a = launch { snapshots.mutate(mac) { first.await() } }
        val b = launch { snapshots.mutate(mac) { second.await() } }
        runCurrent()
        var reads = 0
        val read = async { snapshots.read(mac) { reads++; listing() } }
        first.complete(Unit); a.join(); runCurrent(); assertEquals(0, reads)
        second.complete(Unit); b.join(); assertTrue(read.await().accept()); assertEquals(1, reads)
    }
    @Test fun accountTeamBuildAndMacKeysIsolateCollidingWorkspaceIds() = runTest {
        val base = snapshots.read(mac) { listing() }
        for (other in listOf(mac.copy(accountUserId = "other"), mac.copy(accountTeamId = "other"),
            mac.copy(deviceId = "other"), mac.copy(instanceTag = "nightly"))) {
            snapshots.mutate(other) { }
            assertTrue(base.accept())
        }
        snapshots.mutate(mac.copy(deviceId = mac.deviceId.lowercase(), code = "new route", name = "Renamed")) { }
        assertFalse(base.accept())
    }
    @Test fun retiringAnOwnerAndSigningOutFenceReturnedSnapshots() = runTest {
        snapshots.retain(listOf(mac))
        val old = snapshots.read(mac) { listing() }
        snapshots.retain(emptyList()); assertFalse(old.accept())
        val next = snapshots.read(mac) { listing() }
        login = "second-login"; assertFalse(next.accept())
        val latest = snapshots.read(mac) { listing() }; snapshots.clear(); assertFalse(latest.accept())
    }
    @Test fun retiredReadCannotBeResurrectedWhenTheSameOwnerIsReadded() = runTest {
        val gate = CompletableDeferred<Unit>()
        val read = async { runCatching { snapshots.read(mac) { gate.await(); listing() } } }
        runCurrent(); snapshots.clear()
        assertTrue(snapshots.read(mac) { listing("new session") }.accept())
        gate.complete(Unit); assertTrue(read.await().exceptionOrNull() is NativeWorkspaceSnapshotSuperseded)
    }
    @Test fun waitingReadHasABoundedNonCancellationTimeoutAndNeverRepeatsMutation() = runTest {
        var mutations = 0
        val gate = CompletableDeferred<Unit>()
        val mutation = launch { snapshots.mutate(mac) { mutations++; gate.await() } }
        runCurrent()
        val read = async { runCatching { snapshots.read(mac) { error("Must wait") } } }
        advanceTimeBy(30_001); runCurrent()
        assertTrue(read.await().exceptionOrNull() is java.io.IOException)
        assertTrue(currentCoroutineContext().isActive); assertEquals(1, mutations)
        gate.complete(Unit); mutation.join()
        assertTrue(snapshots.read(mac) { listing() }.accept())
    }
}
