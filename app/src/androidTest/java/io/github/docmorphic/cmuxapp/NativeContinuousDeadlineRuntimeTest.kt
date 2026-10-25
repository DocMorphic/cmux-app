package io.github.docmorphic.cmuxapp

import android.os.SystemClock
import android.system.Os
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Actual process-owned libc timerfd, disposable encrypted account document; no real account or network. */
class NativeContinuousDeadlineRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private fun timers()=File("/proc/self/fd").listFiles().orEmpty().filter {file->
        runCatching{Os.readlink(file.path)=="anon_inode:[timerfd]"}.getOrDefault(false)}
    private suspend fun idle(baseline:Int=0)=withTimeout(5000) {
        while(NativeContinuousDeadline.pendingCount!=0 || timers().size!=baseline)delay(5)
    }
    private fun record(name:String,value:JSONObject) {
        assertEquals("retain",store.requireReadable()!!.getString("pairing_marker"))
        File(context.getExternalFilesDir(null),"continuous-deadline").apply{mkdirs()}.resolve("$name.json").writeText(
            value.put("fixtureOnly",true).put("encryptedStoreUsed",true).put("pairingPreserved",true)
                .put("noRealSuspendClaim",true).toString(2))
    }
    @Before fun setup()=runBlocking<Unit> {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="continuous-deadline-fixture-"+UUID.randomUUID();alias="cmux_continuous_deadline_fixture_"+UUID.randomUUID()
        store=NativeCredentialStore.withKeyProvider(context,name,alias);store.update{it.put("pairing_marker","retain")}
        NativeContinuousDeadline.await(SystemClock.elapsedRealtime()+10);idle()
    }
    @After fun cleanup()=runBlocking<Unit> {
        store.authWork.cancelAll(includingSignOut=true);withTimeout(5000){while(store.authWork.activeCount>0)delay(5)};idle()
        store.clear();context.deleteSharedPreferences(name);KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)
    }
    @Test fun actualAbsoluteBootTimeTimerExpiresAndClosesItsDescriptor()=runBlocking<Unit> {
        val baseline=timers().size;val start=SystemClock.elapsedRealtime();NativeContinuousDeadline.await(start+200)
        val elapsed=SystemClock.elapsedRealtime()-start;assertTrue("Elapsed $elapsed",elapsed>=200 && elapsed<3000);idle(baseline)
        record("absolute-kernel",JSONObject().put("configuredMillis",200).put("elapsedMillis",elapsed).put("kernelDescriptorsReleased",true))
    }
    @Test fun concurrentWaitsShareOneClockBoottimeDescriptorAndCancellationRetiresIt()=runBlocking<Unit> {
        val baseline=timers().size;val deadline=SystemClock.elapsedRealtime()+60_000
        val waits=List(20){launch(Dispatchers.IO){NativeContinuousDeadline.await(deadline+it)}}
        try {
            withTimeout(5000){while(NativeContinuousDeadline.pendingCount!=20)delay(5)}
            val files=timers();assertEquals(baseline+1,files.size)
            val info=File("/proc/self/fdinfo",files.single().name).readText()
            assertTrue(info,Regex("(?m)^clockid:\\s+7$").containsMatchIn(info))
            waits.forEach{it.cancel()};waits.joinAll();idle(baseline)
            record("shared-kernel",JSONObject().put("concurrentWaits",20).put("additionalTimerDescriptors",1).put("kernelClockId",7)
                .put("allRegistrationsRetired",true).put("kernelDescriptorsReleased",true))
        }finally{waits.forEach{it.cancel()};waits.joinAll()}
    }
    @Test fun productionStoreWorkTimesOutBeforeItsUncooperativeOperationRetires()=runBlocking<Unit> {
        val work=store.authWork;val release=CompletableDeferred<Unit>();val start=SystemClock.elapsedRealtime()
        val waiting=async(Dispatchers.IO){runCatching{work.run(NativeAuthPhase.USER,200){withContext(NonCancellable){release.await()}}}}
        try {
            val failure=withTimeout(5000){waiting.await()}.exceptionOrNull() as NativeSignInFailure
            val elapsed=SystemClock.elapsedRealtime()-start;assertEquals(NativeSignInError.TIMED_OUT,failure.reason)
            assertTrue(elapsed>=200 && elapsed<3000);assertEquals(1,work.activeCount);idle()
            assertTrue(runCatching{work.run(NativeAuthPhase.USER,100){error("Timed-out token work admitted")}}.isFailure)
            release.complete(Unit);withTimeout(5000){while(work.activeCount>0)delay(5)}
            record("store-timeout",JSONObject().put("configuredMillis",200).put("elapsedMillis",elapsed).put("actualJobRetainedUntilRelease",true)
                .put("automaticRetryBlocked",true).put("kernelDescriptorsReleased",true))
        }finally{release.complete(Unit);waiting.cancelAndJoin()}
    }
    @Test fun currentTokensKeepsOneProductionContinuousBudgetAcrossBootstrapAndCapture()=runBlocking<Unit> {
        val work=store.authWork;val release=CompletableDeferred<Unit>();var bootstrap=0
        val before=SystemClock.elapsedRealtime();assertTrue(kotlin.math.abs(work.clockMillis()-before)<50)
        val start=SystemClock.elapsedRealtime()
        val waiting=async(Dispatchers.IO){runCatching{nativeCurrentTokens(work,{bootstrap++;delay(80)},{false},{1},
            capture={withContext(NonCancellable){release.await()};NativeCoherentPair("fixture-a","fixture-r")},timeoutMillis=250)}}
        try {
            val error=withTimeout(5000){waiting.await()}.exceptionOrNull() as NativeSignInFailure
            val elapsed=SystemClock.elapsedRealtime()-start;assertEquals(NativeSignInError.TIMED_OUT,error.reason)
            assertEquals(1,bootstrap);assertTrue(elapsed>=250 && elapsed<3000);assertEquals(1,work.activeCount)
            release.complete(Unit);withTimeout(5000){while(work.activeCount>0)delay(5)};idle()
            record("bootstrap-budget",JSONObject().put("configuredMillis",250).put("bootstrapMillis",80).put("elapsedMillis",elapsed)
                .put("storeClockMatchesElapsedRealtime",true).put("oneAbsoluteBudget",true).put("kernelDescriptorsReleased",true))
        }finally{release.complete(Unit);waiting.cancelAndJoin()}
    }
    @Test fun callerCancellationReleasesKernelWaitWhileTheActualOperationRemainsTracked()=runBlocking<Unit> {
        val work=store.authWork;val release=CompletableDeferred<Unit>()
        val waiting=launch(Dispatchers.IO){work.run(NativeAuthPhase.REFRESH,60_000){withContext(NonCancellable){release.await()}}}
        try {
            withTimeout(5000){while(work.activeCount!=1 || NativeContinuousDeadline.pendingCount!=1)delay(5)}
            waiting.cancel();withTimeout(1000){waiting.join()};assertEquals(1,work.activeCount);idle()
            release.complete(Unit);withTimeout(5000){while(work.activeCount>0)delay(5)}
            record("caller-cancel",JSONObject().put("callerStoppedBeforeOperation",true).put("actualJobRetainedUntilRelease",true)
                .put("kernelDescriptorsReleased",true))
        }finally{release.complete(Unit);waiting.cancelAndJoin()}
    }
    @Test fun processOwnedTimerContinuesAfterTheActivityStops()=runBlocking<Unit> {
        ActivityScenario.launch(ComponentActivity::class.java).use {scenario->
            scenario.moveToState(Lifecycle.State.CREATED)
            val start=SystemClock.elapsedRealtime();NativeContinuousDeadline.await(start+200)
            val elapsed=SystemClock.elapsedRealtime()-start;assertTrue(elapsed>=200 && elapsed<3000);idle()
            assertEquals(Lifecycle.State.CREATED,scenario.state)
            record("stopped-activity",JSONObject().put("configuredMillis",200).put("elapsedMillis",elapsed)
                .put("activityStopped",true).put("processTimerStillDelivered",true).put("kernelDescriptorsReleased",true))
        }
    }
}
