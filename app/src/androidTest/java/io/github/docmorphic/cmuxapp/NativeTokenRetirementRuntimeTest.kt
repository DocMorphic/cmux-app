package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKey

/** Production account/store transactions on ART; disposable documents and local provider only. */
class NativeTokenRetirementRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var name: String
    private lateinit var alias: String
    private lateinit var store: NativeCredentialStore
    private val releases = mutableListOf<CountDownLatch>()
    private val cores = mutableSetOf<NativeTokenRefresh>()
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun latch() = CountDownLatch(1).also { releases += it }
    private fun client(server: MockWebServer) = NativeOAuthClient("fixture-project", "fixture-key", server.url("/"))
    private fun token(label: String) = "fixture." + Base64.getUrlEncoder().withoutPadding().encodeToString(
        JSONObject().put("exp", System.currentTimeMillis() / 1000 + 3600).put("label", label).toString().toByteArray()) + ".fixture"
    private fun seed(client: NativeOAuthClient) {
        store.update {
            it.put("access_token", token("old")).put("refresh_token", "saved-refresh").put("task_session", "old-login")
                .put("fixture_pairing_marker", "preserve")
            NativeAuthUserCache.write(it, client.accountEnvironment, NativeSignInUser("old-user", null, null, null))
        }
    }
    private fun account(client: NativeOAuthClient, reader: NativeCredentialStore = store, refresh: ((String) -> String)? = null): NativeAccount {
        cores += reader.refreshCoordinator(client.accountEnvironment)
        return NativeAccount(reader, client, NativeOAuthBrowser(), refreshOverride = refresh)
    }
    private suspend fun until(predicate: () -> Boolean) {
        withTimeout(5000) { while (!predicate()) delay(5) }
    }
    private fun cold() = checkNotNull(NativeCredentialStore.withKeyProvider(context, name, alias).requireReadable())
    private fun assertRetired(owner: NativeAccount) {
        val root = cold()
        assertTrue(root.optString("access_token").isBlank())
        assertTrue(root.optString("refresh_token").isBlank())
        assertFalse(root.has("task_session"))
        assertFalse(NativeSignInCompletion.isPending(root))
        assertFalse(owner.isSignedIn())
        assertNull(owner.sessionRestore.identity())
    }
    private fun record(case: String, values: JSONObject) {
        assertEquals("preserve", cold().getString("fixture_pairing_marker"))
        assertFalse(context.getSharedPreferences(name, 0).getString("state", null)!!.contains("saved-refresh"))
        File(context.getExternalFilesDir(null), "token-retirement").apply { mkdirs() }.resolve("$case.json")
            .writeText(values.put("fixtureOnly", true).put("encryptedStoreUsed", true).put("pairingMarkerPreserved", true)
                .put("actualWorkRetired", store.authWork.activeCount == 0).toString(2))
    }
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name = "token-retirement-fixture-" + UUID.randomUUID()
        alias = "cmux_token_retirement_fixture_" + UUID.randomUUID()
        store = NativeCredentialStore.withKeyProvider(context, name, alias)
    }
    @After fun cleanup() {
        releases.forEach { it.countDown() }
        scope.cancel()
        runBlocking {
            withTimeout(10_000) {
                scope.coroutineContext.job.join()
                while (store.authWork.activeCount > 0) delay(5)
            }
        }
        cores.forEach { it.close() }
        store.clear(); context.deleteSharedPreferences(name); keys().deleteEntry(alias)
    }

    @Test fun cancellationIgnoringRefreshCannotRestoreTokensAfterLocalSignOut() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val client = client(server); seed(client)
            val entered = latch(); val release = latch(); val exchanges = AtomicInteger()
            val owner = account(client, refresh = {
                exchanges.incrementAndGet(); entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)); "late-access"
            })
            val beforeRevision = store.revisions.value
            val pending = scope.async { runCatching { owner.accessToken(forceRefresh = true) } }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertTrue(owner.signOut()); assertRetired(owner)
                assertTrue(store.authWork.activeCount > 0)
                val retiredCiphertext = context.getSharedPreferences(name, 0).getString("state", null)
                release.countDown()
                val failure = withTimeout(5000) { pending.await() }.exceptionOrNull()
                assertTrue(failure is CancellationException)
                until { store.authWork.activeCount == 0 }
                assertRetired(owner)
                assertEquals(retiredCiphertext, context.getSharedPreferences(name, 0).getString("state", null))
                assertEquals(beforeRevision + 2, store.revisions.value)
                assertEquals(1, exchanges.get()); assertEquals(0, server.requestCount)
                record("refresh-after-signout", JSONObject().put("lateRefreshDropped", true).put("postLogoutCiphertextUnchanged", true)
                    .put("credentialRevisions", 2).put("refreshCalls", exchanges.get()))
            } finally { release.countDown(); pending.cancelAndJoin() }
        }
    }

    @Test fun signOutDuringRefreshEncryptionRejectsItsPreferenceCommitAndWaitsForTheActualWriter() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val client = client(server); seed(client)
            val entered = latch(); val release = latch(); val block = AtomicBoolean(true)
            val reader = NativeCredentialStore.withKeyProvider(context, name, alias) {
                if (Thread.currentThread().stackTrace.any { it.className == NativeCredentialStore::class.java.name && it.methodName == "save" } &&
                    block.compareAndSet(true, false)) {
                    entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                }
                keys().getKey(alias, null) as SecretKey
            }
            val owner = account(client, reader, refresh = { "obsolete-access" })
            val beforeRevision = store.revisions.value
            val pending = scope.async { runCatching { owner.accessToken(forceRefresh = true) } }
            var logout: Deferred<Boolean>? = null
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                logout = scope.async { owner.signOut() }
                until { store.authWork.isCapturingSignOutCredentials && store.authWork.cancelledCount > 0 }
                assertFalse(logout.isCompleted)
                assertTrue(store.authWork.activeCount > 0)
                release.countDown()
                assertTrue(withTimeout(5000) { logout.await() })
                assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is CancellationException)
                until { store.authWork.activeCount == 0 }
                assertRetired(owner)
                // Only durable logout intent + its local clear changed the document.
                // An obsolete encrypted refresh commit would add a third revision.
                assertEquals(beforeRevision + 2, store.revisions.value)
                assertFalse(store.authWork.isCapturingSignOutCredentials)
                record("refresh-encryption-signout", JSONObject().put("finalRefreshCommitRejected", true)
                    .put("logoutWaitedForWriter", true).put("credentialRevisions", 2))
            } finally { block.set(false); release.countDown(); pending.cancelAndJoin(); logout?.cancelAndJoin() }
        }
    }

    @Test fun signOutDuringExchangeEncryptionRollsBackItsReceiptWithoutFetchingOrPublishingTheUser() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val client = client(server)
            store.update { it.put("fixture_pairing_marker", "preserve") }
            val entered = latch(); val release = latch(); val block = AtomicBoolean(false)
            val reader = NativeCredentialStore.withKeyProvider(context, name, alias) {
                if (Thread.currentThread().stackTrace.any { it.className == NativeCredentialStore::class.java.name && it.methodName == "save" } &&
                    block.compareAndSet(true, false)) {
                    entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                }
                keys().getKey(alias, null) as SecretKey
            }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/v1/auth/otp/send-sign-in-code" -> MockResponse().setBody("{\"nonce\":\"challenge\"}")
                    "/api/v1/auth/otp/sign-in" -> MockResponse().setBody(JSONObject().put("access_token", token("exchange"))
                        .put("refresh_token", "saved-refresh").toString())
                    else -> MockResponse().setResponseCode(599)
                }
            }
            val owner = account(client, reader); owner.sendCode("fixture@example.test")
            block.set(true)
            val failure = AtomicReference<Throwable?>()
            val pending = scope.launch { runCatching { owner.signIn("abc123") }.onFailure { failure.set(it) } }
            var logout: Deferred<Boolean>? = null
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                logout = scope.async { owner.signOut() }
                // Flow.operation is the inner coroutineScope Job. The outer
                // wrapper catches its CancellationException and need not itself
                // be canceled. Observe the registered actual writer instead.
                until { store.authWork.isCapturingSignOutCredentials && store.authWork.cancelledCount > 0 }
                assertFalse(logout.isCompleted)
                val canceledWriters = store.authWork.cancelledCount
                release.countDown()
                assertTrue(withTimeout(5000) { logout.await() })
                withTimeout(5000) { pending.join() }
                until { store.authWork.activeCount == 0 }
                assertTrue(failure.get() is CancellationException); assertRetired(owner)
                assertEquals(2, server.requestCount)
                assertEquals(listOf("/api/v1/auth/otp/send-sign-in-code", "/api/v1/auth/otp/sign-in"), List(2) { server.takeRequest().path })
                record("exchange-encryption-signout", JSONObject().put("receiptRetired", true).put("userRequests", 0)
                    .put("published", false).put("httpRequests", 2).put("canceledActualWritersBeforeRelease", canceledWriters))
            } finally { block.set(false); release.countDown(); pending.cancelAndJoin(); logout?.cancelAndJoin() }
        }
    }

    @Test fun lateRefreshSuccessCannotOverwriteAnUnverifiedReplacementUsingTheSameRefreshToken() = replacement(false)
    @Test fun lateRefreshRejectionCannotClearAnUnverifiedReplacementUsingTheSameRefreshToken() = replacement(true)
    private fun replacement(rejected: Boolean) = runBlocking<Unit> {
        MockWebServer().use { server ->
            val client = client(server); seed(client)
            val entered = latch(); val release = latch()
            val owner = account(client, refresh = {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                if (rejected) throw NativeAccount.InvalidRefreshToken()
                "obsolete-access"
            })
            val pending = scope.async { runCatching { owner.accessToken(forceRefresh = true) } }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                // Seed the SDK store boundary deliberately. This is not a second
                // interactive sign-in, whose preflight joins the retiring work.
                store.update {
                    it.put("task_session", "replacement").put(NativeSignInCompletion.PENDING, "replacement")
                        .put("access_token", token("replacement"))
                    it.remove(NativeAuthUserCache.KEY); it.remove(NativeAccountProfileCache.KEY)
                }
                val replacementCiphertext = context.getSharedPreferences(name, 0).getString("state", null)
                assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is CancellationException)
                assertTrue(store.authWork.activeCount > 0)
                release.countDown(); until { store.authWork.activeCount == 0 }
                assertEquals(replacementCiphertext, context.getSharedPreferences(name, 0).getString("state", null))
                val root = cold()
                assertTrue(NativeSignInCompletion.isPending(root)); assertEquals("replacement", root.getString("task_session"))
                assertEquals("saved-refresh", root.getString("refresh_token")); assertNull(store.taskSession())
                assertEquals(0, server.requestCount)
                record(if (rejected) "replacement-rejection" else "replacement-success", JSONObject()
                    .put("sameRefreshToken", true).put("pendingReplacementPreserved", true).put("ciphertextUnchanged", true)
                    .put("boundarySeededDirectly", true).put("secondInteractiveSignIn", false))
            } finally { release.countDown(); pending.cancelAndJoin() }
        }
    }
}
