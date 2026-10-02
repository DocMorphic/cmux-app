package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID

/** Generated loopback SSH + private HTTP server; runner refuses physical devices. */
class SshBrowserTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var root: File
    private lateinit var owner: CoroutineScope
    private lateinit var session: NativeSshSession
    private lateinit var host: SshHostRecord
    private lateinit var connection: SshTransport
    private val browserPort get() = args.getString("cmux_ssh_browserport")!!.toInt()
    private fun <T> main(block: () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }
    private suspend fun until(predicate: () -> Boolean) = withTimeout(10000) { while (!predicate()) delay(50) }
    @Before fun setup() {
        assumeTrue(args.containsKey("cmux_ssh_browserport"))
        root = File(compose.activity.noBackupFilesDir, "ssh-browser-${UUID.randomUUID()}")
        var metadata: String? = null
        val hosts = SshHostStore({ metadata }, { metadata = it })
        val vault = SshKeyVault(root, { true }, hosts::removeKeyReferences)
        val key = vault.generate("Browser fixture")
        host = SshHostRecord(name = "Browser fixture", keyId = key.id,
            endpoint = SshEndpoint("127.0.0.1", args.getString("cmux_ssh_port")!!.toInt(), args.getString("cmux_ssh_user")!!))
        hosts.upsert(host)
        assertTrue(hosts.confirmHostKey(hosts.dialPlan(host.id), host.id, hosts.trustSnapshot(host.endpoint), SshHostKey.parse(args.getString("cmux_ssh_hostkey")!!)))
        owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        session = NativeSshSession(hosts, vault, owner) { owner.isActive }
        connection = runBlocking { session.connections.open(host.id) }
    }
    @After fun cleanup() {
        if (::session.isInitialized) {
            main { session.close() }
            runBlocking { delay(300) }
            session.vault.state.value.toList().forEach { session.vault.delete(it.id) }
        }
        if (::owner.isInitialized) owner.cancel()
        if (::root.isInitialized) root.deleteRecursively()
    }
    private suspend fun readAll(lane: BrowserTunnelLane) = ByteArrayOutputStream().use { out ->
        while (true) { val bytes = lane.read() ?: break; out.write(bytes); check(out.size() < 512 * 1024) }
        out.toByteArray()
    }
    private fun socks(proxy: Int, host: String, port: Int): Socket {
        val socket = Socket("127.0.0.1", proxy).apply { soTimeout = 5000 }
        try {
            socket.getOutputStream().write(byteArrayOf(5, 1, 0))
            assertEquals(5, socket.getInputStream().read()); assertEquals(0, socket.getInputStream().read())
            val name = host.toByteArray()
            socket.getOutputStream().write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((port shr 8).toByte(), port.toByte()))
            return socket
        } catch (failure: Throwable) { socket.close(); throw failure }
    }
    private fun reply(socket: Socket): Int {
        val input = socket.getInputStream(); val bytes = ByteArray(10)
        for (i in bytes.indices) { val value = input.read(); check(value >= 0); bytes[i] = value.toByte() }
        assertEquals(5, bytes[0].toInt()); return bytes[1].toInt() and 255
    }
    private suspend fun http(proxy: Int, host: String = "ssh-only.invalid"): String = withContext(Dispatchers.IO) {
        socks(proxy, host, browserPort).use { socket ->
            assertEquals(0, reply(socket))
            socket.getOutputStream().write("GET /api HTTP/1.1\r\nHost: $host:$browserPort\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
    @Test fun directStreamPreservesLargeBinaryPayloadAndResponseAfterHalfClose() = runBlocking {
        val payload = ByteArray(131_099) { (it * 13).toByte() }
        withTimeout(15000) {
            connection.openTcp("ssh-only.invalid", 7).use { lane ->
                lane.write(payload); lane.finishSending()
                assertArrayEquals(payload + byteArrayOf(0, -1) + "SSH-tail".toByteArray(), readAll(lane))
            }
        }
        until { connection.activeChannels == 0 }
        assertTrue(connection.isConnected)
    }
    @Test fun socksUsesServerNamesReconnectsAndNeverFallsBackToPhone() = runBlocking {
        val network = main { session.browsers.network(host.id) }
        val port = network.prepare()
        assertTrue(http(port).endsWith(args.getString("cmux_ssh_nonce")!!))
        assertTrue(http(port, "localhost").endsWith(args.getString("cmux_ssh_nonce")!!))
        connection.close()
        assertEquals(port, network.prepare())
        assertTrue(http(port).endsWith(args.getString("cmux_ssh_nonce")!!))
        withContext(Dispatchers.IO) {
            ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { phone ->
                phone.soTimeout = 500
                socks(port, "127.0.0.1", phone.localPort).use { assertNotEquals(0, reply(it)) }
                assertTrue(runCatching { phone.accept().close() }.exceptionOrNull() is SocketTimeoutException)
            }
        }
        val live = session.connections.autoConnect(host.id)!!
        val lane = live.openTcp("127.0.0.1", args.getString("cmux_ssh_silentport")!!.toInt())
        assertTrue(runCatching { withTimeout(300) { lane.read() } }.exceptionOrNull() is TimeoutCancellationException)
        lane.close(); until { live.activeChannels == 0 }
        assertTrue(http(port).endsWith(args.getString("cmux_ssh_nonce")!!))
    }
    @Test fun routeChangesRetireIdleProxyAndCannotReviveItsStorageIdentity() = runBlocking {
        val network = main { session.browsers.network(host.id) }
        val port = network.prepare()
        assertTrue(http(port).contains("200 OK"))
        main { session.hosts.upsert(host.copy(endpoint = host.endpoint.copy(username = "different-fixture-user"))) }
        withTimeout(5000) { network.retired.await() }
        assertTrue(runCatching { network.prepare() }.isFailure)
        withContext(Dispatchers.IO) { assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure) }
        main { session.hosts.upsert(host) }
        val replacement = main { session.browsers.network(host.id) }
        assertNotEquals(network.storageId, replacement.storageId)
        assertTrue(network.retired.isCompleted)
    }
    @Test fun terminalMenuOpensRealSshBrowserAndRetirementClosesIt() {
        val shell = runBlocking { session.shells.create(host.id) }
        val network = main { session.browsers.network(host.id) }
        val workspace = sshBrowserWorkspace(SshWorkspaceTarget.Shell(shell.id), shell.title)
        main {
            val key = LocalBrowserKey(network.storageId, null, "ssh:${network.storageId}", workspace.id)
            network.navigation.restoreRemembered(key, workspace)
            network.navigation.state.value.local!!.surface.load("http://localhost:$browserPort/page")
        }
        val shown = mutableStateOf(false)
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            SshShellScreen(shell, onBrowser = { shown.value = true }, onBack = {})
            if (shown.value) SshBrowserSheet(SshBrowserPresentation(network, workspace)) { shown.value = false }
        } } }
        compose.onNodeWithTag("ssh.shell.menu").performClick()
        compose.onNodeWithText("Open Browser").performClick()
        compose.waitUntil(15000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        fun text(value: String) = checkNotNull(device.wait(Until.findObject(By.text(value)), 15000)) { "Missing $value" }
        text("SSH routed fixture ▾"); text("SSH route verified")
        text("Next SSH page").click(); text("SSH next ▾")
        assertTrue(device.takeScreenshot(File(compose.activity.getExternalFilesDir(null), "ssh-browser-page.png")))
        main { session.close() }
        compose.waitUntil(10000) { !shown.value }
        assertTrue(network.retired.isCompleted)
        compose.onNodeWithTag("ssh.shell").assertIsDisplayed()
    }
    @Test fun linkedModeRestoresPhonePageAndReturnsToTheExactStreamedTab() {
        val network = main { session.browsers.network(host.id) }
        val workspace = sshBrowserWorkspace(SshWorkspaceTarget.Shell("mode-fixture"), "Workspace").copy(
            browsers = listOf(NativeBrowser("first", "First browser"), NativeBrowser("second", "Second browser")))
        val key = sshLocalBrowserKey(network, workspace)
        val shown = mutableStateOf(true)
        var returned: NativeWorkspaceRoute? = null
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (shown.value) SshBrowserSheet(SshBrowserPresentation(network, workspace, "second", "http://localhost:$browserPort/page"),
                onRoute = { returned = it; shown.value = false }) { shown.value = false }
            else androidx.compose.material3.Button(onClick = { shown.value = true }) { androidx.compose.material3.Text("Reopen linked browser") }
        } } }
        fun text(value: String) = checkNotNull(device.wait(Until.findObject(By.text(value)), 15000)) { "Missing $value" }
        fun description(value: String) = checkNotNull(device.wait(Until.findObject(By.desc(value)), 15000)) { "Missing $value" }
        text("SSH routed fixture ▾")
        val remembered = main { checkNotNull(network.navigation.state.value.local).surface }
        text("Next SSH page").click(); text("SSH next ▾")
        description("Back to workspaces").click()
        compose.waitUntil(15000) { !shown.value }
        assertEquals("http://localhost:$browserPort/next", main { remembered.state.value.url })
        assertTrue(main { network.navigation.prefersOnDevice(key, "second") })
        assertFalse(main { network.navigation.prefersOnDevice(key, "first") })
        compose.onNodeWithText("Reopen linked browser").assertIsDisplayed().performClick()
        // Pump the Compose test clock until the host launches the separate browser Activity.
        compose.waitUntil(15000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        text("SSH next ▾") // Existing phone URL wins over the seed /page URL.
        description("Browser mode").click(); text("Streamed").click()
        compose.waitUntil(15000) { returned != null && !shown.value }
        assertEquals("second", returned!!.browserId)
        assertFalse(main { network.navigation.prefersOnDevice(key, "second") })
        compose.onNodeWithText("Reopen linked browser").assertIsDisplayed().performClick()
        // Pump the Compose test clock until the host launches the separate browser Activity.
        compose.waitUntil(15000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        text("SSH routed fixture ▾") // Switching back forgot the phone page.
        assertTrue(device.takeScreenshot(File(compose.activity.getExternalFilesDir(null), "ssh-browser-mode.png")))
        main { session.close() }
        compose.waitUntil(15000) { !shown.value }
    }

}
