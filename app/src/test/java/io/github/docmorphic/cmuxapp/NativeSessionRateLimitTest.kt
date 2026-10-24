package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class NativeSessionRateLimitTest {
    private fun client(server:MockWebServer)=NativeOAuthClient("fixture-project","fixture-key",server.url("/"))
    private fun throttle()=MockResponse().setResponseCode(429).setHeader("Content-Type","text/html")
        .setHeader("Retry-After","1.2345").setBody("<html>private-response-canary"+"x".repeat(300_000))
    @Test fun oversizedHtmlCurrentUserThrottleKeepsHeadersWithoutParsingOrExposingTheBody()=runBlocking<Unit> {
        MockWebServer().use {server->server.enqueue(throttle())
            val e=runCatching{client(server).restoredCurrentUser("fixture-token"){error("No refresh on429")}}.exceptionOrNull()
            assertTrue(e is NativeAccountRateLimited);e as NativeAccountRateLimited
            assertEquals("/api/v1/users/me",e.endpoint);assertEquals("html",e.bodyKind);assertEquals(1.234,e.retryAfterSeconds!!,0.0)
            assertFalse(e.toString().contains("private-response"));assertEquals(1,server.requestCount)
        }
    }
    @Test fun actualStatusAndKnownErrorAliasesKeepTheSameThrottleMetadata()=runBlocking<Unit> {
        for(header in listOf("x-stack-actual-status","x-hexclave-actual-status","x-stack-known-error","x-hexclave-known-error"))
            MockWebServer().use {server->
                server.enqueue(throttle().setResponseCode(200).setHeader(header,if(header.endsWith("actual-status"))"429" else "rate_limited"))
                val e=runCatching{client(server).restoredCurrentUser("fixture-token"){null}}.exceptionOrNull()
                assertTrue(header,e is NativeAccountRateLimited);assertEquals(1,server.requestCount)
            }
    }
    @Test fun teamUserAndMembershipThrottlesUseTheSameBoundedClassifier()=runBlocking<Unit> {
        MockWebServer().use {server->NativeAccountTeams({"fixture-token"},{"login"},server.url("/api/v1/"),
            sessionIdentity={NativeAuthIdentity(0,"account-a")}).use {teams->
            val allowUser=java.util.concurrent.atomic.AtomicBoolean(false)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                if(allowUser.get() && request.path=="/api/v1/users/me")return MockResponse().setBody("{\"id\":\"account-a\"}")
                return throttle()
            }}
            val user=runCatching{teams.refresh()}.exceptionOrNull() as NativeAccountRateLimited
            assertEquals("/api/v1/users/me",user.endpoint);assertEquals("html",user.bodyKind);assertFalse(teams.hasLoadedMembership)
            allowUser.set(true);val team=runCatching{teams.refreshAfterSignIn("login",NativeSignInUser("account-a",null,null,null))}
                .exceptionOrNull() as NativeAccountRateLimited
            assertEquals("/api/v1/teams",team.endpoint);assertFalse(teams.hasLoadedMembership);assertEquals(0,teams.activeMembershipReads)
        }}
    }
    @Test fun untypedThrottleSniffsOnlyThePrefixAndInvalidTimingFallsBackToTheRecoveryPolicy()=runBlocking<Unit> {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","NaN").setBody("  {\"private-canary\":\""+"x".repeat(300_000)))
            val e=runCatching{client(server).currentUser("fixture-token")}.exceptionOrNull() as NativeAccountRateLimited
            assertEquals("json",e.bodyKind);assertNull(e.retryAfterSeconds);assertFalse(e.toString().contains("canary"))
        }
    }
    @Test fun tokenRefreshThrottleRemainsTheSeparateSdkCooldownType()=runBlocking<Unit> {
        MockWebServer().use {server->server.enqueue(throttle())
            val e=runCatching{client(server).refreshAccess("fixture-refresh")}.exceptionOrNull()
            assertTrue(e is NativeRefreshRateLimited);assertNull(nativeSessionCheckLimit(e as Exception))
        }
    }
}
