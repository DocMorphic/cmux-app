package io.github.docmorphic.cmuxapp

import android.content.ClipboardManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class SshKeysScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var root: File
    private lateinit var vault: SshKeyVault
    private lateinit var hosts: SshHostStore
    @Before fun prepare() {
        root = File(compose.activity.noBackupFilesDir, "ssh-ui-fixture-${UUID.randomUUID()}")
        hosts = SshHostStore({ null }, {})
        vault = SshKeyVault(root, { true }, hosts::removeKeyReferences)
    }
    @After fun cleanup() {
        vault.state.value.toList().forEach { vault.delete(it.id) }
        root.deleteRecursively()
    }
    private fun show() { compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding()) { SshKeysScreen(vault, { true }, {}) } } } }
    private fun waitForKey() = compose.waitUntil(10000) { vault.state.value.size == 1 }
    private fun keyActions() = compose.onNodeWithTag("ssh.key.${vault.state.value.single().id}.actions").performClick()

    @Test fun generateRenameCopyAndConfirmedDeleteUseProductionVault() {
        show()
        compose.onNodeWithTag("ssh.keys.generate").performClick()
        compose.onNodeWithTag("ssh.keys.biometric").assertIsOff()
        capture("ssh-keys-generate")
        compose.onNodeWithTag("ssh.keys.label").performTextInput("Phone fixture")
        compose.onNodeWithTag("ssh.keys.save").performClick(); waitForKey()
        val record = vault.state.value.single()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("ssh.key.${record.id}.name").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("ssh.key.${record.id}.name").assertIsDisplayed()
        capture("ssh-keys-list")
        keyActions(); compose.onNodeWithText("Copy Public Key").performClick()
        compose.runOnIdle { assertEquals(record.publicKey.openSsh, compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
        keyActions(); compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithTag("ssh.keys.rename.label").performTextReplacement("Renamed fixture")
        compose.onNodeWithTag("ssh.keys.rename.confirm").performClick()
        compose.waitUntil(5000) { vault.state.value.single().label == "Renamed fixture" }
        val host = SshHostRecord(name = "Fixture server", endpoint = SshEndpoint("fixture.invalid", username = "test"), keyId = record.id)
        hosts.upsert(host)
        keyActions(); compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Cancel").performClick(); assertEquals(1, vault.state.value.size)
        keyActions(); compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("ssh.keys.delete.confirm").performClick()
        compose.waitUntil(5000) { vault.state.value.isEmpty() }
        assertNull(hosts.state.value.host(host.id)!!.keyId)
        compose.onNodeWithTag("ssh.keys.empty").assertIsDisplayed()
    }

    @Test fun encryptedImportCanRetryWrongPassphraseWithoutLosingKeyText() {
        SshKeyCrypto.encodeSecret(byteArrayOf(1), null)
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
        val output = ByteArrayOutputStream()
        try { pair.writeOpenSSHv1PrivateKey(output, "fixture-pass".toByteArray()) } finally { pair.dispose() }
        val text = output.toString("UTF-8")
        show(); compose.onNodeWithTag("ssh.keys.import").performClick()
        compose.onNodeWithTag("ssh.keys.private").performTextInput(text)
        compose.onNodeWithTag("ssh.keys.passphrase").performScrollTo().performTextInput("wrong")
        compose.onNodeWithTag("ssh.keys.save").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("ssh.keys.error").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(vault.state.value.isEmpty())
        compose.onNodeWithTag("ssh.keys.private").assertTextContains(text)
        compose.onNodeWithTag("ssh.keys.passphrase").performTextReplacement("fixture-pass")
        compose.onNodeWithTag("ssh.keys.save").performClick(); waitForKey()
        assertEquals(SshKeyKind.IMPORTED, vault.state.value.single().kind)
        compose.onNodeWithText("Imported Key").assertIsDisplayed()
    }

    @Test fun secretInputsAreNotRestoredAndSecureWindowFlagIsScoped() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding()) { SshKeysScreen(vault, { true }, {}) } } }
        compose.onNodeWithTag("ssh.keys.import").performClick()
        compose.onNodeWithTag("ssh.keys.label").performTextInput("Safe label")
        compose.onNodeWithTag("ssh.keys.private").performTextInput("fixture-private-secret")
        compose.onNodeWithTag("ssh.keys.passphrase").performScrollTo().performTextInput("fixture-pass-secret")
        compose.runOnIdle { assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("ssh.keys.label").assertTextContains("Safe label")
        assertEquals("", compose.onNodeWithTag("ssh.keys.private").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("", compose.onNodeWithTag("ssh.keys.passphrase").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("ssh.keys.save").assertIsNotEnabled()
        compose.onNodeWithTag("ssh.keys.back").performClick()
        compose.runOnIdle { assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(compose.activity.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
