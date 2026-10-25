package io.github.docmorphic.cmuxapp

import android.os.SystemClock
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Actual shared kernel timer and encrypted disposable store. No provider or real account mutations. */
class NativeAuthWaitsRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private suspend fun idle()=withTimeout(5000){while(NativeContinuousDeadline.pendingCount!=0 || File("/proc/self/fd").listFiles().orEmpty().any{
        runCatching{Os.readlink(it.path)=="anon_inode:[timerfd]"}.getOrDefault(false)})delay(5)}
    private fun cipher()=context.getSharedPreferences(name,0).getString("state",null)
    private fun record(name:String,value:JSONObject) {
        assertEquals("retain",store.requireReadable()!!.getString("pairing_marker"))
        File(context.getExternalFilesDir(null),"auth-waits").apply{mkdirs()}.resolve("$name.json").writeText(
            value.put("fixtureOnly",true).put("encryptedStoreUsed",true).put("pairingPreserved",true)
                .put("noRealSuspendClaim",true).toString(2))
    }
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="auth-waits-fixture-"+UUID.randomUUID();alias="cmux_auth_waits_fixture_"+UUID.randomUUID()
        store=NativeCredentialStore.withKeyProvider(context,name,alias);store.update{it.put("pairing_marker","retain")}
    }
    @After fun cleanup()=runBlocking<Unit> {
        scope.cancel();store.authWork.cancelAll(includingSignOut=true);withTimeout(5000){while(store.authWork.activeCount>0)delay(5)};idle()
        store.clear();context.deleteSharedPreferences(name);KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)
    }
    @Test fun completionWatcherExpiresWithoutCancelingItsOtherOwner()=runBlocking<Unit> {
        val actual=Job();val before=cipher();val start=SystemClock.elapsedRealtime()
        try {
            val failure=runCatching{store.authWork.awaitCompletions(listOf(actual),200)}.exceptionOrNull() as NativeSignInFailure
            val elapsed=SystemClock.elapsedRealtime()-start;assertEquals(NativeSignInError.TIMED_OUT,failure.reason)
            assertTrue(elapsed>=200 && elapsed<3000);assertTrue(actual.isActive);assertEquals(0,store.authWork.activeCount);idle()
            assertEquals(before,cipher());record("completion-watch",JSONObject().put("configuredMillis",200).put("elapsedMillis",elapsed)
                .put("actualOtherOwnerStillActive",true).put("noTokenWorkRegistered",true).put("kernelDescriptorsReleased",true))
        }finally{actual.complete()}
    }
    @Test fun productionPreflightDeadlineLeavesActualRetirementTracked()=runBlocking<Unit> {
        val work=store.authWork;val release=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();val before=cipher()
        val actual=scope.launch{runCatching{work.run(NativeAuthPhase.REFRESH,60_000){entered.complete(Unit);withContext(NonCancellable){release.await()}}}}
        try {
            entered.await();val start=SystemClock.elapsedRealtime();val failure=runCatching{work.quiesce(200)}.exceptionOrNull() as NativeSignInFailure
            val elapsed=SystemClock.elapsedRealtime()-start;assertEquals(NativeSignInError.TIMED_OUT,failure.reason)
            assertTrue(elapsed>=200 && elapsed<3000);assertEquals(1,work.activeCount)
            release.complete(Unit);withTimeout(5000){actual.join()};idle();assertEquals(before,cipher())
            record("preflight-retirement",JSONObject().put("configuredMillis",200).put("elapsedMillis",elapsed)
                .put("actualJobRetainedUntilRelease",true).put("ciphertextPreserved",true).put("kernelDescriptorsReleased",true))
        }finally{release.complete(Unit);actual.cancelAndJoin()}
    }
    @Test fun signInCompletionCannotExchangeBeforeTheProductionPreflightDeadline()=runBlocking<Unit> {
        val work=store.authWork;val release=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();val exchanges=AtomicInteger()
        val actual=scope.launch{runCatching{work.run(NativeAuthPhase.REFRESH,60_000){entered.complete(Unit);withContext(NonCancellable){release.await()}}}}
        try {
            entered.await();val flow=NativeSignInCompletion(store::load,store::update,{NativeSignInUser("fixture-user",null,null,null)},"fixture",
                store.accountStateLock,networkTimeoutMillis=200,work=work)
            val before=cipher();val start=SystemClock.elapsedRealtime()
            val failure=runCatching{flow.signIn{exchanges.incrementAndGet();it("fixture-access","fixture-refresh")}}.exceptionOrNull() as NativeSignInFailure
            val elapsed=SystemClock.elapsedRealtime()-start;assertEquals(NativeSignInError.TIMED_OUT,failure.reason)
            assertTrue(elapsed>=200 && elapsed<3000);assertEquals(0,exchanges.get());assertEquals(before,cipher())
            release.complete(Unit);withTimeout(5000){actual.join();while(work.activeCount>0)delay(5)};idle()
            record("sign-in-preflight",JSONObject().put("configuredMillis",200).put("elapsedMillis",elapsed).put("credentialExchanges",0)
                .put("ciphertextPreserved",true).put("kernelDescriptorsReleased",true))
        }finally{release.complete(Unit);actual.cancelAndJoin()}
    }
    @Test fun postHookJoinAndStructuredServerTailEachReceiveTheirOwnContinuousBudget()=runBlocking<Unit> {
        val work=store.authWork;val hookRelease=CompletableDeferred<Unit>();val hookEntered=CompletableDeferred<Unit>();val serverEntered=CompletableDeferred<Long>()
        val hook=scope.launch{runCatching{work.run(NativeAuthPhase.POST_SIGN_IN,60_000){hookEntered.complete(Unit);withContext(NonCancellable){hookRelease.await()}}}}
        try {
            hookEntered.await();work.cancelAll();val start=SystemClock.elapsedRealtime()
            val tail=work.launchSignOutTeardown(200){serverEntered.complete(SystemClock.elapsedRealtime());awaitCancellation()}
            val serverTime=withTimeout(5000){serverEntered.await()};withTimeout(5000){tail.join()}
            val elapsed=SystemClock.elapsedRealtime()-start;assertTrue(serverTime-start>=200);assertTrue(elapsed>=400 && elapsed<4000)
            assertEquals(1,work.activeCount);hookRelease.complete(Unit);withTimeout(5000){hook.join()};idle()
            record("separate-tail-budgets",JSONObject().put("eachBudgetMillis",200).put("serverStartMillis",serverTime-start).put("elapsedMillis",elapsed)
                .put("lateHookRemainedTracked",true).put("serverTailCanceledAtItsOwnDeadline",true).put("kernelDescriptorsReleased",true))
        }finally{hookRelease.complete(Unit);hook.cancelAndJoin()}
    }
    @Test fun structuredServerDeadlineJoinsTheCanceledMintAndCannotStartLaterLegs()=runBlocking<Unit> {
        val work=store.authWork;val release=CompletableDeferred<Unit>();val producer=CompletableDeferred<Job>();val hooks=AtomicInteger();val revokes=AtomicInteger()
        val tail=work.launchSignOutTeardown(200){nativeSignOutTeardown(NativeCapturedSession("fixture-access","fixture-refresh"),
            {_,_->producer.complete(currentCoroutineContext().job);withContext(NonCancellable){release.await()};"fixture-minted"},
            {_,_->hooks.incrementAndGet()},{revokes.incrementAndGet()})}
        try {
            val operation=withTimeout(5000){producer.await()};withTimeout(5000){while(!operation.isCancelled)delay(5)}
            assertFalse(tail.isCompleted);assertEquals(1,work.activeCount);idle()
            release.complete(Unit);withTimeout(5000){tail.join()};assertEquals(0,hooks.get());assertEquals(0,revokes.get());assertEquals(0,work.activeCount)
            record("structured-deadline",JSONObject().put("configuredMillis",200).put("canceledOperationJoinedUntilRelease",true)
                .put("hookCalls",0).put("revokeCalls",0).put("kernelDescriptorsReleased",true))
        }finally{release.complete(Unit);tail.cancelAndJoin()}
    }
    @Test fun callerCancellationOfServerTailStillJoinsItsActualOperation()=runBlocking<Unit> {
        val work=store.authWork;val release=CompletableDeferred<Unit>();val producer=CompletableDeferred<Job>();val hooks=AtomicInteger()
        val tail=work.launchSignOutTeardown(60_000){nativeSignOutTeardown(NativeCapturedSession(null,"fixture-refresh"),
            {_,_->producer.complete(currentCoroutineContext().job);withContext(NonCancellable){release.await()};"fixture-minted"},
            {_,_->hooks.incrementAndGet()},{error("Canceled mint cannot revoke")})}
        try {
            val operation=withTimeout(5000){producer.await()};tail.cancel();withTimeout(5000){while(!operation.isCancelled)delay(5)}
            assertFalse(tail.isCompleted);assertEquals(1,work.activeCount);idle();release.complete(Unit);withTimeout(5000){tail.join()}
            assertEquals(0,hooks.get());assertEquals(0,work.activeCount)
            record("structured-caller-cancel",JSONObject().put("canceledOperationJoinedUntilRelease",true).put("hookCalls",0)
                .put("kernelDescriptorsReleased",true))
        }finally{release.complete(Unit);tail.cancelAndJoin()}
    }
}
