package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.security.KeyStoreException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey

/** Real Keystore-backed account reads and local HTTP. No real account or provider requests. */
class NativeAccessTokenRuntimeTest {
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String
    private lateinit var alias:String
    private lateinit var store:NativeCredentialStore
    private val now=1_800_000_000_000L
    private fun keys()=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun token(seconds:Long,issued:Long?=null)=token(JSONObject().put("exp",now/1000+seconds).apply { issued?.let { put("iat",now/1000+it) } })
    private fun token(payload:JSONObject)="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray())+".fixture"
    private fun owner(server:MockWebServer,credentials:NativeCredentialStore=store,clock:()->Long={now})=
        NativeAccount(credentials,NativeOAuthClient("fixture-project","fixture-key",server.url("/")),NativeOAuthBrowser(),
            tokenRefreshLeadMillis=120_000,tokenNowMillis=clock)
    private fun seed(access:String,refresh:String?="saved-refresh") {
        store.update { it.put("task_session","saved-login").put("access_token",access).put("refresh_token",refresh ?: "")
            .put("fixture_pairing_marker","retain").put("task_drafts",JSONObject().put("sentinel","saved")) }
    }
    private fun record(case:String,values:JSONObject) {
        File(target.getExternalFilesDir(null),"access-token").apply { mkdirs() }.resolve("$case.json")
            .writeText(values.put("fixtureOnly",true).toString(2))
    }
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="access-token-fixture-"+UUID.randomUUID();alias="cmux_access_fixture_"+UUID.randomUUID()
        store=NativeCredentialStore.withKeyProvider(target,name,alias,null)
    }
    @After fun cleanup() { store.clear();target.deleteSharedPreferences(name);keys().deleteEntry(alias) }
    @Test fun tokenOnlyReadsUseActualExpiryAndForcedRefreshNeverReturnsTheRejectedToken()=runBlocking<Unit> {
        MockWebServer().use { server ->
            val account=owner(server)
            for((access,valid) in listOf(token(10) to true,token(-1) to false,"bad" to false,token(JSONObject()) to true)) {
                seed(access,null);val before=store.requireReadable().toString()
                assertEquals(access.takeIf { valid },account.accessToken());assertNull(account.accessToken(true))
                assertEquals(before,store.requireReadable().toString())
            }
            assertEquals(0,server.requestCount)
            record("token-only",JSONObject().put("actualExpiryApplied",true).put("forcedReadDeclinedStoredToken",true).put("httpRequests",0))
        }
    }
    @Test fun proactiveFailureKeepsAValidTokenButForcedFailureAndExpiredFallbackCannotUseIt()=runBlocking<Unit> {
        MockWebServer().use { server ->
            val old=token(90);seed(old);val account=owner(server)
            server.enqueue(MockResponse().setResponseCode(503));assertEquals(old,account.accessToken())
            server.enqueue(MockResponse().setResponseCode(503));assertTrue(runCatching {account.accessToken(true)}.isFailure)
            seed(token(-1));server.enqueue(MockResponse().setResponseCode(503));assertTrue(runCatching {account.accessToken()}.isFailure)
            assertEquals("saved-refresh",store.requireReadable()!!.getString("refresh_token"))
            assertEquals(3,server.requestCount)
            record("fallback",JSONObject().put("proactiveFallbackOnlyWhileValid",true).put("forcedFallbackRefused",true).put("refreshPreserved",true))
        }
    }
    @Test fun shortLifetimeCapsTheLeadAndActualRefreshUsesTheAccountClientAndPersistsOnlyItsOwner()=runBlocking<Unit> {
        MockWebServer().use { server ->
            val short=token(40,-20);seed(short);val account=owner(server)
            assertEquals(short,account.accessToken());assertEquals(0,server.requestCount)
            server.enqueue(MockResponse().setBody("{\"access_token\":\"minted-access\"}"))
            assertEquals("minted-access",account.accessToken(true));val request=server.takeRequest()
            assertEquals("/api/v1/auth/oauth/token",request.path);assertEquals("fixture-project",request.getHeader("x-hexclave-project-id"))
            assertTrue(request.body.readUtf8().contains("refresh_token=saved-refresh"))
            assertEquals("saved-login",store.taskSession());assertEquals("minted-access",store.requireReadable()!!.getString("access_token"))
            assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"))
            record("mint",JSONObject().put("halfLifetimeLeadApplied",true).put("forcedMintPersisted",true).put("pairingPreserved",true))
        }
    }
    @Test fun onlyDefinitiveRefreshRejectionRetiresTheCapturedSessionAndItsDrafts()=runBlocking<Unit> {
        MockWebServer().use { server ->
            val account=owner(server)
            for(status in listOf(400,401)) {
                seed(token(90));server.enqueue(MockResponse().setResponseCode(status))
                assertNull(account.accessToken());assertNull(store.taskSession())
                val state=store.requireReadable()!!;assertEquals("",state.getString("refresh_token"));assertFalse(state.has("task_drafts"))
                assertEquals("retain",state.getString("fixture_pairing_marker"))
            }
            record("rejection",JSONObject().put("definitiveRejectionOnly",true).put("pairingPreserved",true))
        }
    }
    @Test fun unreadableStorageThrowsAnUnknownVerdictAndCannotFallbackOrSendARefresh()=runBlocking<Unit> {
        MockWebServer().use { server ->
            seed(token(90));val ciphertext=target.getSharedPreferences(name,0).getString("state",null)
            var unavailable=false
            val reader=NativeCredentialStore.withKeyProvider(target,name,alias) {
                if(unavailable)throw KeyStoreException("Fixture unavailable");keys().getKey(alias,null) as SecretKey
            }
            val account=owner(server,reader);unavailable=true
            assertTrue(runCatching {account.accessToken()}.exceptionOrNull() is NativeCredentialReadException)
            assertEquals(ciphertext,target.getSharedPreferences(name,0).getString("state",null));assertEquals(0,server.requestCount)
            unavailable=false;seed(token(3600));assertEquals(store.requireReadable()!!.getString("access_token"),account.accessToken())
            record("unknown-storage",JSONObject().put("unknownNotAbsent",true).put("ciphertextPreserved",true).put("readRecovered",true))
        }
    }
    @Test fun cancelledHttpAndLateOwnerReplacementCannotPublishOrRetireTheNewAccount()=runBlocking<Unit> {
        MockWebServer().use { server ->
            seed(token(90));val account=owner(server)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val waiting=launch(Dispatchers.IO) { account.accessToken(true) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3,TimeUnit.SECONDS) })
            waiting.cancel();withTimeout(2000) { waiting.join() }
            withTimeout(2000) { while(store.authWork.activeCount!=0)delay(10) }
            assertEquals("saved-refresh",store.requireReadable()!!.getString("refresh_token"))
            for(rejected in listOf(false,true)) {
                seed(token(90));server.enqueue((if(rejected)MockResponse().setResponseCode(401).setBody("{}") else MockResponse().setBody("{\"access_token\":\"obsolete\"}")).setBodyDelay(200,TimeUnit.MILLISECONDS))
                val old=async(Dispatchers.IO) { account.accessToken(true) }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3,TimeUnit.SECONDS) })
                store.update { it.put("task_session","new-login").put("refresh_token","new-refresh").put("access_token","new-access") }
                assertNull(old.await());assertEquals("new-refresh",store.requireReadable()!!.getString("refresh_token"))
                assertEquals("new-access",store.requireReadable()!!.getString("access_token"))
            }
            record("cancellation-and-replacement",JSONObject().put("cancelledHttpDrained",true).put("lateMintAndRejectionFenced",true))
        }
    }
    @Test fun fastTokenOnlyReadRechecksItsSnapshotAfterConcurrentAccountReplacement()=runBlocking<Unit> {
        MockWebServer().use { server ->
            seed(token(3600),null);var replace=true
            val account=owner(server,clock={ if(replace) { replace=false;store.update { it.put("task_session","new-login").put("access_token","new-access") } };now })
            assertNull(account.accessToken());assertEquals("new-access",store.requireReadable()!!.getString("access_token"))
            assertEquals(0,server.requestCount)
            record("fast-read-replacement",JSONObject().put("staleFastReadDeclined",true))
        }
    }
}
