package io.github.docmorphic.cmuxapp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.security.KeyStoreException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey

/** Real encrypted fixture documents. SDK results are injected, never real Firebase token requests. */
class PhoneFcmCallbackRuntimeTest {
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var context:IsolatedContext
    private lateinit var alias:String
    private lateinit var writer:NativeCredentialStore
    private lateinit var reader:NativeCredentialStore
    private val mode=AtomicInteger()
    private val reads=AtomicInteger()
    private val project=PhoneFcmProject("fixture-project","fixture-app","fixture-sender")
    private val login="fixture-callback-login"
    private val token="fixture-token-first"
    private val prefs get()=context.getSharedPreferences("phone_fcm_tokens",Context.MODE_PRIVATE)
    private fun keys()=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key()=keys().getKey(alias,null) as SecretKey
    private fun seed():PhoneFcmTokenSnapshot {
        writer.update { root ->
            val state=PhoneFcmTokenState(root);val owner=state.authorize(login,project)
            state.beginFetch(owner);state.accept(owner,token);root.put("fixture_marker","retain")
        }
        return PhoneFcmTokenState(writer.requireReadable()!!).snapshot!!
    }
    private fun state()=PhoneFcmTokenState(reader.requireReadable()!!)
    private fun record(case:String,body:JSONObject) {
        File(target.getExternalFilesDir(null),"fcm-callback").apply { mkdirs() }.resolve("$case.json")
            .writeText(body.put("fixtureOnly",true).toString(2))
    }
    private suspend fun resolve(value:String):Int {
        var calls=0
        val provider=object:PhoneFcmTokenProvider {
            override val project=this@PhoneFcmCallbackRuntimeTest.project
            override suspend fun token():String { calls++;return value }
            override suspend fun delete()=error("Callback cannot authorize a provider deletion")
        }
        assertFalse(PhoneFcmTokenReconciler({action->reader.update { action(PhoneFcmTokenState(it)) }},{login},{true},provider).runPass())
        return calls
    }
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        context=IsolatedContext(target);alias="cmux_fcm_callback_fixture_"+UUID.randomUUID()
        writer=NativeCredentialStore.withKeyProvider(context,"phone_fcm_tokens",alias,null)
        reader=NativeCredentialStore.withKeyProvider(context,"phone_fcm_tokens",alias) {
            if(mode.get()==1 || mode.get()==2 && reads.incrementAndGet()>1)throw KeyStoreException("Fixture provider unavailable")
            key()
        }
    }
    @After fun cleanup() {
        mode.set(0);prefs.edit().remove(PhoneFcmTokenCallback.LATEST).remove(PhoneFcmTokenCallback.APPLIED).commit()
        writer.clear();context.close();keys().deleteEntry(alias)
    }
    @Test fun unavailableCiphertextKeepsTheCallbackAndReconstructionConfirmsTheSdkWithoutChangingConsent()=runBlocking<Unit> {
        val old=seed();val before=prefs.getString("state",null);mode.set(1)
        PhoneFcmTokenCallback.record(context);val event=PhoneFcmTokenCallback.pending(context)!!
        assertNull(reader.load());assertEquals(before,prefs.getString("state",null))
        val reconstructed=NativeCredentialStore.withKeyProvider(context,"phone_fcm_tokens",alias) {
            if(mode.get()==1)throw KeyStoreException("Fixture provider unavailable");key()
        }
        assertNull(reconstructed.load());assertEquals(event,PhoneFcmTokenCallback.pending(context))
        mode.set(0);val restored=PhoneFcmTokenState(reconstructed.requireReadable()!!)
        assertEquals(old,restored.snapshot);assertTrue(restored.callbackCheckPending)
        assertNull(PhoneFcmTokenCallback.pending(context));assertEquals(prefs.getString(PhoneFcmTokenCallback.LATEST,null),prefs.getString(PhoneFcmTokenCallback.APPLIED,null))
        assertEquals(1,resolve(token));assertEquals(old,state().snapshot);assertFalse(state().callbackCheckPending)
        assertEquals("retain",reader.requireReadable()!!.getString("fixture_marker"))
        for(value in listOf(login,token,project.project)) {
            assertFalse(prefs.getString(PhoneFcmTokenCallback.LATEST,"")!!.contains(value))
            assertFalse(prefs.getString("state","")!!.contains(value))
        }
        record("unavailable-reconstruction",JSONObject().put("ciphertextPreserved",true).put("eventRecovered",true)
            .put("sameTokenKeptGrantAndRevision",true).put("callbackPayloadNotStored",true))
    }
    @Test fun callbackCanCommitWhileAnotherThreadIsBlockedInsideKeystore() {
        val old=seed();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val blocked=NativeCredentialStore.withKeyProvider(context,"phone_fcm_tokens",alias) {
            entered.countDown();check(release.await(10,TimeUnit.SECONDS));key()
        }
        val pool=Executors.newFixedThreadPool(2)
        val reading=pool.submit<JSONObject?> { blocked.requireReadable() }
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            pool.submit { PhoneFcmTokenCallback.record(context) }.get(2,TimeUnit.SECONDS)
            assertNotNull(PhoneFcmTokenCallback.pending(context));assertFalse(reading.isDone)
        } finally { release.countDown() }
        try {
            val restored=PhoneFcmTokenState(reading.get(3,TimeUnit.SECONDS)!!)
            assertEquals(old,restored.snapshot);assertTrue(restored.callbackCheckPending)
        } finally { pool.shutdownNow() }
        record("blocked-keystore",JSONObject().put("eventCommittedBeforeCryptoReleased",true).put("eventReplayed",true))
    }
    @Test fun callbackDuringEncryptionRejectsTheOlderSdkResultAtFinalCommit() {
        val old=seed();val before=prefs.getString("state",null);val ticket=state().readTicket!!
        val encrypting=CountDownLatch(1);val release=CountDownLatch(1);val count=AtomicInteger()
        val blocked=NativeCredentialStore.withKeyProvider(context,"phone_fcm_tokens",alias) {
            if(count.incrementAndGet()==2) { encrypting.countDown();check(release.await(10,TimeUnit.SECONDS)) };key()
        }
        val pool=Executors.newSingleThreadExecutor()
        val saving=pool.submit<Boolean> {
            try { blocked.update { assertTrue(PhoneFcmTokenState(it).accept(ticket,"obsolete-sdk-token")) };false }
            catch(_:NativeCredentialReadException) { true }
        }
        try {
            assertTrue(encrypting.await(3,TimeUnit.SECONDS));PhoneFcmTokenCallback.record(context)
            release.countDown();assertTrue(saving.get(3,TimeUnit.SECONDS))
            assertEquals(before,prefs.getString("state",null));assertNotNull(PhoneFcmTokenCallback.pending(context))
            assertEquals(old,state().snapshot);assertTrue(state().callbackCheckPending)
        } finally { release.countDown();pool.shutdownNow() }
        record("encryption-race",JSONObject().put("staleSdkCommitRejected",true).put("originalCiphertextRetained",true).put("newEventNotLost",true))
    }
    @Test fun failedReplayEncryptionCannotAcknowledgeTheCallbackOrReplaceItsCiphertext() {
        seed();val before=prefs.getString("state",null);PhoneFcmTokenCallback.record(context)
        val event=PhoneFcmTokenCallback.pending(context);reads.set(0);mode.set(2)
        assertNull(reader.load());assertEquals(before,prefs.getString("state",null));assertEquals(event,PhoneFcmTokenCallback.pending(context))
        mode.set(0);assertTrue(state().callbackCheckPending);assertNull(PhoneFcmTokenCallback.pending(context))
        record("replay-encryption-failure",JSONObject().put("eventAndCiphertextRetained",true).put("recovered",true))
    }
    @Test fun noConsentCallbackAcknowledgesAnEmptyStoreWithoutCreatingAKeyOrGrant() {
        PhoneFcmTokenCallback.record(context);assertNotNull(PhoneFcmTokenCallback.pending(context))
        assertNull(reader.requireReadable());assertNull(PhoneFcmTokenCallback.pending(context))
        assertFalse(prefs.contains("state"));assertFalse(keys().containsAlias(alias))
        record("no-consent-empty",JSONObject().put("noCipherOrKeyCreated",true).put("noGrantCreated",true))
    }
    @Test fun malformedCallbackMetadataPreservesDataUntilAValidSdkSignalReplacesIt() {
        val old=seed();val before=prefs.getString("state",null)
        for(value in listOf<Any>("fixture-private-invalid-metadata",42)) {
            val edit=prefs.edit();if(value is String)edit.putString(PhoneFcmTokenCallback.LATEST,value) else edit.putInt(PhoneFcmTokenCallback.LATEST,value as Int);edit.commit()
            assertNull(reader.load());var transforms=0
            try { reader.update { transforms++ };fail("Unknown metadata cannot become an empty lifecycle") }
            catch(failure:NativeCredentialReadException) { assertNull(failure.cause);assertFalse(failure.message!!.contains("fixture-private")) }
            assertEquals(0,transforms);assertEquals(before,prefs.getString("state",null))
        }
        PhoneFcmTokenCallback.record(context);assertEquals(old,state().snapshot)
        record("malformed-metadata",JSONObject().put("unknownMetadataDidNotMutateCipher",true).put("validSignalRecovered",true))
    }
    @Test fun productionIngressQueuesWorkAndNoConsentWorkerDoesNotCreateALifecycle()=runBlocking<Unit> {
        val global=target.getSharedPreferences("phone_fcm_tokens",Context.MODE_PRIVATE)
        val before=HashMap(global.all)
        val initial=NativeCredentialStore(target,"phone_fcm_tokens").requireReadable()
        assertFalse(initial?.has(PhoneFcmTokenState.KEY)==true)
        try {
            withTimeout(10_000) { PhoneFcmTokens.onTokenChanged(target)?.join() }
            assertNull(PhoneFcmTokenCallback.pending(target))
            assertFalse(PhoneFcmTokens.run(target))
            assertFalse(NativeCredentialStore(target,"phone_fcm_tokens").requireReadable()?.has(PhoneFcmTokenState.KEY)==true)
            if(!before.containsKey("state"))assertFalse(global.contains("state"))
            val work=withContext(Dispatchers.IO) { WorkManager.getInstance(target).getWorkInfosForUniqueWork("cmux.push.tokens").get(5,TimeUnit.SECONDS) }
            assertTrue(work.isNotEmpty())
            record("production-ingress",JSONObject().put("durableWorkObserved",true).put("lifecycleNotCreated",true).put("directNoConsentPassSettled",true))
        } finally {
            withContext(Dispatchers.IO) { WorkManager.getInstance(target).cancelUniqueWork("cmux.push.tokens").result.get(5,TimeUnit.SECONDS) }
            val edit=global.edit().clear()
            before.forEach { (key,value)->when(value) {
                is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value)
                is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value)
                is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())
            } }
            check(edit.commit())
        }
    }
    private class IsolatedContext(base:Context):ContextWrapper(base),AutoCloseable {
        private val namespace="fcm-callback-"+UUID.randomUUID()
        private val names=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        override fun getApplicationContext():Context=this
        override fun getApplicationInfo()=ApplicationInfo(super.getApplicationInfo()).apply { dataDir+="/"+namespace }
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
            names+=name;return super.getSharedPreferences("$namespace.$name",mode)
        }
        override fun close() { names.forEach { super.deleteSharedPreferences("$namespace.$it") } }
    }
}
