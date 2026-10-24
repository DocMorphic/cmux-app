package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeSignInCompletionTest {
    private val user = NativeSignInUser("fixture-user", "Fixture", "fixture@example.test", "fixture-team")
    private val environment = "https://fixture.example/api/v1/#project"
    private class Store {
        val lock = Any()
        private var root = JSONObject().put("pairings", JSONArray().put("keep-pairing")).put("sentinel", "keep")
        fun load(): JSONObject = synchronized(lock) { JSONObject(root.toString()) }
        fun update(transform: (JSONObject) -> Unit) = synchronized(lock) {
            val next = JSONObject(root.toString()); transform(next); root = next
        }
        fun preserved() { assertEquals("keep-pairing", load().getJSONArray("pairings").getString(0)); assertEquals("keep", load().getString("sentinel")) }
        fun clearSession() = update { it.remove("access_token"); it.remove("refresh_token"); it.remove("task_session"); it.remove(NativeSignInCompletion.PENDING) }
    }
    private fun completion(store: Store, fetch: suspend (String) -> NativeSignInUser,
        timeout: Long = 60_000, hook: suspend (String, NativeSignInUser) -> Unit = { _, _ -> }, work: NativeAuthWork = NativeAuthWork()) =
        NativeSignInCompletion(store::load, store::update, fetch, environment, store.lock, timeout, hook, work = work)
    private fun assertCleared(store: Store) {
        assertFalse(store.load().optString("refresh_token").isNotBlank())
        assertFalse(store.load().has("task_session")); assertFalse(NativeSignInCompletion.isPending(store.load())); store.preserved()
    }

    @Test fun aBlockedCredentialWriteCannotHoldTheDeadlineOrLetARetrySkipActualCleanup() = runBlocking {
        val store = Store(); val work = NativeAuthWork(); val written = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        var firstWrite = true; var exchanges = 0
        val flow = NativeSignInCompletion(store::load, { transform ->
            store.update(transform)
            if (firstWrite) { firstWrite = false; written.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        }, { user }, environment, store.lock, networkTimeoutMillis = 100, work = work)
        try {
            val old = async(Dispatchers.Default) { runCatching { flow.signIn { write -> exchanges++; withContext(Dispatchers.IO) { write("old", "old-refresh") } } } }
            written.await()
            val failure = withTimeout(2000) { old.await() }.exceptionOrNull() as Exception
            assertEquals(NativeSignInError.TIMED_OUT, nativeSignInError(failure)); assertEquals(1L, release.count)
            assertTrue(work.activeCount > 0)
            val retry = async(Dispatchers.Default) { runCatching { flow.signIn { exchanges++; it("too-early", "too-early-refresh") } } }
            assertEquals(NativeSignInError.TIMED_OUT, nativeSignInError(withTimeout(2000) { retry.await() }.exceptionOrNull() as Exception))
            assertEquals(1, exchanges)
        } finally { release.countDown() }
        work.quiesce(5000); assertCleared(store)
        flow.signIn { it("new", "new-refresh") }; assertEquals("new-refresh", store.load().getString("refresh_token")); store.preserved()
    }

    @Test fun userDeadlineReturnsBeforeAnUncooperativeFetchAndDiscardsItsLateUser() = runBlocking {
        val store = Store(); val work = NativeAuthWork(); val fetching = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val flow = NativeSignInCompletion(store::load, store::update, { withContext(NonCancellable) { fetching.complete(Unit); release.await(); user } },
            environment, store.lock, networkTimeoutMillis = 100, work = work)
        try {
            val attempt = async { runCatching { flow.signIn { it("access", "refresh") } } }; fetching.await()
            val failure = withTimeout(2000) { attempt.await() }.exceptionOrNull() as Exception
            assertEquals(NativeSignInError.TIMED_OUT, nativeSignInError(failure)); assertFalse(release.isCompleted)
            assertTrue(NativeSignInCompletion.isPending(store.load())); assertFalse(store.load().has(NativeAccountProfileCache.KEY))
        } finally { release.complete(Unit) }
        work.quiesce(5000); assertTrue(NativeSignInCompletion.isPending(store.load()))
        assertFalse(store.load().has(NativeAccountProfileCache.KEY)); store.preserved()
    }

    @Test fun toleratedMembershipTimeoutKeepsTheUserButItsLateWorkStillBlocksANewExchange() = runBlocking {
        val store = Store(); val work = NativeAuthWork(); val hook = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val flow = NativeSignInCompletion(store::load, store::update, { user }, environment, store.lock, networkTimeoutMillis = 100,
            onPublished = { _, _ -> withContext(NonCancellable) { hook.complete(Unit); release.await() } }, work = work)
        try {
            val attempt = async { flow.signIn { it("access", "refresh") } }; hook.await(); withTimeout(2000) { attempt.await() }
            assertFalse(NativeSignInCompletion.isPending(store.load())); assertTrue(work.activeCount > 0)
            var exchanged = false
            val retry = runCatching { flow.signIn { exchanged = true } }
            assertEquals(NativeSignInError.TIMED_OUT, nativeSignInError(retry.exceptionOrNull() as Exception)); assertFalse(exchanged)
        } finally { release.complete(Unit) }
        work.quiesce(5000); assertEquals("refresh", store.load().getString("refresh_token")); store.preserved()
    }

    @Test fun orphanRestoreRefreshesARejectedAccessTokenWithoutReplacingItsLoginReceipt() = runBlocking {
        val store = Store(); var refreshed = 0; var fetches = 0
        store.update { it.put("task_session", "pending").put(NativeSignInCompletion.PENDING, "pending")
            .put("access_token", "expired").put("refresh_token", "refresh") }
        val flow = NativeSignInCompletion(store::load, store::update, { token ->
            fetches++; if (token == "expired") throw NativeSignInFailure(NativeSignInError.UNAUTHORIZED)
            assertEquals("fresh", token); user
        }, environment, store.lock, refreshAccess = { token -> assertEquals("refresh", token); refreshed++; "fresh" })
        flow.restorePending(); assertEquals(2, fetches); assertEquals(1, refreshed)
        assertEquals("pending", store.load().getString("task_session")); assertEquals("fresh", store.load().getString("access_token"))
        assertEquals("refresh", store.load().getString("refresh_token")); assertFalse(NativeSignInCompletion.isPending(store.load())); store.preserved()
    }

    @Test fun aLateOrphanRefreshCannotOverwriteOrClearANewerWriter() = runBlocking {
        val store = Store(); val refreshing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        store.update { it.put("task_session", "old").put(NativeSignInCompletion.PENDING, "old")
            .put("access_token", "expired").put("refresh_token", "old-refresh") }
        val flow = NativeSignInCompletion(store::load, store::update, { throw NativeSignInFailure(NativeSignInError.UNAUTHORIZED) },
            environment, store.lock, refreshAccess = { withContext(NonCancellable) { refreshing.complete(Unit); release.await(); "late-access" } })
        val restore = async { runCatching { flow.restorePending() } }; refreshing.await()
        store.update { it.put("task_session", "new").put(NativeSignInCompletion.PENDING, "new")
            .put("access_token", "new-access").put("refresh_token", "new-refresh") }
        release.complete(Unit); assertTrue(restore.await().isFailure)
        assertEquals("new-access", store.load().getString("access_token")); assertEquals("new-refresh", store.load().getString("refresh_token"))
        assertTrue(NativeSignInCompletion.isPending(store.load())); store.preserved()
    }

    @Test fun anUnconfirmedRefreshFailureRetainsThePendingCredentialsForALaterRestore() = runBlocking {
        val store = Store()
        store.update { it.put("task_session", "pending").put(NativeSignInCompletion.PENDING, "pending")
            .put("access_token", "expired").put("refresh_token", "keep-refresh") }
        val flow = NativeSignInCompletion(store::load, store::update, { throw NativeSignInFailure(NativeSignInError.UNAUTHORIZED) },
            environment, store.lock, refreshAccess = { throw NativeSignInFailure(NativeSignInError.SERVER_ERROR) })
        assertTrue(runCatching { flow.restorePending() }.isFailure)
        assertTrue(NativeSignInCompletion.isPending(store.load())); assertEquals("keep-refresh", store.load().getString("refresh_token")); store.preserved()
    }

    @Test fun exchangeAndUserFetchHaveSeparateNetworkDeadlines() = runBlocking {
        val store = Store(); val flow = completion(store, { delay(175); user }, timeout = 300)
        flow.signIn { write -> delay(175); write("access", "refresh") }
        assertFalse(NativeSignInCompletion.isPending(store.load())); assertEquals("refresh", store.load().getString("refresh_token"))
    }

    @Test fun clearingTheCompletedFormCannotCancelThePublishedSessionsMembershipPhase() = runBlocking {
        val store = Store(); val hook = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val flow = completion(store, { user }, hook = { _, _ -> hook.complete(Unit); release.await() })
        val task = async { flow.signIn { it("access", "refresh") } }
        hook.await(); assertFalse(NativeSignInCompletion.isPending(store.load()))
        flow.clearIf { true }; release.complete(Unit); task.await(); assertEquals("refresh", store.load().getString("refresh_token"))
    }

    @Test fun signOutCancelsTheStillTrackedPublishedMembershipPhaseAndClearsTheSession() = runBlocking {
        val store = Store(); val hook = CompletableDeferred<Unit>(); var stopped = false
        val flow = completion(store, { user }, hook = { _, _ -> try { hook.complete(Unit); awaitCancellation() } finally { stopped = true } })
        val task = async { runCatching { flow.signIn { it("access", "refresh") } } }
        hook.await(); assertTrue(flow.clearIf(includePublished = true) { store.clearSession(); true })
        assertTrue(task.await().isFailure); assertTrue(stopped); assertCleared(store)
    }

    @Test fun credentialsStayPendingUntilTheFetchedUserIsPublishedBeforeTheToleratedTeamHook() = runBlocking {
        val store = Store(); val fetching = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var hooks = 0
        val flow = completion(store, { access -> assertEquals("access", access); fetching.complete(Unit); release.await(); user }, hook = { id, fetched ->
            hooks++; assertEquals(user, fetched); assertEquals(id, store.load().getString("task_session"))
            assertFalse(NativeSignInCompletion.isPending(store.load()))
            assertEquals("fixture-user", store.load().getJSONObject(NativeAccountProfileCache.KEY).getString("user"))
        })
        val task = async { flow.signIn { write -> write("access", "refresh") } }
        fetching.await()
        assertTrue(NativeSignInCompletion.isPending(store.load())); assertFalse(store.load().has(NativeAccountProfileCache.KEY)); assertEquals(0, hooks)
        release.complete(Unit); task.await()
        assertEquals(1, hooks); assertEquals("refresh", store.load().getString("refresh_token")); store.preserved()
    }

    @Test fun cancellationAfterTheActualStoreWriteRollsBackOnlyThePendingReceipt() = runBlocking {
        val store = Store(); val fetching = CompletableDeferred<Unit>(); val work = NativeAuthWork()
        val flow = completion(store, { fetching.complete(Unit); awaitCancellation() }, work = work)
        val task = launch { flow.signIn { it("access", "refresh") } }
        fetching.await(); assertTrue(store.load().has("task_session")); flow.cancelPending(); task.join(); work.quiesce(5000); assertCleared(store)
    }

    @Test fun cancellationThatRacesPastTheWriterCheckAndDispatcherReturnStillClearsItsWrite() = runBlocking {
        val store = Store(); val written = CompletableDeferred<Unit>(); val release = CountDownLatch(1); val work = NativeAuthWork()
        var firstWrite = true
        val flow = NativeSignInCompletion(store::load, { transform ->
            store.update(transform)
            if (firstWrite) { firstWrite = false; written.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        }, { fail("A cancelled write must not fetch the user"); user }, environment, store.lock, work = work)
        val task = launch(Dispatchers.Default) { flow.signIn { write -> withContext(Dispatchers.IO) { write("access", "refresh") } } }
        try { written.await(); task.cancel() } finally { release.countDown() }
        task.join(); work.quiesce(5000); assertCleared(store)
    }

    @Test fun signOutDuringAnUncooperativeUserFetchWinsEvenWhenTheResponseArrivesLate() = runBlocking {
        val store = Store(); val fetching = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var hooks = 0
        val flow = completion(store, { withContext(NonCancellable) { fetching.complete(Unit); release.await(); user } }, hook = { _, _ -> hooks++ })
        val task = async { runCatching { flow.signIn { it("access", "refresh") } } }
        fetching.await(); assertTrue(flow.clearIf { store.clearSession(); true }); release.complete(Unit)
        assertTrue(task.await().isFailure); assertCleared(store); assertEquals(0, hooks)
    }

    @Test fun staleRollbackCannotClearANewerActualWriterEvenBeforeThatWriterPublishes() = runBlocking {
        val store = Store(); val fetching = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val flow = completion(store, { withContext(NonCancellable) { fetching.complete(Unit); release.await(); user } })
        val task = async { runCatching { flow.signIn { it("old-access", "old-refresh") } } }
        fetching.await()
        store.update { it.put("task_session", "new-writer").put(NativeSignInCompletion.PENDING, "new-writer")
            .put("access_token", "new-access").put("refresh_token", "new-refresh") }
        task.cancel(); release.complete(Unit); task.join()
        assertEquals("new-refresh", store.load().getString("refresh_token")); assertEquals("new-writer", store.load().getString("task_session"))
        assertTrue(NativeSignInCompletion.isPending(store.load())); store.preserved()
    }

    @Test fun aNewerAttemptThatFailsBeforeWritingDoesNotStealTheOlderWritesCleanup() = runBlocking {
        val store = Store(); val fetching = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val flow = completion(store, { withContext(NonCancellable) { fetching.complete(Unit); release.await(); user } })
        val old = async { runCatching { flow.signIn { it("old-access", "old-refresh") } } }
        fetching.await()
        var secondExchangeStarted = false
        val newer = async { runCatching { flow.signIn { secondExchangeStarted = true; throw IOException("Before write") } } }
        yield(); assertFalse(secondExchangeStarted); release.complete(Unit)
        assertTrue(old.await().isFailure); assertTrue(newer.await().isFailure); assertTrue(secondExchangeStarted); assertCleared(store)
    }

    @Test fun userFetchDeadlineIsBoundedWithoutPublishingOrDestroyingTheStoredSession() = runBlocking {
        val store = Store()
        val flow = completion(store, { awaitCancellation() }, timeout = 100)
        val error = runCatching { flow.signIn { it("access", "refresh") } }.exceptionOrNull()
        assertEquals(NativeSignInError.TIMED_OUT, nativeSignInError(error as Exception))
        assertTrue(NativeSignInCompletion.isPending(store.load())); assertEquals("refresh", store.load().getString("refresh_token"))
        assertFalse(store.load().has(NativeAccountProfileCache.KEY)); store.preserved()
    }

    @Test fun aFailedTeamRefreshKeepsTheAlreadyVerifiedSessionAndUserCache() = runBlocking {
        val store = Store()
        val flow = completion(store, { user }, hook = { _, _ -> throw IOException("Membership unavailable") })
        flow.signIn { it("access", "refresh") }
        assertFalse(NativeSignInCompletion.isPending(store.load()))
        val cache = NativeAccountProfileCache(store::load, store::update).read(store.load().getString("task_session"), environment)!!
        assertEquals("fixture-user", cache.userId); assertEquals("Fixture", cache.displayName); assertTrue(cache.teams.isEmpty()); assertNull(cache.scope)
        store.preserved()
    }

    @Test fun failedUserFetchCannotPublishButItsStoredCredentialsCanBeVerifiedByAnOrphanRestore() = runBlocking {
        val store = Store(); var fail = true; var exchanges = 0
        val flow = completion(store, { if (fail) throw IOException("Offline"); user })
        assertTrue(runCatching { flow.signIn { exchanges++; it("access", "refresh") } }.isFailure)
        assertTrue(NativeSignInCompletion.isPending(store.load())); assertFalse(store.load().has(NativeAccountProfileCache.KEY))
        fail = false; flow.restorePending(); assertEquals(1, exchanges)
        assertFalse(NativeSignInCompletion.isPending(store.load())); assertEquals("refresh", store.load().getString("refresh_token")); store.preserved()
    }

    @Test fun restoreCannotOverlapALiveExchangeAndItsLateResponseCannotUndoSignOut() = runBlocking {
        val store = Store(); val fetching = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var fetches = 0
        val flow = completion(store, { fetches++; withContext(NonCancellable) { fetching.complete(Unit); release.await(); user } })
        val task = async { runCatching { flow.signIn { it("access", "refresh") } } }
        fetching.await(); flow.restorePending(); assertEquals(1, fetches)
        flow.clearIf { store.clearSession(); true }; release.complete(Unit); task.await(); assertCleared(store)
    }

    @Test fun rejectedOrphanRestoreClearsOnlyThatPendingSessionAndLegacySessionsRemainUntouched() = runBlocking {
        val store = Store(); var reject = false; var fetches = 0
        val flow = completion(store, { fetches++; if (reject) throw NativeSignInFailure(NativeSignInError.UNAUTHORIZED); throw IOException("Offline") })
        assertTrue(runCatching { flow.signIn { it("access", "refresh") } }.isFailure)
        reject = true; assertTrue(runCatching { flow.restorePending() }.isFailure); assertCleared(store)
        store.update { it.put("access_token", "legacy-access").put("refresh_token", "legacy-refresh").put("task_session", "legacy-login") }
        flow.restorePending(); assertEquals(2, fetches); assertEquals("legacy-refresh", store.load().getString("refresh_token")); store.preserved()
    }

    @Test fun malformedOrMissingFetchedIdentitiesCannotBecomeAuthenticatedUsers() {
        for (value in listOf(JSONObject(), JSONObject().put("id", 7), JSONObject().put("id", " "), JSONObject().put("id", "ok").put("primary_email", 7),
            JSONObject().put("id", "ok").put("selected_team", "wrong"))) {
            assertThrows(NativeSignInFailure::class.java) { NativeSignInUser.decode(value) }
        }
        assertEquals(user, NativeSignInUser.decode(JSONObject().put("id", user.id).put("display_name", user.name).put("primary_email", user.email)
            .put("selected_team", JSONObject().put("id", user.selectedTeam))))
    }
    @Test fun sharedLogoutEpochRejectsAnOtherwiseActiveExchangeAndLetsTheNextLoginPublish()=runBlocking {
        val store=Store();val epoch=java.util.concurrent.atomic.AtomicLong();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var fetches=0
        val flow=NativeSignInCompletion(store::load,store::update,{fetches++;user},environment,store.lock,
            onCredentialsWritten={epoch.incrementAndGet()},sessionEpoch={epoch.get()})
        val old=async { runCatching { flow.signIn { write -> entered.complete(Unit);release.await();write("old","old-refresh") } } }
        entered.await();synchronized(store.lock) { epoch.incrementAndGet();store.clearSession() }
        release.complete(Unit);assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertEquals(0,fetches);assertCleared(store)
        val fresh=NativeSignInCompletion(store::load,store::update,{user},environment,store.lock,
            onCredentialsWritten={epoch.incrementAndGet()},sessionEpoch={epoch.get()})
        fresh.signIn { it("fresh","fresh-refresh") }
        assertEquals("fresh-refresh",store.load().getString("refresh_token"));assertFalse(NativeSignInCompletion.isPending(store.load()))
    }
    @Test fun sharedLogoutEpochDropsALateUserWithoutPublishingOrRefreshingTeams()=runBlocking {
        val store=Store();val epoch=java.util.concurrent.atomic.AtomicLong();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var verified=0;var memberships=0
        val flow=NativeSignInCompletion(store::load,store::update,{entered.complete(Unit);release.await();user},environment,store.lock,
            onPublished={_,_->memberships++},onCredentialsWritten={epoch.incrementAndGet()},onVerified={_,_->verified++},sessionEpoch={epoch.get()})
        val old=async { runCatching { flow.signIn { it("old","old-refresh") } } }
        entered.await();assertTrue(NativeSignInCompletion.isPending(store.load()))
        synchronized(store.lock) { epoch.incrementAndGet();store.clearSession() }
        release.complete(Unit);assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertEquals(0,verified);assertEquals(0,memberships);assertCleared(store)
    }
    @Test fun sharedLogoutWhileWaitingForOldTokenWorkPreventsLaunchingAnotherExchange()=runBlocking {
        val store=Store();val work=NativeAuthWork();val epoch=java.util.concurrent.atomic.AtomicLong()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var exchanges=0
        val blocker=launch { runCatching { work.run(NativeAuthPhase.REFRESH,5000) {
            withContext(NonCancellable) { entered.complete(Unit);release.await() }
        } } }
        entered.await()
        val flow=NativeSignInCompletion(store::load,store::update,{user},environment,store.lock,work=work,sessionEpoch={epoch.get()})
        val waiting=async { runCatching { flow.signIn { exchanges++;it("old","old-refresh") } } }
        try {
            withTimeout(2000) { while(work.cancelledCount==0)delay(1) }
            epoch.incrementAndGet();release.complete(Unit)
            assertTrue(waiting.await().exceptionOrNull() is CancellationException);assertEquals(0,exchanges);assertCleared(store)
        } finally { release.complete(Unit);blocker.join();waiting.join() }
    }

}
