package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeAuthenticatedSnapshotTest {
    private val a = NativeAuthenticatedOwner(NativeAuthIdentity(1,"account-a"),"login-a",1)
    private val b = NativeAuthenticatedOwner(NativeAuthIdentity(2,"account-b"),"login-a",1)
    private class Fixture(var view: NativeStoredAuthSession,work:NativeAuthWork=NativeAuthWork(),timeout:Long=60_000) {
        var storedBlocked=false;var transition=false;var resolutions=0;var bootstraps=0
        var available=true
        var resolve:suspend ()->String?={ "resolved" }
        var bootstrap:suspend ()->Unit={}
        var reader:()->NativeStoredAuthSession={ if(!available)throw NativeCredentialReadException();view }
        val snapshots=NativeAuthenticatedSnapshots({reader()},{storedBlocked},{transition},
            {bootstraps++;bootstrap()},{resolutions++;resolve()},{view.owner},{view.pending},work,timeout,{available})
    }
    private fun fixture(access:String?="stored",refresh:String?="refresh")=Fixture(NativeStoredAuthSession(a,access,refresh))
    private suspend fun failure(reason:NativeSignInError,body:suspend ()->Unit) {
        try {body();fail("Expected $reason")} catch(error:NativeSignInFailure) {assertEquals(reason,error.reason)}
    }
    @Test fun completeStoredPairBypassesBootstrapValidationAndAccessExpiryResolution()=runTest {
        val f=fixture(access="stale-but-refreshable");f.transition=true
        f.bootstrap={error("Must not await validation")};f.resolve={error("Must not refresh")}
        val result=f.snapshots.session();assertEquals(a,result.owner);assertEquals("stale-but-refreshable",result.accessToken)
        assertEquals("refresh",result.refreshToken);assertEquals(0,f.bootstraps);assertEquals(0,f.resolutions)
    }
    @Test fun incompletePairAwaitsBootstrapThenResolvesTheLiveStore()=runTest {
        val f=fixture(access=null);val release=CompletableDeferred<Unit>();f.bootstrap={release.await()}
        val result=async {f.snapshots.session()};runCurrent();assertFalse(result.isCompleted);assertEquals(0,f.resolutions)
        release.complete(Unit);assertEquals("resolved",result.await().accessToken);assertEquals(1,f.resolutions)
    }
    @Test fun signInAndSignOutWriterWindowsAreRetryableWithoutReadingAccess()=runTest {
        val f=fixture();f.storedBlocked=true;f.transition=true
        failure(NativeSignInError.NETWORK_ERROR){f.snapshots.session()};assertEquals(0,f.resolutions)
        f.transition=false;assertEquals("resolved",f.snapshots.session().accessToken)
    }
    @Test fun incompletePairDuringForegroundValidationIsRetryable()=runTest {
        val f=fixture(access=null);f.transition=true
        failure(NativeSignInError.NETWORK_ERROR){f.snapshots.session()};assertEquals(0,f.resolutions)
    }
    @Test fun aRefreshRotationAcrossResolutionRetriesInsteadOfReturningATornPair()=runTest {
        val f=fixture(access=null);f.resolve={
            if(f.resolutions==1)f.view=NativeStoredAuthSession(a,"new-access","new-refresh")
            "new-access"
        }
        val result=f.snapshots.session();assertEquals("new-refresh",result.refreshToken);assertEquals("new-access",result.accessToken)
        assertEquals(2,f.resolutions)
    }
    @Test fun threeCrossingRotationsAreRetryableAndNeverClearTheSession()=runTest {
        val f=fixture(access=null);f.resolve={f.view=NativeStoredAuthSession(a,"a","rotation-${f.resolutions}");"a"}
        failure(NativeSignInError.NETWORK_ERROR){f.snapshots.session()};assertEquals(3,f.resolutions);assertEquals(a,f.view.owner)
    }
    @Test fun aDifferentUserUnderTheSameStoredLoginCannotCompleteOldCapture()=runTest {
        val f=fixture(access=null);f.resolve={f.view=NativeStoredAuthSession(b,"b-access","b-refresh");"b-access"}
        failure(NativeSignInError.UNAUTHORIZED){f.snapshots.session()}
    }
    @Test fun returningToTheSameUserWithANewGenerationCannotReviveACapture()=runTest {
        val f=fixture(access=null);f.resolve={f.view=NativeStoredAuthSession(a.copy(identity=a.identity.copy(generation=3)),"a","refresh");"a"}
        failure(NativeSignInError.UNAUTHORIZED){f.snapshots.session()};assertFalse(f.snapshots.isCurrent(a))
    }
    @Test fun aSharedWriterEpochChangeFencesASeparateAccountObject()=runTest {
        val f=fixture(access=null);f.resolve={f.view=NativeStoredAuthSession(a.copy(epoch=2),"a","refresh");"a"}
        failure(NativeSignInError.UNAUTHORIZED){f.snapshots.session()}
    }
    @Test fun accessMissWithSurvivingRefreshIsRetryableButAnEmptyStoreIsUnauthorized()=runTest {
        val f=fixture(access=null);f.resolve={null}
        failure(NativeSignInError.NETWORK_ERROR){f.snapshots.session()}
        f.resolve={f.view=NativeStoredAuthSession(a,null,null);null}
        failure(NativeSignInError.UNAUTHORIZED){f.snapshots.session()}
    }
    @Test fun missingIdentityCannotBorrowStoredTokens()=runTest {
        val f=Fixture(NativeStoredAuthSession(null,"stored","refresh"))
        failure(NativeSignInError.UNAUTHORIZED){f.snapshots.session()};assertEquals(0,f.resolutions)
    }
    @Test fun orphanedPendingTokensStayRetryableEvenWithoutALiveWriterOrPublishedIdentity()=runTest {
        val f=Fixture(NativeStoredAuthSession(null,"unverified","unverified-refresh",pending=true))
        failure(NativeSignInError.NETWORK_ERROR){f.snapshots.session()};assertEquals(0,f.resolutions)
        failure(NativeSignInError.UNAUTHORIZED){f.snapshots.refresh()};assertFalse(f.snapshots.isCurrent(a))
    }
    @Test fun refreshOnlySnapshotAwaitsBootstrapAndNeverResolvesAccess()=runTest {
        val f=fixture(access=null);val result=f.snapshots.refresh()
        assertEquals(a,result.owner);assertEquals("refresh",result.refreshToken);assertEquals(1,f.bootstraps);assertEquals(0,f.resolutions)
        f.transition=true;failure(NativeSignInError.UNAUTHORIZED){f.snapshots.refresh()}
    }
    @Test fun refreshOnlyCaptureRejectsIdentityReplacementButAllowsSameOwnerRotation()=runTest {
        val f=fixture(access=null)
        f.reader={f.view.also{f.view=NativeStoredAuthSession(b,null,"replacement")}}
        failure(NativeSignInError.UNAUTHORIZED){f.snapshots.refresh()}
        f.view=NativeStoredAuthSession(a,null,"original")
        f.reader={f.view.also{f.view=NativeStoredAuthSession(a,null,"rotated")}}
        assertEquals("original",f.snapshots.refresh().refreshToken)
    }
    @Test fun currentChecksUseIdentityAndEpochRatherThanExactTokenStrings() {
        val f=fixture();assertTrue(f.snapshots.isCurrent(a))
        f.view=NativeStoredAuthSession(a,"rotated-access","rotated-refresh");assertTrue(f.snapshots.isCurrent(a))
        f.transition=true;assertFalse(f.snapshots.isCurrent(a));f.transition=false
        f.view=NativeStoredAuthSession(b,"b","r");assertFalse(f.snapshots.isCurrent(a))
    }
    @Test fun cancelledCaptureNeverResolvesAccessAndDescriptionsRedactAllSecrets()=runTest {
        val f=fixture(access=null);val gate=CompletableDeferred<Unit>();f.bootstrap={gate.await()}
        val task=launch {f.snapshots.session()};runCurrent();task.cancelAndJoin();assertEquals(0,f.resolutions)
        val texts=listOf(a.toString(),NativeAuthenticatedSessionSnapshot(a,"secret-access","secret-refresh").toString(),
            NativeAuthenticatedRefreshSnapshot(a,"secret-refresh").toString(),f.view.toString())
        texts.forEach {assertFalse(it.contains("account-a"));assertFalse(it.contains("login-a"));assertFalse(it.contains("secret"))}
    }
    @Test fun sharedFlowLeaseKeepsPreflightBlockedAndSeparatesPublicationFromFinalHooks() {
        val work=NativeAuthWork();val lease=work.beginSignIn()
        assertTrue(work.storedSessionCaptureBlocked);assertTrue(work.sessionWriteInProgress)
        lease.publish();assertTrue(work.storedSessionCaptureBlocked);assertFalse(work.sessionWriteInProgress)
        lease.finish();lease.finish();assertFalse(work.storedSessionCaptureBlocked);assertFalse(work.sessionWriteInProgress)
        val capture=work.beginSignOutCapture();assertTrue(work.storedSessionCaptureBlocked);assertTrue(work.sessionWriteInProgress)
        capture();capture();assertFalse(work.sessionWriteInProgress)
    }
    @Test fun unavailableStorageIsRetryableOnlyAfterThePublishedIdentityGuard()=runTest {
        val f=fixture();f.available=false
        failure(NativeSignInError.NETWORK_ERROR){f.snapshots.session()}
        failure(NativeSignInError.NETWORK_ERROR){f.snapshots.refresh()}
        assertEquals(a,f.view.owner);assertEquals(0,f.resolutions)
        f.view=NativeStoredAuthSession(null,null,null);f.transition=true
        failure(NativeSignInError.UNAUTHORIZED){f.snapshots.session()}
        failure(NativeSignInError.UNAUTHORIZED){f.snapshots.refresh()}
    }
    @Test fun identityReplacementWinsOverATransitionAfterSuccessfulCoherentCapture()=runTest {
        val f=fixture(access=null)
        f.resolve={f.transition=true;f.view=NativeStoredAuthSession(b,"new","refresh");"new"}
        failure(NativeSignInError.UNAUTHORIZED){f.snapshots.session()}
        f.view=NativeStoredAuthSession(a,null,"refresh");f.transition=false
        f.resolve={f.transition=true;"resolved"}
        failure(NativeSignInError.NETWORK_ERROR){f.snapshots.session()}
    }
    @Test fun threeRotationAttemptsShareOneAbsoluteCaptureDeadline()=runTest {
        val work=NativeAuthWork(nowMillis={testScheduler.currentTime})
        val f=Fixture(NativeStoredAuthSession(a,null,"refresh"),work,1000)
        f.resolve={delay(400);f.view=NativeStoredAuthSession(a,null,"rotation-${f.resolutions}");"resolved"}
        failure(NativeSignInError.TIMED_OUT){f.snapshots.session()}
        assertEquals(1000,testScheduler.currentTime);assertEquals(3,f.resolutions);runCurrent()
        assertEquals(0,work.activeCount);assertEquals(a,f.view.owner)
    }
    @Test fun bootstrapPrecedesTheSingleTokenCaptureBudget()=runTest {
        val work=NativeAuthWork(nowMillis={testScheduler.currentTime})
        val f=Fixture(NativeStoredAuthSession(a,null,"refresh"),work,100)
        f.bootstrap={delay(1500)};f.resolve={delay(101);"resolved"}
        failure(NativeSignInError.TIMED_OUT){f.snapshots.session()}
        assertEquals(1600,testScheduler.currentTime);assertEquals(1,f.resolutions);runCurrent();assertEquals(0,work.activeCount)
    }
    @Test fun aLateSuccessOrFailureCannotOutliveTheCaptureDeadline()=runTest {
        for(throws in listOf(false,true)) {
            var elapsed=0L;val work=NativeAuthWork(nowMillis={elapsed})
            val f=Fixture(NativeStoredAuthSession(a,null,"refresh"),work)
            f.resolve={elapsed=60_001;if(throws)throw NativeSignInFailure(NativeSignInError.UNAUTHORIZED);"resolved"}
            failure(NativeSignInError.TIMED_OUT){f.snapshots.session()};assertEquals(a,f.view.owner);assertEquals(0,work.activeCount)
        }
    }
    @Test fun canceledCaptureWaiterLeavesBlockedActualStorageTrackedUntilItRetires()=runBlocking<Unit> {
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val work=NativeAuthWork();val f=Fixture(NativeStoredAuthSession(a,null,"refresh"),work);f.storedBlocked=true
        f.reader={entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));f.view}
        val task=launch(Dispatchers.IO){f.snapshots.session()}
        try {
            assertTrue(withContext(Dispatchers.IO){entered.await(2,java.util.concurrent.TimeUnit.SECONDS)})
            task.cancel();withTimeout(1000){task.join()};assertTrue(work.activeCount>0)
            release.countDown();withTimeout(2000){while(work.activeCount>0)delay(1)}
            assertEquals(a,f.view.owner);assertEquals(0,f.resolutions)
        }finally{release.countDown();task.cancelAndJoin()}
    }
}
