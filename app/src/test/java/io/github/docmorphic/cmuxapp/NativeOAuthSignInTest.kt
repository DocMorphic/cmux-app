package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import java.net.URLDecoder

@OptIn(ExperimentalCoroutinesApi::class)
class NativeOAuthSignInTest {
    private val state = "A".repeat(32)
    private fun material() = NativeOAuthChallenge(state, "B".repeat(64))
    private fun callback(challenge: NativeOAuthChallenge = material(), code: String = "fixture-code") =
        challenge.redirect + "&state="+challenge.state+"&code="+URLEncoder.encode(code, "UTF-8")
    private fun fields(value: String) = value.split('&').associate { part ->
        val pair = part.split('=', limit = 2); URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") }

    @Test fun fetchedAnonymousAndRestrictedUsersAreRejectedLikeTheOriginalSdk(): Unit = runBlocking {
        MockWebServer().use { server ->
            val client = NativeOAuthClient("fixture-project", "fixture-key", server.url("/"))
            for (flag in listOf("is_anonymous", "is_restricted")) {
                // Actual Foundation JSONSerialization + `as? Bool` reference.
                for ((raw, rejected) in listOf("true" to true, "false" to false, "1" to true, "1.0" to true,
                    "0" to false, "0.0" to false, "2" to false, "-1" to false, "\"true\"" to false, "null" to false)) {
                    server.enqueue(MockResponse().setBody("{\"id\":\"user\",\"$flag\":$raw}"))
                    if (rejected) {
                        val failure = runCatching { client.currentUser("captured-access") }.exceptionOrNull() as Exception
                        assertEquals(NativeSignInError.UNAUTHORIZED, nativeSignInError(failure))
                    } else assertEquals("user", client.currentUser("captured-access").id)
                    server.takeRequest()
                }
            }
        }
    }
    @Test fun orphanRefreshUsesTheSourceFormAndOnlyDefinitiveCredentialRejectionBecomesUnauthorized() = runBlocking {
        MockWebServer().use { server ->
            val client = NativeOAuthClient("fixture-project", "fixture-key", server.url("/"))
            server.enqueue(MockResponse().setBody("{\"access_token\":\"fresh\"}")); assertEquals("fresh", client.refreshAccess("stored-refresh"))
            val request = server.takeRequest(); assertEquals("/api/v1/auth/oauth/token", request.path)
            assertEquals(mapOf("grant_type" to "refresh_token", "refresh_token" to "stored-refresh", "client_id" to "fixture-project", "client_secret" to "fixture-key"), fields(request.body.readUtf8()))
            for ((response, role) in listOf(MockResponse().setResponseCode(400).setBody("{}") to NativeSignInError.UNAUTHORIZED,
                MockResponse().setResponseCode(503).setBody("{}") to NativeSignInError.SERVER_ERROR,
                MockResponse().setBody("{}") to NativeSignInError.SERVER_ERROR)) {
                server.enqueue(response); val failure = runCatching { client.refreshAccess("stored-refresh") }.exceptionOrNull() as Exception
                assertEquals(role, nativeSignInError(failure)); server.takeRequest()
            }
        }
    }
    @Test fun userVerificationUsesTheCapturedAccessTokenAndRejectsHttp200ApplicationErrorsOrMissingUsers() = runBlocking {
        MockWebServer().use { server ->
            val client = NativeOAuthClient("fixture-project", "fixture-key", server.url("/"))
            server.enqueue(MockResponse().setBody("{\"id\":\"user\",\"primary_email\":\"fixture@example.test\"}"))
            assertEquals("user", client.currentUser("captured-access").id)
            val request = server.takeRequest(); assertEquals("/api/v1/users/me", request.path)
            assertEquals("captured-access", request.getHeader("x-stack-access-token")); assertEquals("fixture-project", request.getHeader("x-stack-project-id"))
            assertNull(request.getHeader("Authorization")); assertEquals("GET", request.method)
            server.enqueue(MockResponse().setHeader("x-stack-actual-status", "401").setBody("{}"))
            val rejected = runCatching { client.currentUser("captured-access") }.exceptionOrNull() as Exception
            assertEquals(NativeSignInError.UNAUTHORIZED, nativeSignInError(rejected)); server.takeRequest()
            server.enqueue(MockResponse().setBody("{}"))
            val missing = runCatching { client.currentUser("captured-access") }.exceptionOrNull() as Exception
            assertEquals(NativeSignInError.UNAUTHORIZED, nativeSignInError(missing)); server.takeRequest()
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun actualHttpUsesOriginalAuthorizeAndPkceTokenContractsForAllProviders() = runBlocking {
        MockWebServer().use { server ->
            val client = NativeOAuthClient("fixture project", "public+key", server.url("/"))
            for (provider in NativeOAuthProvider.entries) {
                server.enqueue(MockResponse().setBody("{\"location\":\"https://provider.example/authorize\"}"))
                server.enqueue(MockResponse().setBody("{\"access_token\":\"fixture-access\",\"refresh_token\":\"fixture-refresh\"}"))
                val challenge = material()
                assertEquals("https://provider.example/authorize", client.prepare(provider, challenge))
                assertEquals("fixture-access" to "fixture-refresh", client.exchange(callback(challenge, "code+ &é"), challenge))
                val authorize = server.takeRequest(); val url = authorize.requestUrl!!
                assertEquals("GET", authorize.method); assertEquals("/api/v1/auth/oauth/authorize/${provider.wire}", url.encodedPath)
                assertEquals("fixture project", url.queryParameter("client_id")); assertEquals("public+key", url.queryParameter("client_secret"))
                assertEquals(challenge.redirect, url.queryParameter("redirect_uri")); assertEquals(challenge.errorRedirect, url.queryParameter("error_redirect_uri"))
                assertEquals(state, url.queryParameter("state")); assertEquals("S256", url.queryParameter("code_challenge_method"))
                assertEquals(challenge.codeChallenge, url.queryParameter("code_challenge")); assertEquals("legacy", url.queryParameter("scope"))
                assertEquals("authorization_code", url.queryParameter("grant_type")); assertEquals("code", url.queryParameter("response_type"))
                assertEquals("authenticate", url.queryParameter("type")); assertEquals("json", url.queryParameter("stack_response_mode"))
                assertNull(url.queryParameter("token")); assertFalse(url.toString().contains(challenge.verifier))
                val exchange = server.takeRequest(); assertEquals("POST", exchange.method)
                assertEquals("/api/v1/auth/oauth/token", exchange.requestUrl!!.encodedPath)
                assertEquals("fixture project", exchange.getHeader("x-hexclave-project-id")); assertNull(exchange.getHeader("Authorization"))
                assertEquals(mapOf("grant_type" to "authorization_code", "code" to "code+ &é", "redirect_uri" to challenge.redirect,
                    "code_verifier" to challenge.verifier, "client_id" to "fixture project", "client_secret" to "public+key"), fields(exchange.body.readUtf8()))
            }
        }
    }

    @Test fun randomMaterialAndRfcS256VectorHaveNoVerifierInBrowserOrDescription() {
        val values = List(128) { NativeOAuthChallenge.create() }
        assertEquals(128, values.map { it.state }.toSet().size); assertEquals(128, values.map { it.verifier }.toSet().size)
        for (m in values) { assertTrue(m.state.matches(Regex("[A-Za-z0-9]{32}"))); assertTrue(m.verifier.matches(Regex("[A-Za-z0-9]{64}")))
            assertEquals(43, m.codeChallenge.length); assertFalse(m.redirect.contains(m.verifier)); assertFalse(m.toString().contains(m.verifier)) }
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", NativeOAuthChallenge(state, "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk").codeChallenge)
    }

    @Test fun callbackRejectsWrongFlowStateOriginPathDuplicatesAndMixedOutcomesBeforeAnyRequest() {
        val m = material(); val good = callback(m)
        val bad = listOf(good.replace(state, "C".repeat(32)), good.replace("state=$state", "state=wrong"),
            good.replace("://success", "://attacker"), good.replace(m.path, "/another.app"), good+"#code=evil", good+"&state=$state",
            good+"&%73tate=$state", good+"&code=evil", good+"&error=access_denied", good.replace("$state&code", "&code"),
            good.replace("?cmux_flow=$state&", "?"), good.replace("&state=$state", ""), good.replace("://success", "://user@success"),
            good.replace("://success", "://success:9"), good.replace("stack-auth-mobile-oauth-url", "https"))
        for (uri in bad) assertThrows(uri, NativeSignInFailure::class.java) { NativeOAuthCallback.code(uri, m) }
        assertEquals("fixture-code", NativeOAuthCallback.code(good, m))
    }

    @Test fun errorCallbackUsesBoundFlowEvenWhenBackendOmitsStateAndNeverExposesDescription() {
        for ((code, role) in listOf("access_denied" to null, "server_error" to NativeSignInError.SERVER_ERROR)) {
            val m = material(); val uri = m.errorRedirect+"&error=$code&error_description=PRIVATE_VALUE"
            val error = assertThrows(NativeAccountRequestFailure::class.java) { NativeOAuthCallback.code(uri, m) }
            assertEquals(role, nativeSignInError(error)); assertFalse(error.message!!.contains("PRIVATE_VALUE"))
        }
    }

    @Test fun providerLocationValidationMatchesOriginalHttpsAndHostRestrictions() {
        for (url in listOf("http://accounts.google.com/oauth", "not-a-url", "https://user@example.com/oauth",
            "https://accounts.google.com%0d%0aevil.com/", "https://accounts.google.com%2fevil.com/",
            "https://accounts.google.com%252fevil.com/", "https://accounts.google.com%2540evil.com/", "https://example.com\\@evil.com/"))
            assertThrows(url, NativeAccountRequestFailure::class.java) { nativeOAuthProviderUrl(url) }
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth", nativeOAuthProviderUrl("https://accounts.google.com/o/oauth2/v2/auth"))
    }

    @Test fun knownErrorsIncludingHttp200AndRedirectsDoNotBecomeTokenPairs() = runBlocking {
        MockWebServer().use { server ->
            val client = NativeOAuthClient("project", "key", server.url("/"))
            for (response in listOf(MockResponse().setResponseCode(200).setHeader("x-stack-known-error", "OAUTH_PROVIDER_ACCOUNT_ID_ALREADY_USED_FOR_SIGN_IN").setBody("{}"),
                MockResponse().setResponseCode(429).setBody("private html"), MockResponse().setResponseCode(302).setHeader("Location", server.url("/unexpected")),
                MockResponse().setResponseCode(201).setBody("{\"access_token\":\"not-200\",\"refresh_token\":\"refresh\"}"))) {
                server.enqueue(response)
                assertThrows(NativeAccountRequestFailure::class.java) { runBlocking { client.exchange(callback(), material()) } }
                server.takeRequest()
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun malformedTokenPairsAndInvalidLocationsFailWithoutPublishing(): Unit = runBlocking {
        MockWebServer().use { server ->
            val client = NativeOAuthClient("project", "key", server.url("/"))
            for (body in listOf("{}", "{\"access_token\":\"only-access\"}", "{\"access_token\":7,\"refresh_token\":\"refresh\"}")) {
                server.enqueue(MockResponse().setBody(body))
                assertThrows(NativeSignInFailure::class.java) { runBlocking { client.exchange(callback(), material()) } }; server.takeRequest()
            }
            server.enqueue(MockResponse().setBody("{\"location\":\"http://insecure.example\"}"))
            assertThrows(NativeAccountRequestFailure::class.java) { runBlocking { client.prepare(NativeOAuthProvider.GOOGLE, material()) } }
        }
    }

    @Test fun clearingWinsAgainstAnUncooperativeExchangeAndTheNextFlowPublishesOnce() = runTest {
        val gate = CompletableDeferred<Unit>(); val published = mutableListOf<String>(); var calls = 0
        val flow = NativeOAuthSignIn({ _, _ -> "https://provider.example/" }, { _, _ -> callback() }, { _, _ ->
            if (++calls == 1) withContext(NonCancellable) { gate.await(); "old" to "old-refresh" } else "new" to "new-refresh"
        }, { a, _ -> published += a })
        val old = launch { flow.signIn(NativeOAuthProvider.GOOGLE) }; runCurrent(); flow.clear()
        val next = launch { flow.signIn(NativeOAuthProvider.GITHUB) }; runCurrent(); gate.complete(Unit); runCurrent()
        old.join(); next.join(); assertEquals(listOf("new"), published)
    }

    @Test fun staleConditionalClearLeavesTheCurrentFlowAlone() = runTest {
        val gate = CompletableDeferred<Unit>(); var published = 0
        val flow = NativeOAuthSignIn({ _, _ -> "https://provider.example/" }, { _, _ -> gate.await(); callback() }, { _, _ -> "new" to "refresh" }, { _, _ -> published++ })
        val job = launch { flow.signIn(NativeOAuthProvider.APPLE) }; runCurrent()
        assertFalse(flow.clearIf { false }); gate.complete(Unit); job.join(); assertEquals(1, published)
    }
}
