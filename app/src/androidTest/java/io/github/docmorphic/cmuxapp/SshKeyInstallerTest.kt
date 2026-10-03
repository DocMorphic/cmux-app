package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID

class SshKeyInstallerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private lateinit var root: File
    private lateinit var hosts: SshHostStore
    private lateinit var vault: SshKeyVault
    private lateinit var key: SshKeyRecord
    private lateinit var lifetime: CoroutineScope
    private lateinit var monitor: SshTransport
    private lateinit var jump: SshHostRecord
    private var metadata = ""
    private var admitted = true
    private var uiSession: NativeSshSession? = null
    private val base get() = args.getString("cmux_ssh_user")!!
    private fun password() = "setup-$base".toByteArray()
    @Before fun setup() {
        assumeTrue(args.getString("cmux_ssh_nonce") == "key-install")
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        root = File(compose.activity.noBackupFilesDir, "ssh-key-install-${UUID.randomUUID()}")
        hosts = SshHostStore({ metadata.takeIf { it.isNotEmpty() } }, { metadata = it })
        vault = SshKeyVault(root, { true }, hosts::removeKeyReferences)
        key = vault.generate("Installation fixture")
        lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        jump = SshHostRecord(name = "Fixture jump", endpoint = SshEndpoint("127.0.0.1", args.getString("cmux_ssh_controlport")!!.toInt(), base), keyId = key.id)
        hosts.upsert(jump)
        monitor = runBlocking { SshTransport.connect(hosts, vault, jump.id, lifetime, { admitted }, true,
            askTrust = { it.presented == SshHostKey.parse(args.getString("cmux_ssh_controlhostkey")!!) }, authorize = { _, _ -> error("Unexpected biometric") }) }
    }
    @After fun cleanup() {
        uiSession?.close()
        if (::monitor.isInitialized) monitor.close()
        if (::lifetime.isInitialized) lifetime.cancel()
        if (::vault.isInitialized) vault.state.value.toList().forEach { vault.delete(it.id) }
        if (::root.isInitialized) root.deleteRecursively()
    }
    private fun host(suffix: String = "normal", viaJump: Boolean = false): SshHostRecord = SshHostRecord(
        name = "Install fixture", endpoint = SshEndpoint("127.0.0.1", args.getString("cmux_ssh_port")!!.toInt(), "$base-${UUID.randomUUID()}-$suffix"),
        keyId = key.id, jumpHostId = if (viaJump) jump.id else null).also(hosts::upsert)
    private suspend fun install(host: SshHostRecord, secret: ByteArray = password(),
        trust: suspend (SshTrustQuestion) -> Boolean = { it.presented == SshHostKey.parse(args.getString("cmux_ssh_hostkey")!!) }) {
        SshKeyInstaller.install(hosts, vault, host, secret, { admitted }, trust, { _, _ -> error("Unexpected biometric") })
    }
    private suspend fun status(host: SshHostRecord) = JSONObject(monitor.exec("fixture-status ${host.endpoint.username}").stdout.toString(Charsets.UTF_8))
    private suspend fun fails(host: SshHostRecord, stage: SshKeyInstallStage, secret: ByteArray = password()) {
        try { install(host, secret); fail("Installation unexpectedly succeeded") }
        catch (failure: SshKeyInstallFailure) {
            assertEquals(stage, failure.stage)
            assertFalse(failure.message.orEmpty().contains("setup-$base"))
        }
        assertTrue(secret.all { it == 0.toByte() })
    }
    @Test fun appendPreservesExistingKeyIsIdempotentAndProvesKeyOnlyLogin() = runBlocking {
        val host = host(); val secret = password()
        install(host, secret); assertTrue(secret.all { it == 0.toByte() })
        install(host)
        val state = status(host)
        assertEquals(2, state.getInt("lines")); assertTrue(state.getBoolean("existing"))
        assertEquals(448, state.getInt("directoryMode")); assertEquals(384, state.getInt("fileMode"))
        assertEquals(2, state.getInt("passwords")); assertEquals(2, state.getInt("commands"))
        assertTrue(state.getInt("publicKeys") >= 2)
        assertFalse(metadata.contains("setup-$base"))
    }
    @Test fun wrongPasswordMakesNoWriteAndDoesNotAutomaticallyRetry() = runBlocking {
        val host = host(); fails(host, SshKeyInstallStage.PASSWORD_LOGIN, "wrong-password".toByteArray())
        val state = status(host)
        assertEquals(1, state.getInt("passwords")); assertEquals(0, state.optInt("commands")); assertEquals(1, state.getInt("lines"))
    }
    @Test fun writeAndVerificationFailuresNeverReportSuccessOrExposeStderr() = runBlocking {
        val denied = host("writefail"); fails(denied, SshKeyInstallStage.WRITE_KEY)
        assertEquals(1, status(denied).getInt("lines"))
        val rejected = host("denyverify"); fails(rejected, SshKeyInstallStage.VERIFY_KEY)
        assertEquals(2, status(rejected).getInt("lines"))
    }
    @Test fun lostWriteReplyIsUnconfirmedAndNeverReplayedAutomatically() = runBlocking {
        val host = host("lostreply"); fails(host, SshKeyInstallStage.WRITE_KEY)
        val state = status(host)
        assertEquals(2, state.getInt("lines")); assertEquals(1, state.getInt("commands"))
        assertEquals(1, state.getInt("passwords"))
    }
    @Test fun jumpUsesKeysAndOnlyDestinationReceivesThePassword() = runBlocking {
        val host = host(viaJump = true); install(host)
        val state = status(host)
        assertEquals(0, state.getInt("jumpPasswords")); assertEquals(1, state.getInt("passwords"))
        assertEquals(2, state.getInt("lines")); assertTrue(state.getInt("publicKeys") >= 1)
    }
    @Test fun canceledOrEditedTrustPromptCannotInstallInAChangedRoute() = runBlocking {
        for (edit in listOf(false, true)) {
            val host = host(); val secret = password(); val asked = CompletableDeferred<Unit>()
            val operation = async { runCatching { install(host, secret) { asked.complete(Unit); awaitCancellation() } } }
            withTimeout(10_000) { asked.await() }
            if (edit) {
                hosts.upsert(host.copy(endpoint = host.endpoint.copy(username = host.endpoint.username + "-changed")))
                withTimeout(10_000) { assertTrue(operation.await().isFailure) }
            } else operation.cancelAndJoin()
            assertTrue(secret.all { it == 0.toByte() })
            assertEquals(0, status(host).optInt("passwords")); assertEquals(1, status(host).getInt("lines"))
        }
    }
    @Test fun keyDeletionDuringTrustCannotPublishAKey() = runBlocking {
        val host = host(); val secret = password(); val asked = CompletableDeferred<Unit>()
        val operation = async { runCatching { install(host, secret) { asked.complete(Unit); awaitCancellation() } } }
        withTimeout(10_000) { asked.await() }
        vault.delete(key.id)
        withTimeout(10_000) { assertTrue(operation.await().isFailure) }
        assertTrue(secret.all { it == 0.toByte() })
        // The monitor's key reference was also removed. Inspect the still-pinned
        // target through a fresh independent monitoring key.
        val next = vault.generate("Fixture observer")
        hosts.upsert(jump.copy(keyId = next.id))
        monitor.close()
        monitor = SshTransport.connect(hosts, vault, jump.id, lifetime, { true }, true, { true }, { _, _ -> error("Unexpected biometric") })
        assertEquals(0, status(host).optInt("passwords")); assertEquals(1, status(host).getInt("lines"))
    }
    private fun editor(restoration: StateRestorationTester = StateRestorationTester(compose)) {
        val session = NativeSshSession(hosts, vault, lifetime) { admitted }.also { uiSession = it }
        restoration.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SshComputersScreen(session) {}; SshPromptHost(session)
        } } }
        compose.onNodeWithTag("ssh.computers.add").performClick()
        compose.onNodeWithTag("ssh.host.address").performTextInput("127.0.0.1")
        compose.onNodeWithTag("ssh.host.port").performTextReplacement(args.getString("cmux_ssh_port")!!)
        compose.onNodeWithTag("ssh.host.username").performTextInput("$base-${UUID.randomUUID()}-ui")
        compose.onNodeWithTag("ssh.key.install").performScrollTo().performClick()
    }
    @Test fun editorInstallsUnsavedDraftThroughTrustAndCanSaveAfterSuccess() {
        editor()
        compose.onNodeWithTag("ssh.key.install.password").performTextInput("setup-$base")
        compose.onNodeWithTag("ssh.key.install.submit").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ssh.trust").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ssh.trust.accept").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("ssh.key.install.success").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ssh.key.install.success").performScrollTo().assertIsDisplayed()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "ssh-key-installed.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }; image.recycle()
        assertFalse(metadata.contains("setup-$base"))
        val installed = hosts.state.value.hosts.single { it.id != jump.id }
        runBlocking { assertEquals(2, status(installed).getInt("lines")) }
        compose.onNodeWithTag("ssh.host.save").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ssh.host.editor").fetchSemanticsNodes().isEmpty() }
        assertEquals(2, hosts.state.value.hosts.size)
    }
    @Test fun dismissedPasswordIsNotKeptWhenTheDialogReopens() {
        editor()
        compose.onNodeWithTag("ssh.key.install.password").performTextInput("temporary-password")
        compose.onAllNodesWithText("Cancel").onLast().performClick()
        compose.onNodeWithTag("ssh.key.install").performScrollTo().performClick()
        compose.onNodeWithTag("ssh.key.install.password").assertTextEquals("Password", "")
        assertEquals(1, hosts.state.value.hosts.size)
        assertFalse(metadata.contains("temporary-password"))
    }
    @Test fun restoredPasswordDialogDiscardsTheSecret() {
        val restoration = StateRestorationTester(compose)
        editor(restoration)
        compose.onNodeWithTag("ssh.key.install.password").performTextInput("temporary-password")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("ssh.key.install.password").assertDoesNotExist()
        compose.onNodeWithTag("ssh.key.install").performScrollTo().performClick()
        compose.onNodeWithTag("ssh.key.install.password").assertTextEquals("Password", "")
        assertEquals(1, hosts.state.value.hosts.size)
        assertFalse(metadata.contains("temporary-password"))
    }
    @Test fun restoringDuringTrustCancelsWithoutRetryAndKeepsSavedDraft() {
        val restoration = StateRestorationTester(compose)
        editor(restoration)
        compose.onNodeWithTag("ssh.key.install.password").performTextInput("setup-$base")
        compose.onNodeWithTag("ssh.key.install.submit").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ssh.trust").fetchSemanticsNodes().isNotEmpty() }
        val saved = hosts.state.value.hosts.single { it.id != jump.id }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { uiSession!!.installPrompts.value.isEmpty() }
        compose.onNodeWithTag("ssh.key.install.success").assertDoesNotExist()
        compose.onNodeWithTag("ssh.key.install.failure").performScrollTo().assertTextContains("Previous installation interrupted", substring = true)
        compose.onNodeWithTag("ssh.key.install.password").assertDoesNotExist()
        runBlocking {
            val state = status(saved)
            assertEquals(0, state.optInt("passwords")); assertEquals(0, state.optInt("commands"))
        }
        assertEquals(saved, hosts.state.value.host(saved.id))
    }
    @Test fun accountRetirementDuringTrustCancelsInstallationAndPrompt() = runBlocking {
        val host = host(); val secret = password()
        val session = NativeSshSession(hosts, vault, lifetime) { admitted }.also { uiSession = it }
        val operation = async { runCatching { session.installKey(host, secret) } }
        withTimeout(10_000) { while (session.installPrompts.value.isEmpty()) delay(10) }
        session.close()
        withTimeout(10_000) { assertTrue(operation.await().isFailure) }
        assertTrue(session.installPrompts.value.isEmpty())
        assertTrue(secret.all { it == 0.toByte() })
        assertEquals(0, status(host).optInt("passwords"))
        assertEquals(0, status(host).optInt("commands"))
    }

    @Test fun declinedTrustCanBeRetriedExplicitlyFromTheSameEditor() {
        editor()
        compose.onNodeWithTag("ssh.key.install.password").performTextInput("setup-$base")
        compose.onNodeWithTag("ssh.key.install.submit").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ssh.trust").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Cancel") and hasAnyAncestor(hasTestTag("ssh.trust"))).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ssh.key.install.failure").fetchSemanticsNodes().isNotEmpty() }
        val saved = hosts.state.value.hosts.single { it.id != jump.id }
        assertFalse(saved.autoConnectPaused)
        runBlocking { assertEquals(0, status(saved).optInt("passwords")) }
        compose.onNodeWithTag("ssh.key.install").performScrollTo().performClick()
        compose.onNodeWithTag("ssh.key.install.password").performTextInput("setup-$base")
        compose.onNodeWithTag("ssh.key.install.submit").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ssh.trust").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ssh.trust.accept").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("ssh.key.install.success").fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            val state = status(saved)
            assertEquals(1, state.getInt("passwords")); assertEquals(1, state.getInt("commands"))
        }
    }

}
