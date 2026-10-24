package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class NativeSignOutTeardownTest {
    private fun token(payload:String)="header."+Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())+".signature"
    @Test fun freshnessUsesExpirationJitterAndShortLifetimeLimitsRatherThanTokenAge() {
        assertTrue(nativeCapturedAccessIsFresh(token("{}"),100_000,120_000))
        assertFalse(nativeCapturedAccessIsFresh("bad",100_000,60_000))
        assertFalse(nativeCapturedAccessIsFresh(token("{\"exp\":160}"),100_000,60_000))
        assertTrue(nativeCapturedAccessIsFresh(token("{\"exp\":160.001}"),100_000,60_000))
        assertFalse(nativeCapturedAccessIsFresh(token("{\"exp\":190}"),100_000,120_000))
        assertTrue(nativeCapturedAccessIsFresh(token("{\"exp\":190,\"iat\":100}"),100_000,120_000))
        assertFalse(nativeCapturedAccessIsFresh(token("{\"exp\":120,\"iat\":110}"),100_000,60_000))
        assertFalse(nativeCapturedAccessIsFresh(token("{\"exp\":120.0009,\"iat\":110}"),100_000,60_000))
        assertTrue(nativeCapturedAccessIsFresh(token("{\"exp\":600,\"iat\":0}"),100_000,60_000))
        assertEquals("NativeCapturedSession(redacted)",NativeCapturedSession("secret-access","secret-refresh").toString())
    }
    @Test fun resolvesEphemeralAccessThenRunsHookBeforeRevokeAndPreservesCapturedFallback()=runTest {
        val events=mutableListOf<String>()
        nativeSignOutTeardown(NativeCapturedSession("old","refresh"),{a,r->assertEquals("old",a);assertEquals("refresh",r);events+="mint";"minted"},
            {a,r->assertEquals("minted",a);assertEquals("refresh",r);events+="hook"},{assertEquals("minted",it.access);events+="revoke"})
        assertEquals(listOf("mint","hook","revoke"),events)
        nativeSignOutTeardown(NativeCapturedSession("old","refresh"),{_,_->null},{_,_->throw IOException()},{assertEquals("old",it.access)})
        nativeSignOutTeardown(NativeCapturedSession(null,null),{_,_->error("No mint")},{a,r->assertNull(a);assertNull(r)},{error("No revoke")})
    }
    @Test fun nestedCaptureBlocksOwnershipSnapshotsAndCancellationDoesNotReleaseOtherOwners()=runTest {
        val work=NativeAuthWork();val first=work.beginSignOutCapture();val second=work.beginSignOutCapture();var snapshots=0
        val waiting=async { work.afterSignOutCapture { ++snapshots } };runCurrent();assertEquals(0,snapshots)
        val cancelled=async { work.afterSignOutCapture { error("Cancelled waiter cannot snapshot") } };runCurrent();cancelled.cancel();runCurrent()
        first();runCurrent();assertFalse(waiting.isCompleted);second();second();runCurrent()
        assertEquals(1,waiting.await());assertEquals(2,work.afterSignOutCapture { ++snapshots })
    }
    @Test fun deadlineCancelsTheMintAndNeverStartsHookOrRevoke()=runTest {
        val work=NativeAuthWork(teardownDispatcher=StandardTestDispatcher(testScheduler));var hooks=0;var revokes=0
        val job=work.launchSignOutTeardown(80) { nativeSignOutTeardown(NativeCapturedSession(null,"refresh"),
            {_,_->delay(Long.MAX_VALUE);"late"},{_,_->hooks++},{revokes++}) }
        runCurrent();advanceTimeBy(81);runCurrent();job.join()
        assertEquals(0,hooks);assertEquals(0,revokes);assertEquals(0,work.activeCount)
    }
    @Test fun deadlineCannotPermitALateHookToStartRevocation()=runTest {
        val work=NativeAuthWork(teardownDispatcher=StandardTestDispatcher(testScheduler));val release=CompletableDeferred<Unit>();var revokes=0
        val job=work.launchSignOutTeardown(80) { nativeSignOutTeardown(NativeCapturedSession("access",null),{_,_->error("No mint")},
            {_,_->withContext(NonCancellable){release.await()}},{revokes++}) }
        runCurrent();advanceTimeBy(81);runCurrent();assertFalse(job.isCompleted);assertEquals(1,work.activeCount)
        release.complete(Unit);runCurrent();job.join();assertEquals(0,revokes);assertEquals(0,work.activeCount)
    }
    @Test fun freshExchangeWaitsForCaptureThenJoinsActualTeardownBeforeWriting()=runTest {
        val work=NativeAuthWork(teardownDispatcher=StandardTestDispatcher(testScheduler));val end=work.beginSignOutCapture()
        val release=CompletableDeferred<Unit>();var exchanges=0;var epoch=0L;var root=JSONObject()
        val flow=NativeSignInCompletion({JSONObject(root.toString())},{ f->val next=JSONObject(root.toString());f(next);root=next },
            {NativeSignInUser("fresh",null,null,null)},"fixture",work=work,onCredentialsWritten={epoch++},sessionEpoch={epoch})
        val waiting=async { flow.signIn { exchanges++;it("fresh-access","fresh-refresh") } }
        runCurrent();assertEquals(0,exchanges);epoch++
        val tail=work.launchSignOutTeardown(80) { withContext(NonCancellable){release.await()} }
        runCurrent();end();runCurrent();assertEquals(0,exchanges);assertFalse(waiting.isCompleted)
        release.complete(Unit);runCurrent();waiting.await();tail.join()
        assertEquals(1,exchanges);assertEquals("fresh-refresh",root.getString("refresh_token"));assertFalse(NativeSignInCompletion.isPending(root))
    }
    @Test fun cancelledPostSignInEffectsAreJoinedBeforeTheServerTail()=runTest {
        val work=NativeAuthWork(teardownDispatcher=StandardTestDispatcher(testScheduler));val release=CompletableDeferred<Unit>();var server=0
        val hook=launch { runCatching { work.run(NativeAuthPhase.POST_SIGN_IN,1000) { withContext(NonCancellable){release.await()} } } }
        runCurrent();work.cancelAll();val tail=work.launchSignOutTeardown(80) { server++ };runCurrent();assertEquals(0,server)
        release.complete(Unit);runCurrent();hook.join();tail.join();assertEquals(1,server)
    }
}
