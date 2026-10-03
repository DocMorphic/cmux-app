package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeComputerPresenceRowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var connection by mutableStateOf(NativeComputerConnection())
    private var presence by mutableStateOf(NativeComputerPresence(true, System.currentTimeMillis()))
    private var reconnect by mutableStateOf(false)
    private fun content() = compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(22.dp)) {
            NativeComputerRowLabel("Studio", "Nightly", connection, presence, reconnect, Modifier.weight(1f))
            NativeComputerStatusDot(connection, presence, reconnect)
        }
    } } }
    @Test fun onlinePresenceDoesNotClaimPhoneConnectionAndReconnectUsesHeartbeat() {
        content()
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onNodeWithText("Presence: Online").assertIsDisplayed()
        compose.onNodeWithContentDescription("Computer status: Not connected").assertExists()
        compose.runOnIdle { reconnect = true }
        compose.onNodeWithText("Online").assertIsDisplayed()
        compose.onNodeWithText("Not connected").assertDoesNotExist()
        compose.onNodeWithContentDescription("Computer status: Online").assertExists()
        compose.runOnIdle { connection = NativeComputerConnection(NativeFeedAvailability.CONNECTING) }
        compose.onNodeWithContentDescription("Computer status: Reconnecting…").assertExists()
    }
    @Test fun connectedPhoneSuppressesUnknownHeartbeatAndReconnectDropsCachedWorkspaceCount() {
        connection = NativeComputerConnection(NativeFeedAvailability.CONNECTED, workspaceCount = 3)
        presence = NativeComputerPresence()
        content()
        compose.onNodeWithText("Connected · 3 workspaces").assertIsDisplayed()
        compose.onNodeWithText("Presence: unknown").assertDoesNotExist()
        compose.onNodeWithText("Nightly").assertIsDisplayed()
        capture("connected-without-heartbeat")
        compose.runOnIdle { reconnect = true; connection = NativeComputerConnection(workspaceCount = 3) }
        compose.onNodeWithText("Presence unknown").assertIsDisplayed()
        compose.onNodeWithText("Connected · 3 workspaces").assertDoesNotExist()
        compose.onNodeWithText("3 workspaces").assertDoesNotExist()
        compose.runOnIdle { presence = NativeComputerPresence(false, System.currentTimeMillis() - 120_000) }
        compose.onNode(hasText("Last seen", substring = true)).assertIsDisplayed()
        capture("offline-last-seen")
    }
    @Test fun encryptedHistorySurvivesStoreReloadWithoutMutatingPairingOrRevivingSignOut() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "last_seen_fixture_" + java.util.UUID.randomUUID()
        val store = NativeCredentialStore(context, name)
        try {
            store.update { it.put("refresh_token", "fixture").put("task_session", "login") }
            store.rememberMac("cmux-ios://attach?v=2&r=100.64.0.1:58465", "mac", "Studio", "default")
            val mac = store.pairedMacs().single()
            store.recordMacSeen("login", mac, 1000) { true }
            val restored = NativeCredentialStore(context, name)
            assertEquals(1000L, NativeMacLastSeen.read(restored.load(), mac))
            assertEquals(mac, restored.pairedMacs().single())
            store.clear()
            store.recordMacSeen("login", mac, 2000) { true }
            assertNull(store.load())
        } finally { store.clear() }
    }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "computer-presence-rows").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
