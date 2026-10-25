package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class NativeDeletionAuthorityTest {
    private val process=UUID.randomUUID().toString()
    private val a=NativeAuthenticatedOwner(NativeAuthIdentity(1,"account-a"),"login",1)
    private class Store {
        var root=JSONObject().put("task_session","login").put("access_token","access-secret").put("refresh_token","refresh-secret").put("pairing","retain")
        var fail=false
        var unreadable=false
        fun load():JSONObject {if(unreadable)throw NativeCredentialReadException();return JSONObject(root.toString())}
        fun update(change:(JSONObject)->Unit) {if(fail)throw NativeCredentialReadException();val next=load();change(next);NativeAccountDeletionRecord.prune(next);root=next}
    }
    private inner class Fixture(val scope:CoroutineScope) {
        val store=Store();var owner:NativeAuthenticatedOwner?=a;var verified=true;var send=true;var calls=0
        var response:suspend ()->NativeAccountDeletionResult={NativeAccountDeletionResult.COMPLETED}
        fun authority(id:String=process)=NativeDeletionAuthority({owner?.takeIf {o->o.login==it}},{send && it==owner},{verified},{calls++;response()},id)
        fun controller(auth:NativeDeletionAuthority=authority())=NativeAccountDeletionController(scope,store::load,store::update,auth){error("Fixture fallback must not run")}
    }
    @Test fun staleSameUserConfirmationCannotBindToTheReplacementGeneration()=runTest {
        val f=Fixture(this);val c=f.controller();val confirmed=c.confirmationOwner("login")!!
        f.owner=a.copy(identity=a.identity.copy(generation=2))
        assertFalse(c.begin("login",confirmed));runCurrent();assertEquals(0,f.calls);assertNull(NativeAccountDeletionRecord.read(f.store.root))
    }
    @Test fun replacementAfterConfirmationBeforeDispatchCannotSendOrRecreateAnOldResult()=runTest {
        val f=Fixture(this);val c=f.controller();assertTrue(c.begin("login"));f.owner=a.copy(identity=NativeAuthIdentity(2,"account-b"))
        runCurrent();assertEquals(0,f.calls);assertNull(c.state.value)
        c.reconcile();assertTrue(c.begin("login"));runCurrent();assertEquals(1,f.calls)
        assertEquals("account-b",c.state.value!!.binding!!.user)
    }
    @Test fun lateNonCooperativeResponseCannotPublishOrSignOutTheReplacement()=runTest {
        val f=Fixture(this);val gate=CompletableDeferred<Unit>();f.response={withContext(NonCancellable){gate.await()};NativeAccountDeletionResult.COMPLETED}
        val c=f.controller();c.begin("login");runCurrent();assertEquals(1,f.calls)
        f.owner=a.copy(identity=NativeAuthIdentity(2,"account-b"));c.reconcile();assertNull(c.state.value)
        gate.complete(Unit);runCurrent();assertNull(c.state.value)
        var logouts=0;val stale=NativeAccountDeletionRecord.read(f.store.root)!!.copy(result=NativeAccountDeletionResult.COMPLETED)
        assertFalse(c.signOut(stale){logouts++;true});assertEquals(0,logouts)
    }
    @Test fun aCompletedResultCannotSignOutASameUserNewGenerationBeforeTheCollectorRuns()=runTest {
        val f=Fixture(this);val c=f.controller();c.begin("login");runCurrent();val old=c.state.value!!
        f.owner=a.copy(identity=a.identity.copy(generation=2));var logouts=0
        assertTrue(c.signOut(old){logouts++;true});assertEquals(0,logouts);assertNull(c.state.value)
    }
    @Test fun newProcessWaitsForUserVerificationThenConsumesWithoutResending()=runTest {
        val f=Fixture(this);val old=f.controller();old.begin("login");runCurrent();assertEquals(1,f.calls)
        f.owner=a.copy(identity=a.identity.copy(generation=0),epoch=0);f.verified=false
        val restored=f.controller(f.authority(UUID.randomUUID().toString()));assertNull(restored.state.value)
        assertFalse(restored.begin("login"));assertEquals(1,f.calls)
        f.verified=true;restored.reconcile();val result=restored.state.value!!
        assertEquals(NativeAccountDeletionResult.COMPLETED,result.result)
        var applied:NativeAuthenticatedOwner?=null;assertTrue(restored.signOut(result){applied=it;true})
        assertEquals(f.owner,applied);assertEquals(1,f.calls)
    }
    @Test fun verifiedDifferentUserCannotConsumeThePriorProcessResultEvenUnderTheSameLogin()=runTest {
        val f=Fixture(this);val c=f.controller();c.begin("login");runCurrent()
        f.owner=a.copy(identity=NativeAuthIdentity(0,"account-b"),epoch=0)
        val restored=f.controller(f.authority(UUID.randomUUID().toString()));assertNull(restored.state.value)
        assertEquals(1,f.calls)
    }
    @Test fun legacyUnboundSuccessBecomesUncertaintyAndAcknowledgementNeverResends()=runTest {
        val f=Fixture(this);NativeAccountDeletionRecord.write(f.store.root,
            NativeAccountDeletionReceipt(UUID.randomUUID().toString(),"login",NativeAccountDeletionResult.COMPLETED))
        val c=f.controller();val unknown=c.state.value!!;assertEquals(NativeAccountDeletionResult.UNKNOWN,unknown.result)
        assertFalse(c.signOut(unknown){error("Must not sign out")});assertTrue(c.acknowledge(unknown));runCurrent()
        assertEquals(0,f.calls);assertNull(NativeAccountDeletionRecord.read(f.store.root))
    }
    @Test fun boundReceiptSurvivesDefinitiveSdkTokenRemovalAndExplicitLogoutRetiresIt()=runTest {
        val f=Fixture(this);f.response={f.store.update {it.remove("task_session");it.put("access_token","").put("refresh_token","")};NativeAccountDeletionResult.UNAUTHORIZED}
        val c=f.controller();c.begin("login");runCurrent();val result=c.state.value!!
        assertEquals(NativeAccountDeletionResult.UNAUTHORIZED,result.result);assertNotNull(NativeAccountDeletionRecord.read(f.store.root))
        assertTrue(c.signOut(result){f.store.update(NativeLocalLogout::retire);true})
        assertNull(NativeAccountDeletionRecord.read(f.store.root));assertEquals("retain",f.store.root.getString("pairing"))
    }
    @Test fun storageFailureSendsNothingAndBindingDescriptionsContainNoAccountOrCredentials()=runTest {
        val f=Fixture(this);val c=f.controller();f.store.fail=true
        assertFalse(c.begin("login"));runCurrent();assertEquals(0,f.calls)
        val receipt=c.state.value!!;assertEquals(NativeAccountDeletionResult.STORAGE,receipt.result)
        for(text in listOf(receipt.toString(),receipt.binding.toString())) {
            assertFalse(text.contains("account-a"));assertFalse(text.contains("login"));assertFalse(text.contains("secret"))
        }
    }
    @Test fun writerPreflightBlocksNewRequestsWhileSameIdentityRotationKeepsTheConfirmation()=runTest {
        val f=Fixture(this);val c=f.controller();val confirmed=c.confirmationOwner("login")!!
        f.send=false;assertFalse(c.begin("login",confirmed));assertNull(c.confirmationOwner("login"))
        f.send=true;f.store.update {it.put("access_token","rotated").put("refresh_token","rotated-refresh")}
        assertTrue(c.begin("login",confirmed));runCurrent();assertEquals(1,f.calls)
    }
    @Test fun unknownStorageDoesNotMarkTheRecordedAccountStale() {
        val pinned=NativeDeletionBinding("account-a",1,1,process)
        val authority=NativeDeletionAuthority({throw NativeCredentialReadException()},{true},{true},{error("No send")},process)
        val receipt=NativeAccountDeletionReceipt(UUID.randomUUID().toString(),"login",NativeAccountDeletionResult.COMPLETED,pinned)
        assertEquals(NativeDeletionReceiptStatus.WAITING,authority.status(receipt));assertNull(authority.capture("login"))
    }
    @Test fun storageBecomingUnavailableAfterTheStatusCheckCannotEscapeIntoTheAlert() {
        var reads=0
        val authority=NativeDeletionAuthority({if(++reads==1)a else throw NativeCredentialReadException()},{true},{true},{error("No send")},process)
        val receipt=NativeAccountDeletionReceipt(UUID.randomUUID().toString(),"login",NativeAccountDeletionResult.COMPLETED,
            NativeDeletionBinding(a.identity.userId,a.identity.generation,a.epoch,process))
        assertNull(authority.signOutOwner(receipt));assertEquals(2,reads)
    }
    @Test fun knownOutcomeSurvivesUnreadableStorageAndRecoveryWithoutBecomingAResend()=runTest {
        val f=Fixture(this);val release=CompletableDeferred<Unit>()
        f.response={release.await();NativeAccountDeletionResult.REJECTED}
        val c=f.controller();assertTrue(c.begin("login"));runCurrent();f.store.unreadable=true
        c.reconcile();assertEquals(NativeAccountDeletionResult.PROCESSING,c.state.value!!.result)
        release.complete(Unit);runCurrent();assertEquals(NativeAccountDeletionResult.REJECTED,c.state.value!!.result)
        c.reconcile();assertEquals(NativeAccountDeletionResult.REJECTED,c.state.value!!.result)
        assertFalse(c.begin("login"));assertFalse(c.acknowledge(c.state.value!!));assertEquals(1,f.calls)
        f.store.unreadable=false;c.reconcile();assertEquals(NativeAccountDeletionResult.REJECTED,c.state.value!!.result)
        val restarted=f.controller();assertEquals(NativeAccountDeletionResult.UNKNOWN,restarted.state.value!!.result)
        assertTrue(c.acknowledge(c.state.value!!));assertNull(c.state.value);assertEquals(1,f.calls)
    }
    @Test fun unreadableStorageCannotKeepAnOutcomeForAReplacementSession()=runTest {
        val f=Fixture(this);val release=CompletableDeferred<Unit>()
        f.response={withContext(NonCancellable){release.await()};NativeAccountDeletionResult.COMPLETED}
        val c=f.controller();assertTrue(c.begin("login"));runCurrent();f.store.unreadable=true
        f.owner=a.copy(identity=a.identity.copy(generation=2));c.reconcile();assertNull(c.state.value)
        release.complete(Unit);runCurrent();assertNull(c.state.value);assertEquals(1,f.calls)
    }
}
