package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** No external server, account storage or automatic SSH connection is needed. */
class NativeComputersManagementTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var root: File
    private lateinit var session: NativeSshSession
    private lateinit var owner: CoroutineScope
    private val mac = NativeCredentialStore.PairedMac("cmux-ios://attach?v=2&r=100.64.0.1:58465", "studio", "Studio", "default")
    private var details: NativeCredentialStore.PairedMac? = null
    private var paired = 0
    private var done = 0
    @Before fun setup() {
        root = File(compose.activity.noBackupFilesDir, "management-ui-" + UUID.randomUUID())
        val hosts = SshHostStore({ null }, {})
        val vault = SshKeyVault(root, { true }, hosts::removeKeyReferences)
        owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        session = NativeSshSession(hosts, vault, owner, admitted = { owner.isActive })
        hosts.upsert(SshHostRecord(name = "Build server", endpoint = SshEndpoint("192.0.2.10", 22, "builder"),
            autoConnectPaused = true))
    }
    @After fun cleanup() {
        session.close(); owner.cancel(); root.deleteRecursively()
    }
    private fun show() = compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
        SshComputersScreen(session, { done++ }, {
            NativeManagedComputerRows(
                NativeComputerList.rows(listOf(mac), NativeMacAppearances(), NativeMacPresenceState(),
                    emptyMap(), NativeMacConnectionPreferences(), emptyList(), emptyMap()),
                NativeMacAppearances(), emptyMap(), emptyMap(), NativeMacPresenceState(), { details = it }, { paired++ })
        }, { paired++ })
    } } }
    @Test fun nativeAndSshRowsShareManagementWithoutDialingAndMenuRoutesCorrectly() {
        show()
        compose.onNodeWithContentDescription("Computer details: Studio").performClick()
        compose.runOnIdle { assertEquals(mac, details); assertTrue(session.connections.statuses.value.isEmpty()) }
        compose.onNodeWithText("Build server").assertIsDisplayed()
        capture()
        compose.onNodeWithContentDescription("Add Computer").performClick()
        compose.onNodeWithText("Add SSH Computer…").performClick()
        compose.onNodeWithTag("ssh.host.editor").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("computers.management").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add Computer").performClick()
        compose.onNodeWithText("Pair Mac…").performClick()
        compose.onNodeWithTag("computers.done").performClick()
        compose.runOnIdle { assertEquals(1, paired); assertEquals(1, done); assertTrue(session.connections.statuses.value.isEmpty()) }
    }
    @Test fun editingExistingSshAndCancellingDeleteRetainHostAndManagement() {
        show()
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        compose.onNodeWithTag("ssh.host.name").assertTextContains("Build server")
        compose.onNodeWithTag("ssh.host.address").assertTextContains("192.0.2.10")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("computers.management").assertIsDisplayed()
        assertEquals("Build server", session.hosts.state.value.hosts.single().name)
        assertTrue(session.connections.statuses.value.isEmpty())
    }
    private fun capture() {
        val i = InstrumentationRegistry.getInstrumentation()
        i.uiAutomation.waitForIdle(100, 3000)
        val folder = File(i.targetContext.getExternalFilesDir(null), "computers-management").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().let { bitmap ->
            File(folder, "combined.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
