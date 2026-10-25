package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeCoherentTokensTest {
    private suspend fun failure(reason:NativeSignInError,body:suspend()->Unit) {
        val result=runCatching{body()}.exceptionOrNull();assertTrue(result is NativeSignInFailure)
        assertEquals(reason,(result as NativeSignInFailure).reason)
    }
    @Test fun noRefreshClassifiesFromCapturedAvailabilityWithoutResolvingAccess()=runTest {
        for(available in listOf(false,true))failure(if(available)NativeSignInError.UNAUTHORIZED else NativeSignInError.NETWORK_ERROR) {
            nativeCoherentPair({available},{null},{error("No refresh cannot resolve access")})
        }
    }
    @Test fun liveResolutionRetriesARotationAndNeverReturnsATornPair()=runTest {
        var refresh="old";var calls=0
        val result=nativeCoherentPair({true},{refresh},{calls++;refresh="new";"fresh"})
        assertEquals(2,calls);assertEquals("fresh",result.access);assertEquals("new",result.refresh)
        assertFalse(result.toString().contains("fresh"))
    }
    @Test fun threeCrossingRotationsAreRetryableWithoutAStateClear()=runTest {
        var refresh="initial";var calls=0
        failure(NativeSignInError.NETWORK_ERROR){nativeCoherentPair({true},{refresh},{refresh="rotation-${++calls}";"fresh"})}
        assertEquals(3,calls);assertEquals("rotation-3",refresh)
    }
    @Test fun failedAccessDistinguishesSurvivingRefreshFromDefinitiveRejection()=runTest {
        var refresh:String?="refresh"
        failure(NativeSignInError.NETWORK_ERROR){nativeCoherentPair({true},{refresh},{null})}
        failure(NativeSignInError.UNAUTHORIZED){nativeCoherentPair({true},{refresh},{refresh=null;null})}
        assertNull(refresh)
    }
    @Test fun unavailableReadsUseOneCapturedAvailabilityVerdict()=runTest {
        for(wasAvailable in listOf(false,true)) {
            var probes=0
            failure(if(wasAvailable)NativeSignInError.UNAUTHORIZED else NativeSignInError.NETWORK_ERROR) {
                nativeCoherentPair({probes++;wasAvailable},{throw NativeCredentialReadException()},{error("No token")})
            }
            assertEquals(1,probes)
        }
    }
    @Test fun onePhaseBoundsAllCoherenceAttempts()=runTest {
        val work=NativeAuthWork(nowMillis={testScheduler.currentTime});var refresh="initial";var calls=0
        failure(NativeSignInError.TIMED_OUT){work.run(NativeAuthPhase.REFRESH,1000){
            nativeCoherentPair({true},{refresh},{calls++;delay(400);refresh="next-$calls";"access"})}}
        assertEquals(1000,testScheduler.currentTime);assertEquals(3,calls);runCurrent();assertEquals(0,work.activeCount)
    }
    @Test fun contextPinsOptionalTeamAndEverySourceGenerationGuard()=runTest {
        val initial=NativeCloudContextStamp(3,1,7,null)
        for(after in listOf(initial.copy(session=4),initial.copy(epoch=2),initial.copy(routing=8),initial.copy(team="team"))) {
            var current=initial
            failure(NativeSignInError.NETWORK_ERROR){nativeCloudTokenContext({current},{false}){current=after;NativeCoherentPair("a","r")}}
        }
        failure(NativeSignInError.NETWORK_ERROR){nativeCloudTokenContext({initial},{true}){NativeCoherentPair("a","r")}}
        assertNull(nativeCloudTokenContext({initial},{false}){NativeCoherentPair("a","r")}.team)
    }
    @Test fun cloudScopeSupportsPersonalRoutesAndFencesProofChangesWithoutClosingItsBackendOwner() {
        val scopes=NativeCloudScopeRegistry();val principal=NativeCloudPrincipal("login","user",1,1)
        assertNull(scopes.scope(null,null,null))
        val personal=checkNotNull(scopes.scope(principal,null,null));assertNull(personal.team)
        assertEquals(personal,scopes.scope(principal,null,null))
        val a=checkNotNull(scopes.scope(principal,"a",NativeTeamScope("login","user","a",1)))
        scopes.scope(principal,"b",NativeTeamScope("login","user","b",2))
        val a2=checkNotNull(scopes.scope(principal,"a",NativeTeamScope("login","user","a",3)))
        assertNotEquals(a,a2)
        val route=scopes.routingGeneration
        val unverified=checkNotNull(scopes.scope(principal,"a",null));assertEquals(a2,unverified);assertTrue(scopes.routingGeneration>route)
        val retired=scopes.routingGeneration
        assertEquals(unverified,scopes.scope(principal,"a",NativeTeamScope("login","user","a",4)))
        assertTrue(scopes.routingGeneration>retired)
        assertNotEquals(personal,scopes.scope(principal.copy(epoch=2),null,null))
        scopes.scope(null,null,null);assertNotEquals(personal,scopes.scope(principal,null,null))
    }
    @Test fun emptySuccessfulUserCanOwnCloudWithoutCreatingANativeMacProof() {
        val scopes=NativeCloudScopeRegistry();val value=scopes.scope(NativeCloudPrincipal("login","",1,1),null,null)
        assertEquals("",value!!.user);assertNull(value.team)
        val saved=CloudScreenCheckpoint("login","",null,"vm",CloudAddress("vm","ws").identifier,null)
        assertEquals(saved,CloudScreenCheckpoint.decode(saved.encode()));assertTrue(saved.matches(value))
        assertEquals(CloudVpnOwner("",null),CloudVpnOwner(value.user,value.team))
    }
    @Test fun cloudObserversSeeSameEmptyUserReloginEvenWhenTheIdentityStreamStaysNil()=runTest {
        val user=NativeSignInUser("",null,null,null)
        val model=NativeSessionRestore(false,null,{},{null},{true},{false},{null},{null},{true},{},{})
        val values=mutableListOf<Long>();val observer=launch{model.generations.take(4).toList(values)}
        runCurrent();model.publishedSignIn(user);runCurrent();model.publishedSignIn(user);runCurrent();model.clear(false);runCurrent()
        observer.join();assertEquals(listOf(0L,1L,2L,3L),values);assertNull(model.identity())
    }
    @Test fun bootstrapAndCaptureShareAnAbsoluteDeadlineAndTheBootstrapWaitIsNotTokenOwnership()=runTest {
        val work=NativeAuthWork(nowMillis={testScheduler.currentTime})
        failure(NativeSignInError.TIMED_OUT){nativeCurrentTokens(work,bootstrap={delay(700)},writer={false},generation={1},
            capture={delay(400);NativeCoherentPair("a","r")},timeoutMillis=1000,nowMillis={testScheduler.currentTime})}
        assertEquals(1000,testScheduler.currentTime);runCurrent();assertEquals(0,work.activeCount)
    }
    @Test fun timedOutBootstrapWaitCannotBlockANewTokenCaptureOrSignInQuiescence()=runTest {
        val work=NativeAuthWork(nowMillis={testScheduler.currentTime});val release=CompletableDeferred<Unit>()
        failure(NativeSignInError.TIMED_OUT){nativeCurrentTokens(work,bootstrap={withContext(NonCancellable){release.await()}},
            writer={false},generation={1},capture={error("Bootstrap not complete")},timeoutMillis=100,nowMillis={testScheduler.currentTime})}
        assertEquals(1,work.activeCount)
        assertEquals(2,work.run(NativeAuthPhase.REFRESH,100){2})
        work.quiesce(100);assertEquals(1,work.activeCount)
        release.complete(Unit);runCurrent();assertEquals(0,work.activeCount)
    }
    @Test fun absoluteDeadlineRejectsLateAdmissionAndOverridesLateSuccessOrFailure()=runTest {
        var now=1000L;val work=NativeAuthWork(nowMillis={now});var calls=0
        failure(NativeSignInError.TIMED_OUT){work.run(NativeAuthPhase.REFRESH,100,absoluteDeadlineMillis=1000){calls++;1}}
        assertEquals(0,calls)
        for(throws in listOf(false,true)) {
            now=1000
            failure(NativeSignInError.TIMED_OUT){work.run(NativeAuthPhase.REFRESH,100,absoluteDeadlineMillis=1050){
                now=1050;if(throws)throw NativeSignInFailure(NativeSignInError.NETWORK_ERROR);1}}
        }
    }
    @Test fun currentTokensUsesItsWorkClockForBothBudgetStagesByDefault()=runTest {
        var elapsed=0L;val work=NativeAuthWork(nowMillis={elapsed})
        failure(NativeSignInError.TIMED_OUT){nativeCurrentTokens(work,bootstrap={elapsed=59_000},writer={false},generation={1},
            capture={elapsed=60_001;NativeCoherentPair("a","r")})}
        assertEquals(0,work.activeCount)
    }
}
