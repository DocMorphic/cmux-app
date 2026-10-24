package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NativeSdkHttpTest {
    @Test fun restoredUserRefreshPreservesNonceAndRefreshSnapshotAndReportsTheRejectedAccess()=runBlocking<Unit> {
        MockWebServer().use{server->
            server.enqueue(MockResponse().setHeader("x-hexclave-actual-status","401")
                .setHeader("x-stack-known-error","invalid_access_token").setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(503).setHeader("x-hexclave-actual-status","200").setBody("{\"id\":\"verified\"}"))
            val client=NativeOAuthClient("p","k",server.url("/").newBuilder().host("127.0.0.1").build())
            var rejected:String?=null
            assertEquals("verified",client.restoredCurrentUser("old","snapshot-refresh"){rejected=it;"new"}?.id)
            assertEquals("old",rejected)
            val first=server.takeRequest();val second=server.takeRequest()
            assertEquals(first.getHeader("x-hexclave-random-nonce"),second.getHeader("x-hexclave-random-nonce"))
            assertEquals("snapshot-refresh",first.getHeader("x-hexclave-refresh-token"))
            assertEquals("snapshot-refresh",second.getHeader("x-hexclave-refresh-token"))
            assertEquals("new",second.getHeader("x-hexclave-access-token"))
            assertTrue(first.headers.names().none{it.startsWith("x-stack-",true)})
        }
    }
    @Test fun restorationDoesNotRefreshBare401WrongStatusOrWrongCaseAndOnlyExactRateEscapes()=runBlocking<Unit> {
        val scenarios=listOf(401 to null,403 to "invalid_access_token",200 to "invalid_access_token",401 to "INVALID_ACCESS_TOKEN",
            200 to "",200 to "rate_limited",200 to "RATE_LIMIT",200 to "RATE_LIMITED")
        for((status,code) in scenarios)MockWebServer().use{server->
            server.enqueue(MockResponse().setResponseCode(status).setBody("{}").apply{code?.let{setHeader("x-hexclave-known-error",it)}})
            val failure=runCatching{NativeOAuthClient("p","k",server.url("/").newBuilder().host("127.0.0.1").build()).restoredCurrentUser("old"){error("Unexpected refresh")}}.exceptionOrNull()
            if(code=="RATE_LIMITED")assertEquals(code,(failure as NativeSdkRequestFailure).code)
            else assertTrue("$status/$code",failure is NativeSessionUserUnavailable)
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun emailPostUsesSdkHeadersAndDoesNotReplayALostResponse()=runBlocking<Unit> {
        MockWebServer().use{server->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val client=NativeOAuthClient("p","k",server.url("/").newBuilder().host("127.0.0.1").build(),sdkRetrySleep={error("POST must not retry")})
            val failure=runCatching{client.emailRequest("/auth/otp/send-sign-in-code",JSONObject().put("email","fixture@example.test"))}.exceptionOrNull()
            assertEquals("network_error",(failure as NativeSdkRequestFailure).code)
            val request=server.takeRequest();assertEquals("POST",request.method)
            assertEquals("fixture@example.test",JSONObject(request.body.readUtf8()).getString("email"))
            assertNull(request.getHeader("x-hexclave-access-token"));assertNull(request.getHeader("x-hexclave-refresh-token"))
            assertTrue(request.headers.names().none{it.startsWith("x-stack-",true)})
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun capturedDeleteRetriesNetworkAndRejectedAccessWithOneStableRequestSnapshot()=runBlocking<Unit> {
        MockWebServer().use{server->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setResponseCode(401).setHeader("x-hexclave-known-error","invalid_access_token"))
            server.enqueue(MockResponse().setBody("{\"access_token\":\"new\"}"))
            server.enqueue(MockResponse().setResponseCode(500).setHeader("x-hexclave-actual-status","204"))
            val delays=mutableListOf<Long>()
            NativeOAuthClient("p","k",server.url("/").newBuilder().host("127.0.0.1").build(),sdkRetrySleep={delays+=it}).revokeCapturedSession(NativeCapturedSession("old","snapshot-refresh"))
            val requests=List(4){server.takeRequest()};val deletes=requests.filter{it.method=="DELETE"}
            assertEquals(3,deletes.size);assertEquals(listOf(1000L),delays)
            assertEquals(1,deletes.map{it.getHeader("x-hexclave-random-nonce")}.toSet().size)
            assertTrue(deletes.all{it.getHeader("x-hexclave-refresh-token")=="snapshot-refresh"})
            assertEquals(listOf("old","old","new"),deletes.map{it.getHeader("x-hexclave-access-token")})
            assertTrue(requests.single{it.method=="POST"}.body.readUtf8().contains("refresh_token=snapshot-refresh"))
        }
    }
    @Test fun teamReadsUseEffectiveStatusRejectKnownSuccessErrorsAndDoNotForceRefreshBare401()=runBlocking<Unit> {
        MockWebServer().use{server->
            var mode="success";val forced=AtomicInteger()
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                if(mode=="bare")return MockResponse().setResponseCode(401)
                if(mode=="known")return MockResponse().setHeader("x-hexclave-known-error","fixture-error").setBody("{}")
                val body=if(request.path!!.startsWith("/api/v1/teams"))"{\"items\":[{\"id\":\"one\",\"display_name\":\"One\"}]}" else "{\"id\":\"user\",\"selected_team\":{\"id\":\"one\"}}"
                return MockResponse().setResponseCode(500).setHeader("x-stack-actual-status","200").setBody(body)
            }}
            NativeAccountTeams({force->if(force)forced.incrementAndGet();"access"},{"login"},server.url("/api/v1/"),refreshCredential={"snapshot-refresh"}).use{teams->
                assertEquals("one",teams.refresh().scope?.teamId)
                mode="known";assertTrue(runCatching{teams.refresh()}.exceptionOrNull() is NativeSessionUserUnavailable)
                mode="bare";assertTrue(runCatching{teams.refresh()}.isFailure);assertEquals(0,forced.get())
            }
            repeat(server.requestCount){val request=server.takeRequest()
                assertEquals("snapshot-refresh",request.getHeader("x-hexclave-refresh-token"))
                assertTrue(request.headers.names().none{it.startsWith("x-stack-",true)})
            }
        }
    }
    @Test fun interrupted429TransfersFollowNetworkRetryRulesInsteadOfBecomingCompletedThrottles()=runBlocking<Unit> {
        for(method in listOf("GET","POST"))MockWebServer().use{server->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Content-Type","text/html")
                .setBody("<html>"+"fixture".repeat(80)).setHeader("Content-Length","2048")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.enqueue(MockResponse().setBody("{\"id\":\"verified\"}"))
            val delays=mutableListOf<Long>()
            val client=NativeOAuthClient("p","k",server.url("/").newBuilder().host("127.0.0.1").build(),sdkRetrySleep={delays+=it})
            if(method=="GET") {
                assertEquals("verified",client.currentUser("access").id);assertEquals(listOf(1000L),delays)
                assertEquals(2,server.requestCount)
                assertEquals(server.takeRequest().getHeader("x-hexclave-random-nonce"),server.takeRequest().getHeader("x-hexclave-random-nonce"))
            } else {
                val failure=runCatching{client.emailRequest("/auth/otp/send-sign-in-code",JSONObject())}.exceptionOrNull()
                assertEquals("network_error",(failure as NativeSdkRequestFailure).code)
                assertTrue(delays.isEmpty());assertEquals(1,server.requestCount)
            }
        }
    }

}
