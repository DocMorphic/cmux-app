package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeAccountDeletionSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    // Separate encrypted storage: these tests never create a production account runtime.
    private val store get() = NativeCredentialStore(context, "account-deletion-fixture")
    private val runtime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentController: NativeAccountDeletionController? = null
    @Before fun setup() {
        store.clear(); store.update { it.put("task_session", "fixture-login").put("refresh_token", "fixture-refresh") }
    }
    @After fun cleanup() { runtime.cancel(); store.clear() }
    private fun controller(server: MockWebServer) = NativeAccountDeletionController(runtime, store::load, store::update) { login ->
        NativeAccountDeletionClient({ NativeDeletionCredentials("fixture-access", "fixture-refresh") },
            { store.taskSession() == login }, server.url("/"), timeoutMillis = 15_000).delete()
    }
    @Composable private fun Screen(controller: NativeAccountDeletionController) {
        val receipt by controller.state.collectAsState()
        var signedIn by remember { mutableStateOf(store.taskSession() != null) }
        LaunchedEffect(controller) { store.revisions.collect { controller.reconcile(); signedIn = store.taskSession() != null } }
        CmuxTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.statusBarsPadding()) {
            Text(if (signedIn) "Fixture signed in" else "Fixture signed out")
            NativeAccountDeletionButton(if (signedIn) store.taskSession() else null, receipt, controller::begin)
            NativeAccountDeletionAlerts(receipt, { login ->
                NativeAccount(store).signOut(login); controller.reconcile(); true
            }, controller::acknowledge)
        } } }
    }
    private fun confirm() {
        compose.onNodeWithText("Delete Account").performClick()
        compose.onNodeWithText("This permanently deletes your cmux account and cmux data. You will be signed out on this device.").assertIsDisplayed()
        compose.onNode(hasText("Delete Account") and hasAnyAncestor(isDialog())).performClick()
    }
    private fun waitForResult(result: NativeAccountDeletionResult) {
        compose.waitUntil(7000) { currentController?.state?.value?.result == result }
    }

    @Test fun confirmationIsNotRestoredButConfirmedRequestSurvivesUiRecreationWithoutDuplicateDelete() {
        MockWebServer().use { server ->
            val release = CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    release.await(30, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(204)
                }
            }
            val controller = controller(server); currentController = controller
            val restore = StateRestorationTester(compose)
            restore.setContent { Screen(controller) }
            try {
                compose.onNodeWithText("Delete Account").performClick()
                capture("account-delete-confirmation")
                compose.onNodeWithText("Cancel").performClick(); assertEquals(0, server.requestCount)
                compose.onNodeWithText("Delete Account").performClick()
                restore.emulateSavedInstanceStateRestore()
                compose.onNodeWithText("Delete Account?").assertDoesNotExist(); assertEquals(0, server.requestCount)
                confirm()
                compose.waitUntil(5000) { server.requestCount == 1 }
                compose.onNodeWithText("Deleting Account…").assertIsNotEnabled().performClick()
                restore.emulateSavedInstanceStateRestore()
                compose.onNodeWithText("Deleting Account…").assertIsNotEnabled()
                assertEquals(1, server.requestCount)
                val encrypted = context.getSharedPreferences("account-deletion-fixture", 0).getString("state", "")!!
                assertFalse(encrypted.contains("fixture-login")); assertFalse(encrypted.contains("PROCESSING"))
                release.countDown()
                compose.waitUntil(5000) { store.taskSession() == null }
                compose.onNodeWithText("Fixture signed out").assertIsDisplayed()
                assertNull(NativeAccountDeletionRecord.read(store.load()))
                assertEquals(1, server.requestCount)
            } finally { release.countDown() }
        }
    }

    @Test fun partialDeletionKeepsSessionAndExplicitRetryReportsCleanupBeforeSigningOut() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":\"account_delete_retryable\"}"))
            server.enqueue(MockResponse().setResponseCode(202).setBody("{\"cleanupIncomplete\":true}"))
            val controller = controller(server); currentController = controller
            compose.setContent { Screen(controller) }
            confirm(); waitForResult(NativeAccountDeletionResult.PARTIAL)
            compose.onNodeWithText(NativeAccountDeletionResult.PARTIAL.message).assertIsDisplayed()
            capture("account-delete-partial")
            assertEquals("fixture-login", store.taskSession()); assertEquals(1, server.requestCount)
            compose.onNodeWithText("OK").performClick()
            assertEquals(1, server.requestCount)
            confirm(); waitForResult(NativeAccountDeletionResult.CLEANUP_INCOMPLETE)
            compose.onNodeWithText("Account Deleted").assertIsDisplayed()
            assertEquals("fixture-login", store.taskSession())
            compose.onNodeWithText("OK").performClick()
            compose.onNodeWithText("Fixture signed out").assertIsDisplayed()
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun lostResponseAndReconstructedControllerKeepUnknownOutcomeWithoutRetry() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            var controller by mutableStateOf(controller(server)); currentController = controller
            compose.setContent { Screen(controller) }
            confirm(); waitForResult(NativeAccountDeletionResult.UNKNOWN)
            val original = controller.state.value
            compose.runOnIdle { controller = controller(server); currentController = controller }
            compose.onNodeWithText(NativeAccountDeletionResult.UNKNOWN.message).assertIsDisplayed()
            assertEquals(original, controller.state.value)
            capture("account-delete-unknown")
            assertEquals("fixture-login", store.taskSession()); assertEquals(1, server.requestCount)
            compose.onNodeWithText("OK").performClick()
            compose.onNodeWithText("Delete Account").assertIsEnabled()
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun deletionCredentialsRefreshWithinLoginAndLateSignOutCannotClearAnotherAccount() = runBlocking<Unit> {
        store.update { it.put("access_token", "expired-fixture") }
        var refreshes = 0
        val account = NativeAccount(store) { refresh ->
            assertEquals("fixture-refresh", refresh); refreshes++; "refreshed-fixture"
        }
        val credentials = account.deletionCredentials("fixture-login")!!
        assertEquals("refreshed-fixture", credentials.access); assertEquals("fixture-refresh", credentials.refresh)
        assertEquals(1, refreshes)
        store.update { it.put("task_session", "replacement-login").put("refresh_token", "replacement-refresh") }
        assertFalse(account.signOut("fixture-login"))
        assertEquals("replacement-login", store.taskSession())
        assertEquals("replacement-refresh", store.load()!!.getString("refresh_token"))
        assertTrue(runCatching { account.deletionCredentials("fixture-login") }.exceptionOrNull() is CancellationException)
        assertEquals(1, refreshes)
    }

    private fun capture(name: String) {
        // Compose semantics can settle before the dialog's window is painted.
        // Wait for the platform transition as well before retaining pixel evidence.
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1000)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = java.io.File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        try { java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
