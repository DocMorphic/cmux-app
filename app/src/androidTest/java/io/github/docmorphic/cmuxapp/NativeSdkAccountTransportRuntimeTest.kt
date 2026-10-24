package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Uses disposable encrypted credentials only; never reads the user's account or pairings. */
class NativeSdkAccountTransportRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    @Before fun setup(){check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="sdk-transport-fixture-"+UUID.randomUUID();alias="cmux_sdk_transport_"+UUID.randomUUID()
        store=NativeCredentialStore.withKeyProvider(context,name,alias)}
    @After fun cleanup(){runBlocking{withTimeout(5000){while(store.authWork.activeCount>0)delay(5)}}
        store.clear();context.deleteSharedPreferences(name);KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)}
    private fun access(label:String)="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject()
        .put("exp",System.currentTimeMillis()/1000+3600).put("label",label).toString().toByteArray())+".fixture"
    private fun seed(token:String){store.update{it.put("task_session","fixture-login").put("access_token",token)
        .put("refresh_token","fixture-refresh").put("fixture_pairing_marker","preserve")}}
    private fun assertStored(token:String){val cold=NativeCredentialStore.withKeyProvider(context,name,alias).requireReadable()!!
        assertEquals(token,cold.getString("access_token"));assertEquals("fixture-refresh",cold.getString("refresh_token"))
        assertEquals("preserve",cold.getString("fixture_pairing_marker"))
        val cipher=context.getSharedPreferences(name,0).getString("state",null)!!
        assertFalse(cipher.contains(token));assertFalse(cipher.contains("fixture-refresh"))}

    @Test fun delayedRejectionsReuseTheAlreadyRefreshedAccessWithoutAnotherExchange()=runBlocking<Unit> {
        MockWebServer().use{server->val old=access("old");val next=access("new");seed(old)
            val exchanges=AtomicInteger();val account=NativeAccount(store,NativeOAuthClient("p","k",server.url("/")),NativeOAuthBrowser(),
                refreshOverride={assertEquals("fixture-refresh",it);exchanges.incrementAndGet();next})
            assertEquals(next,account.resolvedAccessToken(forceRefresh=true,rejectedAccess=old))
            assertEquals(next,account.resolvedAccessToken(forceRefresh=true,rejectedAccess=old))
            assertEquals(1,exchanges.get());assertStored(next)
            // A rejection of the current access still needs a fresh exchange.
            assertEquals(next,account.resolvedAccessToken(forceRefresh=true,rejectedAccess=next))
            assertEquals(2,exchanges.get());assertStored(next)
        }
    }

    @Test fun aReplacedAccountCannotReuseThePreviousRequestTokenOrWriteItsRefreshResult()=runBlocking<Unit> {
        MockWebServer().use{server->val old=access("old");val next=access("new");seed(old)
            val entered=CompletableDeferred<Unit>();val release=java.util.concurrent.CountDownLatch(1)
            val account=NativeAccount(store,NativeOAuthClient("p","k",server.url("/")),NativeOAuthBrowser(),refreshOverride={
                entered.complete(Unit);check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));next})
            val result=async(Dispatchers.IO){runCatching{account.resolvedAccessToken(forceRefresh=true,rejectedAccess=old)}}
            try {withTimeout(3000){entered.await()};store.update{it.put("task_session","replacement").put("refresh_token","replacement-refresh")}
                release.countDown();assertTrue(result.await().exceptionOrNull() is CancellationException)
                val root=store.requireReadable()!!;assertEquals("replacement",root.getString("task_session"))
                assertEquals(old,root.getString("access_token"));assertEquals("replacement-refresh",root.getString("refresh_token"))
                assertEquals("preserve",root.getString("fixture_pairing_marker"))
            } finally {release.countDown();result.cancelAndJoin()}
        }
    }

    @Test fun changedTeamOwnerWhileWaitingForNetworkRetryCannotIssueAnotherRequest()=runBlocking<Unit> {
        MockWebServer().use{server->server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)}
            val owner=AtomicReference("login");val waiting=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            NativeAccountTeams({"fixture-access"},{owner.get()},server.url("/api/v1/"),retrySleep={waiting.complete(Unit);release.await()}).use{teams->
                val pending=async{runCatching{teams.refresh()}}
                withTimeout(4000){waiting.await()};owner.set("replacement");release.complete(Unit)
                assertTrue(withTimeout(4000){pending.await()}.isFailure)
                val count=server.requestCount;delay(100);assertEquals(count,server.requestCount);assertTrue(count in 1..2)
                assertFalse(teams.hasLoadedMembership);assertNull(teams.state.value.scope)
            }
        }
    }
    @Test fun interactiveEmailCompletionRefreshesThePendingReceiptWithoutChangingItsUserRequestNonce()=runBlocking<Unit> {
        MockWebServer().use{server->val old=access("pending-old");val next=access("pending-new")
            store.update{it.put("fixture_pairing_marker","preserve")}
            server.enqueue(MockResponse().setBody("{\"nonce\":\"challenge\"}"))
            server.enqueue(MockResponse().setBody(JSONObject().put("access_token",old).put("refresh_token","fixture-refresh").toString()))
            server.enqueue(MockResponse().setResponseCode(401).setHeader("x-hexclave-known-error","invalid_access_token"))
            server.enqueue(MockResponse().setBody(JSONObject().put("access_token",next).toString()))
            server.enqueue(MockResponse().setBody("{\"id\":\"verified\"}"))
            val account=NativeAccount(store,NativeOAuthClient("p","k",server.url("/").newBuilder().host("127.0.0.1").build()),NativeOAuthBrowser())
            account.sendCode("fixture@example.test");account.signIn("abc123")
            assertTrue(account.isSignedIn());assertFalse(NativeSignInCompletion.isPending(store.requireReadable()))
            assertEquals("verified",account.sessionRestore.verifiedUser()?.id);assertStored(next)
            val requests=List(5){server.takeRequest()};val users=requests.filter{it.method=="GET"}
            assertEquals(2,users.size);assertEquals(users[0].getHeader("x-hexclave-random-nonce"),users[1].getHeader("x-hexclave-random-nonce"))
            assertEquals(listOf(old,next),users.map{it.getHeader("x-hexclave-access-token")})
            assertTrue(users.all{it.getHeader("x-hexclave-refresh-token")=="fixture-refresh"})
        }
    }
    @Test fun aReplacedPendingReceiptCannotReceiveTheLateAccessRefreshOrPublishAUser()=runBlocking<Unit> {
        MockWebServer().use{server->val old=access("pending-old");val next=access("late")
            store.update{it.put("fixture_pairing_marker","preserve")}
            val entered=CompletableDeferred<Unit>();val release=java.util.concurrent.CountDownLatch(1)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.path) {
                "/api/v1/auth/otp/send-sign-in-code"->MockResponse().setBody("{\"nonce\":\"challenge\"}")
                "/api/v1/auth/otp/sign-in"->MockResponse().setBody(JSONObject().put("access_token",old).put("refresh_token","fixture-refresh").toString())
                "/api/v1/users/me"->MockResponse().setResponseCode(401).setHeader("x-hexclave-known-error","invalid_access_token")
                "/api/v1/auth/oauth/token"->{entered.complete(Unit);check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
                    MockResponse().setBody(JSONObject().put("access_token",next).toString())}
                else->MockResponse().setResponseCode(599)
            }}
            val account=NativeAccount(store,NativeOAuthClient("p","k",server.url("/").newBuilder().host("127.0.0.1").build()),NativeOAuthBrowser())
            account.sendCode("fixture@example.test");val pending=async(Dispatchers.IO){runCatching{account.signIn("abc123")}}
            try {withTimeout(4000){entered.await()}
                store.update{it.put("task_session","replacement").put(NativeSignInCompletion.PENDING,"replacement")
                    .put("access_token","replacement-access").put("refresh_token","replacement-refresh")}
                release.countDown();assertTrue(withTimeout(4000){pending.await()}.isFailure)
                val root=store.requireReadable()!!;assertEquals("replacement-access",root.getString("access_token"))
                assertEquals("replacement-refresh",root.getString("refresh_token"));assertEquals("replacement",root.getString("task_session"))
                assertEquals("preserve",root.getString("fixture_pairing_marker"));assertTrue(NativeSignInCompletion.isPending(root))
                assertNull(account.sessionRestore.verifiedUser());assertEquals(4,server.requestCount)
            } finally {release.countDown();pending.cancelAndJoin()}
        }
    }

}
