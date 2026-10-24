package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.security.KeyStoreException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey

/** Real Keystore documents; network/sign-in uses only an explicitly local mock server. */
class NativeLocalLogoutRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var alias: String
    private lateinit var writer: NativeCredentialStore
    private lateinit var reader: NativeCredentialStore
    private val mode=AtomicInteger(0)
    private val keyReads=AtomicInteger(0)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val user=NativeSignInUser("old-user","Saved Person","saved@example.test",null)
    private val pairing=NativeCredentialStore.PairedMac("fixture-pairing","fixture-mac","Saved Mac")
    private fun keys()=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val prefs get()=context.getSharedPreferences(name,Context.MODE_PRIVATE)
    private fun client(server:MockWebServer?=null)=if(server==null)NativeOAuthClient(NativeAccount.PROJECT_ID,NativeAccount.PUBLISHABLE_KEY)
        else NativeOAuthClient("fixture-project","fixture-public",server.url("/"))
    private fun account(server:MockWebServer?=null,browser:NativeOAuthBrowser=NativeOAuthBrowser())=
        NativeAccount(reader,client(server),browser,refreshOverride={error("Production refresh must not run")})
    private fun seed(server:MockWebServer?=null) {
        writer.update { root ->
            root.put("access_token","old-access").put("refresh_token","old-refresh").put("task_session","old-login")
                .put("pairings",JSONArray().put(NativePairingRecords.encode(pairing))).put("fixture_marker","retain")
            NativeAccountProfileCache.writeVerifiedUser(root,"old-login",client(server).accountEnvironment,user)
        }
        writer.setAuthTokenHint(client(server).accountEnvironment,true)
    }
    private fun record(case:String,values:JSONObject) {
        File(context.getExternalFilesDir(null),"local-logout").apply { mkdirs() }.resolve("$case.json")
            .writeText(values.put("fixtureOnly",true).toString(2))
    }
    private fun assertRetired() {
        val root=reader.requireReadable()!!
        assertEquals("",root.getString("access_token"));assertEquals("",root.getString("refresh_token"))
        assertFalse(root.has("task_session"));assertFalse(root.has(NativeAuthUserCache.KEY));assertFalse(root.has(NativeAccountProfileCache.KEY))
        assertFalse(reader.localLogoutPending);assertEquals(listOf(pairing),reader.pairedMacs())
        assertEquals("retain",root.getString("fixture_marker"))
    }
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="local-logout-fixture-"+UUID.randomUUID();alias="cmux_local_logout_fixture_"+UUID.randomUUID()
        writer=NativeCredentialStore.withKeyProvider(context,name,alias,null)
        reader=NativeCredentialStore.withKeyProvider(context,name,alias) {
            val reads=keyReads.incrementAndGet()
            if(mode.get()==1 || mode.get()==2 && reads>1) throw KeyStoreException("Fixture provider unavailable")
            keys().getKey(alias,null) as SecretKey
        }
    }
    @After fun cleanup() {
        scope.cancel();mode.set(0);prefs.edit().remove(NativeLocalLogout.PENDING).commit()
        writer.clear();context.deleteSharedPreferences(name);keys().deleteEntry(alias)
    }
    @Test fun explicitLogoutIsDurableAcrossUnreadableStoreReconstructionAndLaterReplay()=runBlocking<Unit> {
        seed();val owner=account();assertTrue(owner.sessionRestore.state.value.authenticated)
        val before=prefs.getString("state",null);val created=keys().getCreationDate(alias)
        mode.set(1);assertTrue(owner.signOut())
        assertEquals(NativeAuthState(),owner.sessionRestore.state.value);assertTrue(reader.localLogoutPending)
        assertEquals(before,prefs.getString("state",null));assertFalse(reader.authTokenHint(client().accountEnvironment,true))
        val reconstructed=account();assertEquals(NativeAuthState(),reconstructed.sessionRestore.state.value)
        reconstructed.sessionRestore.start(scope);withTimeout(5000) { reconstructed.sessionRestore.awaitBootstrapped() }
        assertFalse(reconstructed.sessionRestore.state.value.authenticated)
        assertFalse(reader.authTokenHint(client().accountEnvironment,true));assertEquals(before,prefs.getString("state",null))
        mode.set(0);reconstructed.retryLocalLogout();assertRetired()
        assertEquals(created,keys().getCreationDate(alias));assertEquals(NativeAuthState(),account().sessionRestore.state.value)
        record("unavailable-reconstruction",JSONObject().put("ciphertextPreservedWhileUnavailable",true)
            .put("localVerdictCleared",true).put("oldAccountCannotRestore",true).put("pairingAndKeyRetained",true))
    }
    @Test fun aFreshProductionCompletionReplaysOldRetirementBeforeWritingAndCannotBeErasedByIt()=runBlocking<Unit> {
        MockWebServer().use { server ->
            seed(server);val browser=NativeOAuthBrowser();val owner=account(server,browser)
            mode.set(1);assertTrue(owner.signOut());assertTrue(reader.localLogoutPending);mode.set(0)
            server.enqueue(MockResponse().setBody("{\"location\":\"https://provider.example/authorize\"}"))
            server.enqueue(MockResponse().setBody("{\"access_token\":\"fresh-access\",\"refresh_token\":\"fresh-refresh\"}"))
            server.enqueue(MockResponse().setBody("{\"id\":\"fresh-user\"}"))
            val operation=async(Dispatchers.Default) { owner.signInWithProvider(NativeOAuthProvider.GOOGLE) }
            val launch=withTimeout(5000) { browser.state.filterNotNull().first() }
            assertTrue(browser.claim(launch.id))
            val request=server.takeRequest(5,java.util.concurrent.TimeUnit.SECONDS)!!
            val url=request.requestUrl!!
            assertTrue(browser.receive(launch.id,url.queryParameter("redirect_uri")+"&state="+url.queryParameter("state")+"&code=fixture-code"))
            withTimeout(5000) { operation.await() }
            assertFalse(reader.localLogoutPending);assertEquals("fresh-refresh",reader.requireReadable()!!.getString("refresh_token"))
            owner.retryLocalLogout();assertEquals("fresh-user",owner.sessionRestore.state.value.user?.id)
            assertTrue(account(server).sessionRestore.state.value.authenticated)
            assertEquals("fresh-refresh",writer.requireReadable()!!.getString("refresh_token"));assertEquals(listOf(pairing),reader.pairedMacs())
            assertEquals(3,server.requestCount)
            record("fresh-signin",JSONObject().put("productionCompletion",true).put("oldIntentCannotEraseNewLogin",true).put("requests",3))
        }
    }
    @Test fun replayEncryptionFailureKeepsTheIntentAndOriginalCiphertextUntilACompleteWriteSucceeds() {
        seed();val owner=account();val before=prefs.getString("state",null)
        keyReads.set(0);mode.set(2);assertTrue(owner.signOut())
        assertEquals(NativeAuthState(),owner.sessionRestore.state.value)
        assertTrue(reader.localLogoutPending);assertEquals(before,prefs.getString("state",null));assertNull(reader.load())
        mode.set(0);owner.retryLocalLogout();assertRetired()
        record("replay-write-failure",JSONObject().put("intentRetained",true).put("originalCiphertextRetained",true).put("recovered",true))
    }
    @Test fun anEmptyStoreAcknowledgesIntentBeforeItsFirstCredentialWriterWithoutGeneratingAReadKey() {
        reader.requestLocalLogout();assertTrue(reader.localLogoutPending)
        assertNull(reader.requireReadable());assertFalse(reader.localLogoutPending);assertFalse(keys().containsAlias(alias))
        writer.update { it.put("access_token","first-access").put("refresh_token","first-refresh").put("task_session","first-login") }
        assertEquals("first-refresh",reader.requireReadable()!!.getString("refresh_token"));assertFalse(reader.localLogoutPending)
        record("empty-store",JSONObject().put("intentAcknowledgedBeforeFirstWrite",true).put("readDidNotCreateKey",true))
    }
    @Test fun aReentrantLogoutCannotBeOverwrittenByTheOuterCredentialTransaction() {
        seed();val owner=account()
        try {
            writer.update { stale -> assertTrue(owner.signOut());stale.put("access_token","must-not-return") }
            fail("The account epoch must retire the outer snapshot")
        } catch (_:CancellationException) { }
        assertRetired()
        record("retired-transaction",JSONObject().put("oldSnapshotRejected",true).put("logoutWon",true))
    }
    @Test fun malformedIntentMetadataCannotMutateTheEncryptedAccountWithoutAnExplicitLogout() {
        seed();val before=prefs.getString("state",null)
        prefs.edit().putString(NativeLocalLogout.PENDING,"malformed-fixture").commit()
        assertNull(reader.load());var transforms=0
        try { reader.update { transforms++ };fail("Unknown metadata is not a logout request") }
        catch(failure:NativeCredentialReadException) { assertNull(failure.cause) }
        assertEquals(0,transforms);assertEquals(before,prefs.getString("state",null))
        assertTrue(account().signOut());assertRetired()
        record("malformed-metadata",JSONObject().put("ciphertextPreserved",true).put("transformCalls",0).put("explicitLogoutRecovered",true))
    }
}
