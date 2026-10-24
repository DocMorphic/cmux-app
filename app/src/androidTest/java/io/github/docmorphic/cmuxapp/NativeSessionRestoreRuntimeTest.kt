package io.github.docmorphic.cmuxapp

import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.security.KeyStoreException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey

/** Real encrypted fixture credentials and production restore/HTTP/UI on the owned emulator. */
class NativeSessionRestoreRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var name: String
    private lateinit var alias: String
    private lateinit var store: NativeCredentialStore
    private var previousSoftInputMode: Int? = null
    private val cached = NativeSignInUser("saved-user", "Saved Person", "saved@example.test", null)
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun token() = "fixture." + Base64.encodeToString(JSONObject().put("exp", System.currentTimeMillis()/1000+3600)
        .toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".fixture"
    private fun client(server: MockWebServer) = NativeOAuthClient("fixture-project", "fixture-public", server.url("/"))
    private fun account(server: MockWebServer, credentials: NativeCredentialStore = store) =
        NativeAccount(credentials, client(server), NativeOAuthBrowser(), refreshOverride = { error("No production refresh request allowed") })
    private fun seed(server: MockWebServer) {
        store.update { root ->
            root.put("task_session", "saved-login").put("access_token", token()).put("refresh_token", "saved-refresh")
                .put("fixture_pairing_marker", "retain")
            NativeAccountProfileCache.writeVerifiedUser(root, "saved-login", client(server).accountEnvironment, cached)
        }
    }
    private fun record(case: String, values: JSONObject) {
        File(target.getExternalFilesDir(null), "session-restore").apply { mkdirs() }
            .resolve("$case.json").writeText(values.put("fixtureOnly", true).toString(2))
    }
    private fun screenshot(case: String) {
        val file=File(target.getExternalFilesDir(null), "session-restore").apply { mkdirs() }.resolve("$case.png")
        assertTrue(androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file))
    }
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="session-restore-fixture-"+UUID.randomUUID();alias="cmux_restore_fixture_"+UUID.randomUUID()
        store=NativeCredentialStore.withKeyProvider(target,name,alias,null)
        compose.runOnUiThread {
            previousSoftInputMode=compose.activity.window.attributes.softInputMode
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    @After fun cleanup() {
        scope.cancel();store.clear();keys().deleteEntry(alias)
        target.deleteSharedPreferences(name)
        compose.runOnUiThread { previousSoftInputMode?.let { compose.activity.window.setSoftInputMode(it) } }
        compose.activity.finish()
    }
    @Test fun encryptedCachedStartupPublishesTheVerifiedUserBeforeWaitingForMembership()=runBlocking<Unit> {
        MockWebServer().use { server ->
            seed(server)
            val encoded=target.getSharedPreferences(name,0).getString("state",null)!!
            assertFalse(encoded.contains("Saved Person"));assertFalse(encoded.contains("saved-refresh"))
            val owner=account(server);val teams=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
            owner.onSignedIn={ login,user ->
                assertEquals("saved-login",login);assertEquals("verified-user",user.id)
                assertTrue(owner.sessionRestore.state.value.authenticated);assertFalse(owner.sessionRestore.state.value.restoring)
                entered.complete(Unit);teams.await()
            }
            assertEquals(NativeAuthState(true,cached,true),owner.sessionRestore.state.value)
            server.enqueue(MockResponse().setBody("{\"id\":\"verified-user\",\"display_name\":\"Verified Person\"}"))
            owner.sessionRestore.start(scope)
            try {
                withTimeout(5000) { entered.await() }
                assertTrue(owner.sessionRestore.revalidating.value)
                assertEquals("Verified Person",owner.sessionRestore.state.value.user?.name)
                assertEquals("Verified Person",NativeAuthUserCache.read(store.load(),client(server).accountEnvironment)?.name)
            } finally { teams.complete(Unit) }
            withTimeout(5000) { owner.sessionRestore.awaitBootstrapped() }
            assertEquals("retain",store.load()!!.getString("fixture_pairing_marker"));assertEquals(1,server.requestCount)
            record("cached-publication",JSONObject().put("encrypted",true).put("publishedBeforeMembership",true).put("requests",server.requestCount))
        }
    }
    @Test fun transientFailureKeepsIdentityAcrossReconstructionAndForegroundRevalidatesIt()=runBlocking<Unit> {
        MockWebServer().use { server ->
            seed(server);server.enqueue(MockResponse().setResponseCode(503))
            val first=account(server);first.sessionRestore.start(scope)
            withTimeout(5000) { first.sessionRestore.awaitBootstrapped() }
            assertEquals(NativeAuthState(true,cached,false),first.sessionRestore.state.value)
            val next=account(server);assertEquals(NativeAuthState(true,cached,true),next.sessionRestore.state.value)
            server.enqueue(MockResponse().setBody("{\"id\":\"saved-user\",\"display_name\":\"Updated Person\"}"))
            next.sessionRestore.revalidate()
            assertEquals("Updated Person",next.sessionRestore.state.value.user?.name)
            assertEquals("saved-refresh",store.load()!!.getString("refresh_token"));assertEquals(2,server.requestCount)
            record("transient-reconstruction",JSONObject().put("cachedIdentityPreserved",true).put("foregroundRevalidated",true))
        }
    }
    @Test fun temporarilyUnreadableStoragePreservesCiphertextThenRetriesOnForeground()=runBlocking<Unit> {
        MockWebServer().use { server ->
            seed(server);val unavailable=java.util.concurrent.atomic.AtomicBoolean(false)
            val reader=NativeCredentialStore.withKeyProvider(target,name,alias) {
                if(unavailable.get()) throw KeyStoreException("Fixture provider unavailable")
                keys().getKey(alias,null) as SecretKey
            }
            val owner=account(server,reader);val before=target.getSharedPreferences(name,0).getString("state",null)
            unavailable.set(true);owner.sessionRestore.start(scope)
            withTimeout(5000) { owner.sessionRestore.awaitBootstrapped() }
            assertEquals(NativeAuthState(true,cached,false),owner.sessionRestore.state.value)
            assertEquals(before,target.getSharedPreferences(name,0).getString("state",null));assertEquals(0,server.requestCount)
            unavailable.set(false);server.enqueue(MockResponse().setBody("{\"id\":\"saved-user\"}"))
            owner.sessionRestore.revalidate()
            assertTrue(owner.sessionRestore.state.value.authenticated);assertFalse(owner.sessionRestore.state.value.restoring)
            assertEquals("retain",store.load()!!.getString("fixture_pairing_marker"));assertEquals(1,server.requestCount)
            record("unreadable-recovery",JSONObject().put("ciphertextPreserved",true).put("foregroundRecovered",true))
        }
    }
    @Test fun anotherAccountSignOutRetiresAnInFlightRestoreAndKeepsNonAccountData()=runBlocking<Unit> {
        MockWebServer().use { server ->
            seed(server)
            server.enqueue(MockResponse().setBody("{\"id\":\"late-user\"}").setBodyDelay(2,TimeUnit.SECONDS))
            val first=account(server);val second=account(server);first.sessionRestore.start(scope)
            assertNotNull(server.takeRequest(5,TimeUnit.SECONDS))
            assertTrue(second.signOut("saved-login"))
            withTimeout(5000) { first.sessionRestore.awaitBootstrapped() }
            first.reconcileStoredSession()
            assertEquals(NativeAuthState(),first.sessionRestore.state.value)
            assertFalse(store.load()!!.has(NativeAuthUserCache.KEY))
            assertEquals("retain",store.load()!!.getString("fixture_pairing_marker"))
            record("other-owner-signout",JSONObject().put("lateRestoreRetired",true).put("nonAccountDataPreserved",true))
        }
    }
    @Test fun bootstrapResumesAnOrphanedPendingExchangeWithoutCancellingItsOwnValidationPhase()=runBlocking<Unit> {
        MockWebServer().use { server ->
            seed(server)
            store.update { root -> root.put("task_session","orphaned-receipt")
                .put(NativeSignInCompletion.PENDING,"orphaned-receipt") }
            val owner=account(server);var memberships=0
            owner.onSignedIn={ login,user ->
                assertEquals("orphaned-receipt",login);assertEquals("restored-user",user.id)
                assertTrue(owner.sessionRestore.state.value.authenticated);memberships++
            }
            server.enqueue(MockResponse().setBody("{\"id\":\"restored-user\",\"display_name\":\"Restored Person\"}"))
            owner.sessionRestore.start(scope)
            withTimeout(5000) { owner.sessionRestore.awaitBootstrapped() }
            assertEquals("restored-user",owner.sessionRestore.state.value.user?.id)
            assertTrue(owner.sessionRestore.state.value.authenticated);assertFalse(owner.sessionRestore.state.value.restoring)
            val root=store.load()!!;assertFalse(root.has(NativeSignInCompletion.PENDING))
            assertEquals("restored-user",NativeAuthUserCache.read(root,client(server).accountEnvironment)?.id)
            assertEquals("retain",root.getString("fixture_pairing_marker"));assertEquals(1,memberships);assertEquals(1,server.requestCount)
            record("pending-bootstrap",JSONObject().put("pendingReceiptVerifiedAndRetired",true)
                .put("productionBootstrapPath",true).put("membershipAfterPublication",true))
        }
    }
    @Test fun originalWarningTimerRetryAndPausedMethodsUseTheProductionScreen() {
        var retries=0;val restoring=mutableStateOf(true)
        val formScope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val form=NativeSignInSession(formScope,{}, {})
        try {
            compose.mainClock.autoAdvance=false
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                NativeSignIn({}, {}, {}, {}, onSignedIn={}, retained=form,restoring=restoring.value,revalidateSession={retries++})
            } } } }
            compose.mainClock.advanceTimeBy(50);compose.waitForIdle()
            compose.onNodeWithText("Restoring session").assertIsDisplayed()
            screenshot("restore-progress")
            compose.onNodeWithTag("signin.google").assertIsNotEnabled()
            compose.onNodeWithTag("signin.email").performTextInput("draft@example.test")
            compose.onNodeWithTag("signin.request").assertIsNotEnabled()
            compose.mainClock.advanceTimeBy(10_001);compose.waitForIdle()
            compose.mainClock.autoAdvance=true
            compose.onNodeWithText("Still restoring session").assertExists().performScrollTo().assertIsDisplayed()
            screenshot("restore-warning")
            compose.onNodeWithTag("signin.restoreRetry").performScrollTo().assertIsDisplayed().performClick()
            compose.mainClock.advanceTimeBy(20);compose.waitForIdle()
            assertEquals(1,retries);compose.onNodeWithText("Restoring session").assertIsDisplayed()
            compose.onNodeWithTag("signin.restoreRetry").assertDoesNotExist()
            compose.runOnUiThread { restoring.value=false }
            compose.mainClock.advanceTimeBy(20);compose.waitForIdle()
            compose.onNodeWithTag("signin.restoreStatus").assertDoesNotExist()
            compose.onNodeWithTag("signin.request").assertIsEnabled()
            compose.onNodeWithTag("signin.email").assertTextContains("draft@example.test")
            record("restore-ui",JSONObject().put("warningAfterTenSeconds",true).put("retryResetsTimer",true)
                .put("keyboardStatusAndRetryReachableByScrolling",true).put("methodsPaused",true).put("emailRemainsEditable",true).put("controlledRestoreState",true))
        } catch(failure:Throwable) {
            screenshot("restore-ui-failure")
            File(target.getExternalFilesDir(null),"session-restore/restore-ui-failure.txt")
                .writeText(compose.onRoot(useUnmergedTree=true).printToString())
            throw failure
        } finally { compose.mainClock.autoAdvance=true;form.close();formScope.cancel() }
    }
}
