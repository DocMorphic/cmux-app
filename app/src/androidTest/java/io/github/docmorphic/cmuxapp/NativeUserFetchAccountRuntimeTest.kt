package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID

/** Real disposable encrypted store and actual account callers; no real account/pairings. */
class NativeUserFetchAccountRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    @Before fun setup(){check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="user-fetch-fixture-"+UUID.randomUUID();alias="cmux_user_fetch_"+UUID.randomUUID()
        store=NativeCredentialStore.withKeyProvider(context,name,alias)
        store.update{it.put("fixture_pairing_marker","preserve")}}
    @After fun cleanup(){runBlocking{withTimeout(5000){while(store.authWork.activeCount>0)delay(5)}}
        store.clear();context.deleteSharedPreferences(name);KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)}
    private fun token()="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject()
        .put("exp",System.currentTimeMillis()/1000+3600).toString().toByteArray())+".fixture"
    private fun account(server:MockWebServer)=NativeAccount(store,NativeOAuthClient("p","k",server.url("/")),NativeOAuthBrowser())
    private fun cold()=NativeCredentialStore.withKeyProvider(context,name,alias).requireReadable()!!
    private fun retained(){assertEquals("preserve",cold().getString("fixture_pairing_marker"))
        assertFalse(context.getSharedPreferences(name,0).getString("state",null)!!.contains("fixture-refresh"))}
    private fun record(name:String,rows:JSONArray){retained();File(context.getExternalFilesDir(null),"user-fetch").apply{mkdirs()}
        .resolve("$name.json").writeText(JSONObject().put("fixtureOnly",true).put("encryptedStoreUsed",true)
            .put("pairingMarkerPreserved",true).put("rows",rows).toString(2))}

    @Test fun actualInteractiveAccountUsesSourceErrorsWithoutPublishingOrClearingItsPendingCredentials()=runBlocking<Unit> {
        val rows=JSONArray()
        val cases=listOf(Triple("malformed","private-body-canary",NativeSignInError.UNAUTHORIZED),
            Triple("anonymous","{\"id\":\"user\",\"is_anonymous\":true}",NativeSignInError.SERVER_ERROR),
            Triple("provider-refusal","{\"message\":\"private-body-canary\"}",NativeSignInError.SERVER_ERROR))
        for((label,body,expected) in cases)MockWebServer().use{server->
            store.update{it.remove("access_token");it.remove("refresh_token");it.remove("task_session");it.remove(NativeSignInCompletion.PENDING)}
            val access=token()
            server.enqueue(MockResponse().setBody("{\"nonce\":\"challenge\"}"))
            server.enqueue(MockResponse().setBody(JSONObject().put("access_token",access).put("refresh_token","fixture-refresh").toString()))
            server.enqueue(MockResponse().setBody(body).apply{if(label=="provider-refusal")setHeader("x-hexclave-known-error","SCHEMA_ERROR")})
            val account=account(server);account.sendCode("fixture@example.test")
            val failure=runCatching{account.signIn("abc123")}.exceptionOrNull() as Exception
            assertEquals(label,expected,nativeSignInError(failure));assertFalse(failure.message.orEmpty().contains("private-body-canary"))
            assertFalse(account.isSignedIn());assertNull(account.sessionRestore.verifiedUser());assertNull(store.taskSession())
            val root=cold();assertTrue(NativeSignInCompletion.isPending(root));assertEquals(access,root.getString("access_token"))
            assertEquals("fixture-refresh",root.getString("refresh_token"));assertFalse(root.has(NativeAccountProfileCache.KEY))
            assertEquals(3,server.requestCount);retained()
            rows.put(JSONObject().put("case",label).put("errorRole",expected.name).put("pendingPreserved",true)
                .put("unverifiedAccountDidNotPublish",true).put("httpRequests",3))
        }
        record("interactive-errors",rows)
    }

    @Test fun orphanRestoreDistinguishesSuccessfulMissingUserFromTransientHttpFailure()=runBlocking<Unit> {
        val rows=JSONArray()
        for(malformed in listOf(true,false))MockWebServer().use{server->
            val access=token();store.update{it.put("task_session","pending").put(NativeSignInCompletion.PENDING,"pending")
                .put("access_token",access).put("refresh_token","fixture-refresh")}
            server.enqueue(if(malformed)MockResponse().setBody("private-body-canary") else MockResponse().setResponseCode(503))
            val account=account(server);val failure=runCatching{account.restorePendingSignIn()}.exceptionOrNull() as Exception
            assertEquals(if(malformed)NativeSignInError.UNAUTHORIZED else NativeSignInError.SERVER_ERROR,nativeSignInError(failure))
            assertFalse(account.isSignedIn());assertNull(account.sessionRestore.verifiedUser());val root=cold()
            if(malformed){assertFalse(root.has(NativeSignInCompletion.PENDING));assertTrue(root.optString("refresh_token").isBlank());assertNull(store.taskSession())}
            else {assertTrue(NativeSignInCompletion.isPending(root));assertEquals("fixture-refresh",root.getString("refresh_token"));assertEquals(access,root.getString("access_token"))}
            assertEquals(1,server.requestCount);retained()
            rows.put(JSONObject().put("case",if(malformed)"successful-no-user" else "transient-http")
                .put("matchingReceiptCleared",malformed).put("httpRequests",1))
        }
        record("orphan-errors",rows)
    }
}
