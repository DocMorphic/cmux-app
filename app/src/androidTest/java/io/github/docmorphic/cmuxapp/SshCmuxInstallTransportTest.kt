package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID

/** Live npm HTTPS download, pinned hash, Android SFTP, real isolated installation
 * and phone-owned cmux-tui creation. Never admitted on a physical device. */
class SshCmuxInstallTransportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun downloadUploadActivateAndCreateThroughProductionWorkspaceAction() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cmux_ssh_nonce") == "cmux-install" && android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
        val root = File(compose.activity.noBackupFilesDir, "cmux-install-${UUID.randomUUID()}")
        val cache = root.resolve("cache")
        var metadata: String? = null
        val hosts = SshHostStore({ metadata }, { metadata = it })
        val vault = SshKeyVault(root.resolve("keys"), { true }, hosts::removeKeyReferences)
        val key = vault.generate("private installer fixture")
        val host = SshHostRecord(name = "Install fixture", keyId = key.id,
            endpoint = SshEndpoint("127.0.0.1", args.getString("cmux_ssh_port")!!.toInt(), args.getString("cmux_ssh_user")!!))
        hosts.upsert(host)
        assertTrue(hosts.confirmHostKey(hosts.dialPlan(host.id), host.id, hosts.trustSnapshot(host.endpoint), SshHostKey.parse(args.getString("cmux_ssh_hostkey")!!)))
        val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = NativeSshSession(hosts, vault, lifetime, SshCmuxInstaller(SshCmuxArchive(cache))) { lifetime.isActive }
        try {
            val cmux = runBlocking { session.cmux.open(host.id) }
            compose.waitUntil(10000) { !cmux.state.value.loading }
            assertFalse(cmux.state.value.available); assertTrue(cmux.state.value.providers.isEmpty())
            fun receipt() = runBlocking { JSONObject(cmux.connection.exec("fixture-install-status").stdout.toString(Charsets.UTF_8)) }
            assertEquals(0, receipt().getInt("prepared")) // Merely listing never installs.
            assertFalse(cache.exists())
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                SshWorkspacesRoute(session, host.id) {}
            } } }
            compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("ssh.cmux.create-owned") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("ssh.cmux.create-owned").performClick()
            try { compose.waitUntil(150000) { cmux.state.value.providers.any { it.session == "cmux-android" && it.state.value.tree?.workspaces?.size == 1 } } }
            catch (failure: Exception) { compose.onRoot().printToLog("InstallFixture"); throw failure }
            compose.waitUntil(10000) { cmux.state.value.operation == null && !cmux.state.value.loading }
            assertEquals(1, receipt().getInt("activated")); assertEquals(0, receipt().getInt("stages"))
            val archive = cache.listFiles()!!.single()
            SshCmuxRelease.verify(archive, SshCmuxRelease.integrity.getValue("cmux-tui-darwin-arm64"))
            val desktop = cmux.state.value.providers.single { it.session == "fixture" }.state.value.tree!!.workspaces.single()
            assertEquals("Desktop cmux", desktop.name)
            val owner = cmux.state.value.providers.single { it.session == "cmux-android" }
            val workspace = owner.state.value.tree!!.workspaces.single(); val tab = workspace.tabs.single()
            compose.onNodeWithTag("ssh.cmux.terminal.${workspace.key}.${tab.surface}").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("ssh.shell.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("ssh.shell.composer").performTextReplacement("Installed from Android λ 中")
            compose.onNodeWithTag("ssh.shell.send").performClick()
            compose.onNodeWithTag("ssh.shell.text").performClick()
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(org.hamcrest.Matchers.`is`("terminal-text-snapshot" as Any)))
                .check { view, error -> if (error != null) throw error; assertTrue((view as android.widget.TextView).text.contains("Installed from Android λ 中")) }
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithText("Back").performClick()
            compose.onNodeWithTag("ssh.cmux.create-owned").performScrollTo().performClick()
            compose.waitUntil(15000) { owner.state.value.tree!!.workspaces.size == 2 }
            assertEquals(1, receipt().getInt("prepared")); assertEquals(1, receipt().getInt("activated"))
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(compose.activity.getExternalFilesDir(null), "cmux-ssh-installed.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }; bitmap.recycle()
        } finally {
            compose.runOnIdle { session.close(); lifetime.cancel() }
            vault.state.value.toList().forEach { vault.delete(it.id) }; root.deleteRecursively()
        }
    }
}
