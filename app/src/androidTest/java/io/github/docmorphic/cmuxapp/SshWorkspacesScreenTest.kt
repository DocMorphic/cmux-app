package io.github.docmorphic.cmuxapp

import android.widget.TextView
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.hamcrest.Matchers.`is`
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID

/** Emulator-only, real SSH + cmux-tui + tmux + production Compose/Ghostty. */
class SshWorkspacesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var root: File
    private lateinit var session: NativeSshSession
    private lateinit var lifetime: CoroutineScope
    private lateinit var hostId: UUID
    private lateinit var cmux: SshCmuxHost
    private var metadata: String? = null
    private var sortMetadata: String? = null
    private lateinit var sortStore: NativeWorkspaceSortStore
    @Before fun setup() {
        sortStore = NativeWorkspaceSortStore({ sortMetadata }, { sortMetadata = it })
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cmux_ssh_nonce") == "cmux")
        root = File(compose.activity.noBackupFilesDir, "cmux-ui-${UUID.randomUUID()}")
        val hosts = SshHostStore({ metadata }, { metadata = it })
        val vault = SshKeyVault(root, { true }, hosts::removeKeyReferences)
        val key = vault.generate("mixed fixture")
        val host = SshHostRecord(name = "SSH fixture", keyId = key.id,
            endpoint = SshEndpoint("127.0.0.1", args.getString("cmux_ssh_port")!!.toInt(), args.getString("cmux_ssh_user")!!))
        hostId = host.id; hosts.upsert(host)
        assertTrue(hosts.confirmHostKey(hosts.dialPlan(hostId), hostId, hosts.trustSnapshot(host.endpoint), SshHostKey.parse(args.getString("cmux_ssh_hostkey")!!)))
        lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        session = NativeSshSession(hosts, vault, lifetime) { lifetime.isActive }
        runBlocking {
            assertEquals(0, session.connections.open(hostId).exec("fixture-reset").exitStatus)
            cmux = session.cmux.open(hostId)
        }
        compose.waitUntil(15000) { !cmux.state.value.loading }
        assertEquals(emptyList<String>(), cmux.state.value.errors)
        assertEquals("Desktop cmux", provider().state.value.tree!!.workspaces.single().name)
    }
    @After fun cleanup() {
        if (::session.isInitialized) {
            compose.runOnIdle { session.close(); lifetime.cancel() }
            session.vault.state.value.toList().forEach { session.vault.delete(it.id) }
        }
        if (::root.isInitialized) root.deleteRecursively()
    }
    private fun provider() = cmux.state.value.providers.single { it.session == "fixture" }
    private fun workspace() = provider().state.value.tree!!.workspaces.first { it.name == "Desktop cmux" }
    private fun show() = compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        SshWorkspacesRoute(session, hostId) {}
    } } }
    private fun ready(tag: String = "ssh.shell.composer") {
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun openCmux() {
        val workspace = workspace(); val tab = workspace.tabs.first()
        val tag = "ssh.cmux.terminal.${workspace.key}.${tab.surface}"
        ready(tag); compose.onNodeWithTag(tag).performScrollTo().performClick(); ready()
    }
    private fun send(value: String) {
        ready(); compose.onNodeWithTag("ssh.shell.composer").performTextReplacement(value)
        // The editor is enabled before pending preparation/connection work has
        // settled. Clicking a disabled Send has no effect and leaves the draft.
        ready("ssh.shell.send")
        compose.onNodeWithTag("ssh.shell.send").assertIsEnabled()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitUntil(5000) {
            compose.onNodeWithTag("ssh.shell.composer").fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty().isEmpty()
        }
        waitText(value)
    }
    private fun terminalIdentity(): String? = compose.onAllNodes(SemanticsMatcher("SSH terminal identity") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("ssh.shell.identity.") == true
    }).fetchSemanticsNodes().singleOrNull()?.config?.get(SemanticsProperties.TestTag)
    private fun selectNewTerminal(action: () -> Unit) {
        val old = checkNotNull(terminalIdentity())
        action()
        compose.waitUntil(15000) { terminalIdentity()?.let { it != old } == true &&
            compose.onAllNodes(hasTestTag("ssh.shell.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitText(value: String) {
        val deadline = System.nanoTime() + 10_000_000_000L
        var text = ""
        do {
            ready("ssh.shell.text")
            compose.onNodeWithTag("ssh.shell.text").performClick()
            if (compose.onAllNodesWithText("No terminal text available").fetchSemanticsNodes().isEmpty()) {
                onView(withTagValue(`is`("terminal-text-snapshot" as Any))).check { view, error ->
                    if (error != null) throw error
                    text = (view as TextView).text.toString()
                }
            }
            compose.onNodeWithText("Done").performClick()
            if (text.contains(value)) return
            Thread.sleep(150)
        } while (System.nanoTime() < deadline)
        fail("Expected fixture output '$value', got '$text'")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.waitForIdle(100, 3000); Thread.sleep(300)
        val bitmap = ui.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun withMainFeed(configure: (NativeFixturePeer) -> Unit = {}, windowSize: (() -> Pair<Int, Int>)? = null, check: (NativeFixturePeer, StateRestorationTester) -> Unit) {
        compose.runOnUiThread {
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val store = NativeCredentialStore(compose.activity)
        val peer = NativeFixturePeer().also(configure)
        store.clear(); store.update {
            it.put("refresh_token", "emulator-ssh-feed-fixture")
            it.put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465")
        }
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
                val configuration = LocalConfiguration.current
                val override = windowSize?.invoke()?.let { (width, height) -> android.content.res.Configuration(configuration).also {
                    it.screenWidthDp = width; it.screenHeightDp = height
                } } ?: configuration
                CompositionLocalProvider(LocalConfiguration provides override) {
                NativeScreen(onUseHelper = {}, sshSessionOverride = session, sortStoreOverride = sortStore, connector = NativeConnector { _, _ ->
                    MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
                })
            } } } }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Desktop cmux").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
            check(peer, restoration)
        } catch (failure: Throwable) {
            runCatching { capture("ssh-feed-test-failure") }
            throw failure
        } finally { compose.activity.finish(); peer.close(); store.clear() }
    }
    private fun selectSshFeed() {
        compose.onNodeWithContentDescription("Computer filter").performClick()
        compose.onNodeWithTag("computer.select.ssh:$hostId").performClick()
        compose.waitUntil { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isEmpty() }
    }
    private fun feedRow(kind: SshWorkspaceKind): SshFeedRow = session.workspaceFeed.state.value[hostId]!!.rows.first { it.kind == kind }
    private fun openFeedClose(kind: SshWorkspaceKind) {
        val row = feedRow(kind)
        compose.onNodeWithTag("workspace.row:${row.key}").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText(checkNotNull(row.confirmation).title).assertIsDisplayed()
    }

    @Test fun mainFeedListsFiltersSearchesAndOpensExistingSshInventoryWithoutMacRpc() = withMainFeed { peer, restoration ->
        compose.onNodeWithText("desktop-tmux").assertIsDisplayed()
        capture("main-unified-workspaces")
        selectSshFeed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Claude Code task").assertDoesNotExist()
        compose.onNodeWithText("Desktop cmux").assertIsDisplayed()
        compose.onNodeWithContentDescription("New Workspace").performClick()
        compose.onNodeWithTag("ssh.workspace.create.TMUX").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithContentDescription("Search").performClick()
        val paneQuery = feedRow(SshWorkspaceKind.TMUX).workspace.terminals.first().title
        assertFalse("Search must exercise pane metadata", "desktop-tmux SSH fixture".contains(paneQuery, ignoreCase = true))
        compose.onNode(hasSetTextAction()).performTextReplacement(paneQuery)
        compose.onNodeWithText("Desktop cmux").assertDoesNotExist()
        capture("ssh-feed-search-results")
        compose.waitUntil(5000) { compose.onAllNodesWithText("desktop-tmux").fetchSemanticsNodes().isNotEmpty() &&
            compose.onNodeWithText("desktop-tmux").isDisplayed() }
        compose.onNodeWithText("desktop-tmux").assertIsDisplayed()
        capture("ssh-feed-search-keyboard")
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNodeWithText("Desktop cmux").performClick(); ready(); send("Opened from unified feed")
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("desktop-tmux").performClick(); ready(); send("Tmux from unified feed")
        compose.onNodeWithText("Back").performClick()
        assertTrue(peer.requests.none { it.optString("method") in setOf("workspace.create", "workspace.close", "mobile.terminal.replay") })
        capture("ssh-selected-workspaces")
        compose.onNodeWithContentDescription("Computer filter").performClick()
        compose.onNode(hasText("All Computers") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("Claude Code task").assertIsDisplayed()
        compose.onNodeWithContentDescription("Computer filter").performClick()
        compose.onNodeWithText("Add Computer").performClick()
        compose.onNodeWithTag("computers.pairing.help").assertIsDisplayed()
    }

    @Test fun wideSidebarKeepsLiveTerminalDuringTabsSearchReflowAndSwitchesMacSshDestinations() {
        var size by mutableStateOf(1000 to 700)
        withMainFeed(windowSize = { size }) { peer, restoration ->
            compose.onNodeWithTag("workspace.shell.split").assertExists()
            compose.onNodeWithTag("workspace.shell.placeholder").assertIsDisplayed()
            compose.onNodeWithText("Desktop cmux").performClick(); ready(); send("Wide cmux live terminal")
            val cmuxIdentity = terminalIdentity()
            compose.onNodeWithContentDescription("Hide sidebar").performClick()
            assertEquals(cmuxIdentity, terminalIdentity())
            compose.onNodeWithContentDescription("Show sidebar").performClick()
            compose.onNode(hasText("Notifications", substring = true) and hasClickAction() and
                hasAnyAncestor(hasTestTag("workspace.shell.sidebar"))).performClick()
            assertEquals(cmuxIdentity, terminalIdentity())
            compose.onNode(hasText("Workspaces") and hasClickAction()).performClick()
            compose.onNodeWithContentDescription("Search").performClick()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("workspace.shell.sidebar"))).performTextReplacement("no matching workspace")
            assertEquals(cmuxIdentity, terminalIdentity())
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithContentDescription("Cancel search").assertDoesNotExist()
            assertEquals(cmuxIdentity, terminalIdentity())
            compose.onNodeWithTag("ssh.shell.composer").performTextReplacement("Unsent through resizing")
            compose.onNodeWithContentDescription("Search").performClick()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("workspace.shell.sidebar"))).performTextReplacement("desktop")
            compose.runOnIdle { size = 412 to 850 }
            compose.onNodeWithTag("workspace.shell.stack").assertExists()
            compose.onNodeWithText("Unsent through resizing").assertExists()
            assertEquals(cmuxIdentity, terminalIdentity())
            compose.runOnIdle { size = 1000 to 420 }
            compose.onNodeWithTag("workspace.shell.stack").assertExists()
            compose.runOnIdle { size = 1000 to 700 }
            compose.onNodeWithTag("workspace.shell.split").assertExists()
            compose.onNodeWithContentDescription("Cancel search").assertDoesNotExist()
            compose.onNodeWithText("Unsent through resizing").assertExists()
            assertEquals(cmuxIdentity, terminalIdentity())
            compose.onNodeWithTag("ssh.shell.send").performClick(); waitText("Unsent through resizing")
            compose.onNodeWithContentDescription("Search").performClick()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("workspace.shell.sidebar"))).assertTextEquals("desktop")
            compose.onNodeWithContentDescription("Cancel search").performClick()
            capture("wide-cmux-sidebar")
            compose.onNodeWithText("desktop-tmux").performClick(); ready(); send("Wide tmux live terminal")
            assertNotEquals(cmuxIdentity, terminalIdentity())
            compose.onNodeWithText("Claude Code task").performClick()
            ready("native-terminal"); compose.onNodeWithTag("ssh.shell").assertDoesNotExist()
            capture("wide-native-sidebar")
            compose.onNodeWithText("Desktop cmux").performClick(); ready()
            compose.onNodeWithTag("native-terminal").assertDoesNotExist()
            waitText("Wide cmux live terminal")
            compose.onNodeWithContentDescription("Hide sidebar").performClick()
            restoration.emulateSavedInstanceStateRestore(); ready()
            compose.onNodeWithTag("workspace.shell.sidebar").assertDoesNotExist()
            compose.onNodeWithContentDescription("Show sidebar").performClick()
            send("Wide saved-screen restoration")
            assertTrue(peer.requests.none { it.optString("method") in setOf("workspace.create", "workspace.close", "workspace.move") })
        }
    }

    @Test fun mainFeedSortsMacAndSshTogetherPersistsOrderAndKeepsSingleComputerOrder() = withMainFeed(configure = { peer ->
        peer.customWorkspaceListing = org.json.JSONObject("""{"groups":[],"workspaces":[
            {"id":"workspace-1","title":"Claude Code task","last_activity_at":1,"terminals":[{"id":"terminal-1","title":"Shell"}]},
            {"id":"workspace-2","title":"Recent workspace","last_activity_at":200,"terminals":[{"id":"terminal-2","title":"Shell"}]}]}""")
    }) { peer, restoration ->
        fun filter() = compose.onNodeWithContentDescription("Filter workspaces").performClick()
        fun y(title: String) = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top
        filter(); compose.onNodeWithText("Custom Order").performClick()
        val sshId = workspaceSshFilterId(hostId)
        val action = compose.onNodeWithTag("workspace.sort.computer:$sshId").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].single { it.label == "Move up" }
        compose.runOnUiThread { assertTrue(action.action()) }
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5000) { y("Desktop cmux") < y("Claude Code task") }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5000) { y("Desktop cmux") < y("Claude Code task") }
        assertEquals(sortStore.state.value, NativeWorkspaceSortStore({ sortMetadata }, {}).state.value)
        capture("custom-computer-order")
        filter(); compose.onNodeWithText("Recent Activity").performClick()
        compose.waitUntil(5000) { y("Recent workspace") < y("Claude Code task") && y("Claude Code task") < y("Desktop cmux") }
        capture("recent-workspace-order")
        compose.onNodeWithContentDescription("Computer filter").performClick()
        compose.onNodeWithText("Fixture Mac").performClick()
        compose.waitUntil(5000) { y("Claude Code task") < y("Recent workspace") }
        filter(); compose.onNodeWithText("Recent Activity").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithContentDescription("Computer filter").performClick()
        compose.onNode(hasText("All Computers") and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntil(5000) { y("Recent workspace") < y("Claude Code task") }
        assertTrue(peer.requests.none { it.optString("method") in setOf("workspace.move","workspace.create","workspace.close") })
    }

    @Test fun compoundMachineUnreadFilterSurvivesRestorationAndClearsWhenScopeMakesItHidden() = withMainFeed { peer, restoration ->
        fun filter() = compose.onNodeWithContentDescription("Filter workspaces").performClick()
        val sshTag = "workspace.filter.machine:${workspaceSshFilterId(hostId)}"
        filter(); compose.onNodeWithTag(sshTag).performClick()
        compose.onNodeWithText("Claude Code task").assertDoesNotExist()
        compose.onNodeWithText("Desktop cmux").assertIsDisplayed()
        filter(); compose.onNodeWithText("Unread").performClick()
        compose.onNodeWithText("No unread workspaces on the selected machines").assertIsDisplayed()
        compose.onNodeWithText("Desktop cmux").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("No unread workspaces on the selected machines").assertIsDisplayed()
        compose.onNodeWithTag("workspace.filter.showAll").performClick()
        compose.onNodeWithText("Claude Code task").assertIsDisplayed()
        compose.onNodeWithText("Desktop cmux").assertIsDisplayed()
        filter(); compose.onNodeWithTag(sshTag).performClick()
        filter(); compose.onNodeWithText("Unread").performClick()
        filter(); compose.onNodeWithTag(sshTag).assertIsSelected()
        compose.onNodeWithText("All workspaces").performClick()
        compose.onNodeWithText("Claude Code task").assertDoesNotExist()
        compose.onNodeWithText("Desktop cmux").assertIsDisplayed()
        filter(); compose.onNodeWithText("All Machines").performClick()
        compose.onNodeWithText("Claude Code task").assertIsDisplayed()
        filter(); compose.onNodeWithTag(sshTag).performClick()
        selectSshFeed()
        filter(); compose.onNodeWithTag(sshTag).assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithContentDescription("Computer filter").performClick()
        compose.onNode(hasText("All Computers") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("Claude Code task").assertIsDisplayed()
        filter(); compose.onNodeWithText("All Machines").assertIsSelected()
        capture("compound-workspace-filter")
        androidx.test.espresso.Espresso.pressBack()
        assertTrue(peer.requests.none { it.optString("method") in setOf("workspace.create", "workspace.move", "workspace.close") })
    }

    @Test fun mainFeedReopensLastSelectedCmuxAndTmuxPanesAfterSavedRestoration() = withMainFeed { peer, restoration ->
        selectSshFeed()
        for ((title, action, kind) in listOf(Triple("Desktop cmux", "New Screen", SshWorkspaceKind.CMUX_TUI),
            Triple("desktop-tmux", "New Window", SshWorkspaceKind.TMUX))) {
            compose.onNodeWithText(title).performClick(); ready()
            val first = terminalIdentity()
            compose.onNodeWithTag("ssh.shell.menu").performClick()
            selectNewTerminal { compose.onNodeWithText(action).performScrollTo().performClick() }
            val second = checkNotNull(terminalIdentity())
            assertNotEquals(first, second)
            val marker = "Remembered second $kind pane"
            send(marker)
            compose.onNodeWithText("Back").performClick()
            val store = NativeCredentialStore(compose.activity)
            val login = checkNotNull(store.taskSession())
            val row = feedRow(kind)
            val saved = store.lastWorkspaceTab(login, sshWorkspaceTabKey(login, row.host, row.targets.first()))
            assertNotNull(saved)
            assertNotEquals(row.targets.first(), row.reopenTarget(saved))
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText(title).performClick(); ready()
            assertEquals(second, terminalIdentity()); waitText(marker)
            restoration.emulateSavedInstanceStateRestore(); ready()
            assertEquals(second, terminalIdentity()); send("Restored $kind selection")
            compose.onNodeWithText("Back").performClick()
        }
        assertEquals(2, workspace().screens.size)
        assertTrue(peer.requests.none { it.optString("method") in setOf("workspace.create", "workspace.close", "mobile.terminal.replay") })
        capture("ssh-last-selected-panes")
    }

    @Test fun mainFeedClosePreservesConfirmationSnapshotAndTargetsOnlyTheSshWorkspace() = withMainFeed { peer, _ ->
        selectSshFeed(); openFeedClose(SshWorkspaceKind.CMUX_TUI)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, provider().state.value.tree!!.workspaces.size)
        openFeedClose(SshWorkspaceKind.CMUX_TUI)
        val old = workspace()
        runBlocking { provider().newScreen(old) }
        compose.waitUntil { workspace().tabs.size == 2 }
        compose.onNodeWithTag("workspace.close.confirm").performClick()
        compose.waitUntil(10000) { session.workspaceFeed.state.value[hostId]?.error?.contains("contents changed") == true }
        assertEquals(1, provider().state.value.tree!!.workspaces.size)
        openFeedClose(SshWorkspaceKind.CMUX_TUI)
        compose.onNodeWithTag("workspace.close.confirm").performClick()
        compose.waitUntil(15000) { session.workspaceFeed.state.value[hostId]?.rows?.none { it.kind == SshWorkspaceKind.CMUX_TUI } == true }
        compose.onNodeWithText("Desktop cmux").assertDoesNotExist()
        compose.onNodeWithText("desktop-tmux").assertIsDisplayed()
        openFeedClose(SshWorkspaceKind.TMUX)
        compose.onNodeWithTag("workspace.close.confirm").performClick()
        compose.waitUntil(10000) { session.workspaceFeed.state.value[hostId]?.rows?.isEmpty() == true }
        compose.onNodeWithText("No workspaces yet").assertIsDisplayed()
        assertTrue(peer.requests.none { it.optString("method") == "workspace.close" })
    }

    @Test fun emptyCmuxWorkspaceRestoresWithoutCreatingThenOpensItsExplicitNewTerminal() = withMainFeed { peer, restoration ->
        selectSshFeed()
        val result = runBlocking { cmux.connection.exec("fixture-empty-workspace") }
        assertEquals(0, result.exitStatus)
        val key = result.stdout.toString(Charsets.UTF_8).trim()
        compose.runOnIdle { provider().refresh() }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Empty cmux").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Empty cmux").performClick()
        ready("WorkspaceWaiting")
        assertTrue(provider().state.value.tree!!.workspaces.single { it.key == key }.tabs.isEmpty())
        restoration.emulateSavedInstanceStateRestore(); ready("WorkspaceWaiting")
        compose.onNodeWithText("New terminal").assertIsEnabled()
        compose.onNodeWithText("New browser").assertIsEnabled()
        assertTrue(provider().state.value.tree!!.workspaces.single { it.key == key }.tabs.isEmpty())
        capture("ssh-empty-workspace")
        compose.onNodeWithText("New terminal").performClick(); ready(); send("Created inside empty workspace")
        val created = provider().state.value.tree!!.workspaces.single { it.key == key }
        assertEquals(1, created.tabs.size)
        val identity = terminalIdentity()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Empty cmux").performClick(); ready()
        assertEquals(identity, terminalIdentity()); waitText("Created inside empty workspace")
        assertEquals(1, provider().state.value.tree!!.workspaces.single { it.key == key }.tabs.size)
        assertTrue(peer.requests.none { it.optString("method") in setOf("workspace.create", "terminal.create") })
    }

    @Test fun independentPhoneBrowserReconstructsFromPreferenceAndReopensAfterReturningToFeed() = withMainFeed { peer, restoration ->
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun text(value: String) = checkNotNull(device.wait(Until.findObject(By.text(value)), 15000)) { "Missing $value" }
        fun back() = checkNotNull(device.wait(Until.findObject(By.desc("Back to workspaces")), 10000)).click()
        selectSshFeed()
        val row = feedRow(SshWorkspaceKind.CMUX_TUI)
        val store = NativeCredentialStore(compose.activity)
        val login = checkNotNull(store.taskSession())
        val key = sshWorkspaceTabKey(login, row.host, checkNotNull(row.openTarget()))
        // A stored local-tab choice with no in-memory page models a cold browser reconstruction.
        assertTrue(store.rememberWorkspaceTab(login, key, NativeWorkspaceTab.LocalBrowser))
        compose.onNodeWithText("Desktop cmux").performClick()
        compose.waitUntil(15000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        val selector = By.clazz("android.widget.EditText").hasDescendant(By.desc("Browser address"))
        val address = checkNotNull(device.wait(Until.findObject(By.copy(selector).text("https://duckduckgo.com/")), 15000))
        address.click()
        assertTrue(device.wait(Until.hasObject(By.copy(selector).focused(true)), 5000))
        val url = "http://localhost:${InstrumentationRegistry.getArguments().getString("cmux_ssh_browserport")}/"
        address.text = url
        assertTrue(device.wait(Until.hasObject(By.copy(selector).text(url)), 5000)); device.pressEnter()
        text("SSH workspace page ▾"); text("Next workspace page").click(); text("SSH workspace next ▾")
        back()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Desktop cmux").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(NativeWorkspaceTab.LocalBrowser, store.lastWorkspaceTab(login, key))
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Desktop cmux").performClick()
        compose.waitUntil(15000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        text("SSH workspace next ▾")
        assertTrue(device.takeScreenshot(File(compose.activity.getExternalFilesDir(null), "ssh-restored-local-browser.png")))
        val terminalTitle = sshCmuxPicker(provider().session, provider().state.value.tree!!, workspace()).sections.first().rows.first().title
        text("SSH workspace next ▾").click(); text(terminalTitle).click()
        ready(); send("Selected terminal after phone browser")
        assertEquals(NativeWorkspaceTabKind.TERMINAL, store.lastWorkspaceTab(login, key)?.kind)
        compose.onNodeWithText("Back").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Desktop cmux").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Desktop cmux").performClick(); ready(); waitText("Selected terminal after phone browser")
        assertEquals(1, workspace().tabs.size)
        assertTrue(peer.requests.none { it.optString("method") in setOf("workspace.create", "terminal.create", "browser.create") })
    }

    @Test fun mainFeedRetainsDisconnectedRowsHonorsPauseAndRetriesOnlyItsSshHost() = withMainFeed { peer, restoration ->
        compose.runOnIdle { session.connections.disconnect(hostId) }
        compose.waitUntil { session.hosts.state.value.host(hostId)?.autoConnectPaused == true }
        selectSshFeed(); restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("ssh.feed.retry:$hostId").assertIsEnabled()
        compose.runOnIdle {
            assertTrue(session.hosts.state.value.host(hostId)!!.autoConnectPaused)
            assertEquals(SshConnectionPhase.IDLE, session.connections.statuses.value[hostId]?.phase)
        }
        compose.onNodeWithText("Desktop cmux").assertIsDisplayed()
        capture("ssh-feed-disconnected")
        compose.onNodeWithTag("ssh.feed.retry:$hostId").performClick()
        compose.waitUntil(15000) { session.connections.statuses.value[hostId]?.phase == SshConnectionPhase.CONNECTED &&
            session.workspaceFeed.state.value[hostId]?.rows?.size == 2 && session.workspaceFeed.state.value[hostId]?.loading == false }
        assertFalse(session.hosts.state.value.host(hostId)!!.autoConnectPaused)
        compose.waitUntil(10000) { compose.onAllNodesWithText("Desktop cmux").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Desktop cmux").performClick(); ready(); send("Reconnected from feed")
        assertTrue(peer.requests.none { it.optString("method") in setOf("workspace.create", "workspace.close", "mobile.terminal.replay") })
    }

    @Test fun mainChooserCreatesEverySshKindWithoutMutatingTheMacAndRestoresWithoutReplay() {
        verifyMainChooser(withMac = true)
    }

    @Test fun mainChooserCreatesWithOnlyAnSshComputerAndNoMacPairing() {
        verifyMainChooser(withMac = false)
    }

    private fun verifyMainChooser(withMac: Boolean) {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val store = NativeCredentialStore(compose.activity)
        val peer = if (withMac) NativeFixturePeer() else null
        store.clear()
        store.update {
            it.put("refresh_token", "emulator-ssh-main-fixture")
            if (withMac) it.put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465")
        }
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
                NativeScreen(onUseHelper = {}, sshSessionOverride = session, connector = NativeConnector { _, _ ->
                    checkNotNull(peer) { "No Mac pairing in this fixture" }
                    MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
                })
            } } }
            val kinds = if (withMac) SshWorkspaceKind.entries else listOf(SshWorkspaceKind.SHELL)
            for (kind in kinds) {
                compose.waitUntil(15000) { compose.onAllNodes(hasContentDescription("New Workspace") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("New Workspace").performClick()
                if (withMac) compose.onNodeWithTag("workspace.create.ssh:$hostId").performClick()
                capture(if (withMac) "main-ssh-kind-chooser" else "ssh-only-kind-chooser")
                compose.onNodeWithTag("ssh.workspace.create.${kind.name}").performClick()
                ready(); send("Main chooser ${kind.name}")
                val before = terminalIdentity()
                val shellCount = session.shells.state.value.size
                restoration.emulateSavedInstanceStateRestore()
                ready(); assertEquals(before, terminalIdentity())
                assertEquals(shellCount, session.shells.state.value.size)
                waitText("Main chooser ${kind.name}")
                capture("main-ssh-created-${kind.name}")
                compose.onNodeWithText("Back").performClick()
            }
            assertTrue(peer?.requests?.none { it.optString("method") == "workspace.create" } != false)
            assertEquals(1, session.shells.state.value.count { it.hostId == hostId })
            if (withMac) {
                val owned = cmux.state.value.providers.single { it.session == "cmux-android" }
                assertEquals(1, owned.state.value.tree!!.workspaces.size)
                val tmux = runBlocking { session.tmux.open(hostId) }
                assertEquals(2, tmux.state.value.workspaces.size) // original fixture + exactly one new session
            }
        } finally {
            compose.activity.finish(); peer?.close(); store.clear()
        }
    }

    @Test fun creationMenuOpensEachKindOnItsOwningHostAndRejectsAnEditedRoute() {
        show(); ready("ssh.cmux.create-owned")
        fun create(kind: SshWorkspaceKind) {
            compose.onNodeWithTag("ssh.workspace.create").performClick()
            compose.onNodeWithText("New cmux-tui Workspace").assertIsDisplayed()
            compose.onNodeWithText("New tmux Session").assertIsDisplayed()
            compose.onNode(hasText("New Shell") and hasAnyAncestor(isPopup())).assertIsDisplayed()
            capture("ssh-create-kinds")
            compose.onNodeWithTag("ssh.workspace.create.${kind.name}").performClick()
            ready(); send("Created ${kind.name} here")
            waitText("Created ${kind.name} here")
            compose.onNodeWithText("Back").performClick()
            ready("ssh.cmux.create-owned")
        }
        create(SshWorkspaceKind.CMUX_TUI)
        create(SshWorkspaceKind.TMUX)
        create(SshWorkspaceKind.SHELL)
        assertEquals(1, session.shells.state.value.count { it.hostId == hostId })
        val expected = session.hosts.state.value.host(hostId)!!
        val stale = expected.copy(endpoint = expected.endpoint.copy(port = if (expected.endpoint.port == 22) 23 else 22))
        val failure = runBlocking { runCatching { session.createWorkspace(stale, SshWorkspaceKind.SHELL) }.exceptionOrNull() }
        assertTrue(failure?.message?.contains("SSH computer changed") == true)
        assertEquals(1, session.shells.state.value.count { it.hostId == hostId })
    }

    @Test fun mixedWorkspacesStreamReopenCreateAndEndWithConfirmation() {
        show(); openCmux(); waitText("Remote cmux λ 中"); send("Phone cmux λ 中")
        capture("cmux-ssh-terminal")
        compose.onNodeWithText("Back").performClick()
        openCmux(); waitText("Phone cmux λ 中")
        compose.onNodeWithText("Back").performClick()
        val key = workspace().key
        compose.onNodeWithTag("ssh.cmux.new-terminal.$key").performScrollTo().performClick()
        compose.waitUntil(15000) { workspace().tabs.size == 2 }
        ready(); compose.onNodeWithText("Back").performClick()
        ready("ssh.cmux.create.fixture")
        compose.onNodeWithTag("ssh.cmux.create.fixture").performScrollTo().performClick()
        compose.waitUntil(15000) { provider().state.value.tree!!.workspaces.size == 2 }
        capture("cmux-ssh-workspaces")
        ready("ssh.cmux.end.$key")
        compose.onNodeWithTag("ssh.cmux.end.$key").performScrollTo().performClick()
        compose.onNodeWithText("End “Desktop cmux” on SSH fixture?").assertIsDisplayed()
        compose.onNodeWithText("Close Workspace").assertIsDisplayed()
        capture("cmux-close-confirmation")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(2, provider().state.value.tree!!.workspaces.size)
        compose.onNodeWithTag("ssh.cmux.end.$key").performClick()
        compose.onNodeWithText("Close Workspace").performClick()
        compose.waitUntil(15000) { provider().state.value.tree!!.workspaces.none { it.key == key } }
        val tmux = runBlocking { session.tmux.open(hostId) }
        compose.waitUntil(10000) { !tmux.state.value.loading }
        val tw = tmux.state.value.workspaces.single(); val tp = tw.panes.first()
        compose.onNodeWithTag("ssh.tmux.pane.${tw.id}.${tp.id}").performScrollTo().performClick()
        ready(); waitText("Remote tmux"); send("Phone tmux")
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithTag("ssh.workspaces.new-shell").performScrollTo().performClick()
        ready(); waitText("Plain shell fixture λ 中"); send("Phone shell")
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithTag("ssh.tmux.end.${tw.id}").performScrollTo().performClick()
        compose.onNodeWithText("End “desktop-tmux” on SSH fixture?").assertIsDisplayed()
        compose.onNodeWithText("End Session").assertIsDisplayed()
        capture("tmux-close-confirmation")
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(tmux.state.value.workspaces.any { it.id == tw.id })
        compose.onNodeWithTag("ssh.tmux.end.${tw.id}").performClick()
        compose.onNodeWithText("End Session").performClick()
        compose.waitUntil(10000) { tmux.state.value.workspaces.none { it.id == tw.id } }
        assertEquals(1, session.shells.state.value.size)
    }
    @Test fun createsOnlyPhoneOwnedSessionAndKeepsDesktopWorkspaceIntact() {
        show(); ready("ssh.cmux.create-owned")
        val desktop = workspace(); val original = provider().state.value.tree!!.registry
        compose.onNodeWithTag("ssh.cmux.create-owned").performScrollTo().performClick()
        compose.waitUntil(20000) { cmux.state.value.providers.any { it.session == "cmux-android" && it.state.value.tree?.workspaces?.size == 1 } }
        val owned = cmux.state.value.providers.single { it.session == "cmux-android" }
        val phone = owned.state.value.tree!!.workspaces.single(); val tab = phone.tabs.single()
        val desktopProvider = cmux.state.value.providers.single { it.session == "fixture" }
        assertEquals(original, desktopProvider.state.value.tree!!.registry)
        assertEquals(desktop.key, desktopProvider.state.value.tree!!.workspaces.single().key)
        val tag = "ssh.cmux.terminal.${phone.key}.${tab.surface}"
        ready(tag); compose.onNodeWithTag(tag).performScrollTo().performClick()
        send("created from Android")
        compose.onNodeWithText("Back").performClick()
        ready(tag); compose.onNodeWithTag(tag).performScrollTo().performClick()
        waitText("created from Android")
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithTag("ssh.cmux.end.${phone.key}").performScrollTo().performClick()
        compose.onNodeWithText("Close Workspace").performClick()
        compose.waitUntil(10000) { owned.state.value.tree!!.workspaces.isEmpty() }
        assertEquals(desktop.key, desktopProvider.state.value.tree!!.workspaces.single().key)
        capture("cmux-ssh-created-owner")
    }
    @Test fun newScreenTabAndBothSplitsSelectTheirCreatedTerminal() {
        show(); ready("ssh.cmux.create.fixture")
        val original = workspace(); val key = original.key; val screen = original.screens.single(); val pane = screen.panes.single()
        fun create(tag: String, text: String) {
            ready(tag); compose.onNodeWithTag(tag).performScrollTo().performClick()
            send(text) // No second tap: the created terminal must be selected.
            compose.onNodeWithText("Back").performClick()
        }
        create("ssh.cmux.new-terminal.$key", "new screen λ 中")
        assertEquals(2, workspace().screens.size)
        create("ssh.cmux.new-tab.$key.${pane.id}", "new tab λ 中")
        assertEquals(2, workspace().screens.first { it.id == screen.id }.panes.single().tabs.size)
        create("ssh.cmux.split-right.$key.${pane.id}", "right split λ 中")
        assertEquals(2, workspace().screens.first { it.id == screen.id }.panes.size)
        create("ssh.cmux.split-down.$key.${pane.id}", "down split λ 中")
        val updated = workspace().screens.first { it.id == screen.id }
        assertEquals(3, updated.panes.size)
        fun directions(layout: SshCmuxLayout?): Set<Boolean> = when (layout) {
            is SshCmuxLayout.Split -> directions(layout.a) + directions(layout.b) + layout.right
            else -> emptySet()
        }
        assertEquals(setOf(true, false), directions(updated.layout))
        assertEquals(5, workspace().tabs.size)
        assertTrue(workspace().tabs.any { it.terminal == original.tabs.single().terminal })
        capture("cmux-ssh-layout-actions")
        compose.onNodeWithTag("ssh.cmux.end.$key").performScrollTo().performClick()
        compose.onNodeWithText("Close Workspace").performClick()
        compose.waitUntil(15000) { provider().state.value.tree!!.workspaces.none { it.key == key } }
    }
    @Test fun groupedCmuxPickerCreatesSelectsAndReturnsToExactTerminal() {
        show(); openCmux(); send("original picker terminal")
        val original = workspace(); val screen = original.screens.single()
        val originalTarget = SshWorkspaceTarget.Cmux(SshCmuxSelection.capture(provider().session, provider().state.value.tree!!, original, original.tabs.single()))
        fun action(tag: String, marker: String) {
            ready("ssh.shell.menu"); compose.onNodeWithTag("ssh.shell.menu").performClick()
            selectNewTerminal { compose.onNodeWithTag(tag).performScrollTo().performClick() }
            send(marker)
        }
        action("ssh.picker.action.${screen.id}.NEW_TAB", "picker new tab")
        assertEquals(2, workspace().tabs.size)
        action("ssh.picker.action.${screen.id}.SPLIT_RIGHT", "picker right split")
        action("ssh.picker.action.${screen.id}.SPLIT_DOWN", "picker down split")
        assertEquals(3, workspace().screens.single().panes.size)
        compose.onNodeWithTag("ssh.shell.menu").performClick()
        compose.onAllNodesWithText("Pane 1").onFirst().assertExists()
        capture("ssh-cmux-grouped-picker")
        compose.onNodeWithTag("ssh.picker.row.${originalTarget.encode()}").performScrollTo().performClick()
        waitText("original picker terminal")
        compose.onNodeWithTag("ssh.shell.menu").performClick()
        compose.onNodeWithTag("ssh.picker.row.${originalTarget.encode()}").assertIsSelected()
        selectNewTerminal { compose.onNodeWithText("New Screen").performScrollTo().performClick() }
        send("picker new screen")
        assertEquals(2, workspace().screens.size)
        compose.onNodeWithTag("ssh.shell.menu").performClick()
        selectNewTerminal { compose.onNodeWithText("New Workspace").performScrollTo().performClick() }
        send("picker new workspace")
        assertEquals(2, provider().state.value.tree!!.workspaces.size)
    }
    @Test fun groupedTmuxPickerSelectsReturnedPaneForWindowSplitAndWorkspace() {
        show(); ready("ssh.cmux.create.fixture")
        val tmux = runBlocking { session.tmux.open(hostId) }
        compose.waitUntil(10000) { !tmux.state.value.loading }
        val original = tmux.state.value.workspaces.single(); val pane = original.panes.first()
        compose.onNodeWithTag("ssh.tmux.pane.${original.id}.${pane.id}").performScrollTo().performClick()
        send("original tmux picker")
        compose.onNodeWithTag("ssh.shell.menu").performClick()
        selectNewTerminal { compose.onNodeWithText("New Window").performScrollTo().performClick() }
        send("picker created window")
        val created = tmux.state.value.workspaces.single().panes.single { next -> original.panes.none { it.id == next.id } }
        for (action in listOf(SshPaneAction.SPLIT_RIGHT, SshPaneAction.SPLIT_DOWN)) {
            compose.onNodeWithTag("ssh.shell.menu").performClick()
            selectNewTerminal { compose.onNodeWithTag("ssh.picker.action.${created.window}.${action.name}").performScrollTo().performClick() }
            send("picker tmux ${action.name}")
        }
        assertEquals(3, tmux.state.value.workspaces.single().panes.count { it.window == created.window })
        compose.onNodeWithTag("ssh.shell.menu").performClick()
        capture("ssh-tmux-grouped-picker")
        val first = SshWorkspaceTarget.Tmux(original.id, pane.window, pane.id)
        compose.onNodeWithTag("ssh.picker.row.${first.encode()}").performScrollTo().performClick()
        waitText("original tmux picker")
        compose.onNodeWithTag("ssh.shell.menu").performClick()
        compose.onNodeWithTag("ssh.picker.row.${first.encode()}").assertIsSelected()
        selectNewTerminal { compose.onNodeWithText("New Workspace").performScrollTo().performClick() }
        send("picker created tmux workspace")
        assertEquals(2, tmux.state.value.workspaces.size)
    }
    @Test fun ownerRestartRestoresSameTerminalWhileListingNeverRestartsDesktopOrPhone() {
        show(); ready("ssh.cmux.create-owned")
        compose.onNodeWithTag("ssh.cmux.create-owned").performScrollTo().performClick()
        compose.waitUntil(15000) { cmux.state.value.providers.any { it.session == "cmux-android" && it.state.value.tree?.workspaces?.size == 1 } }
        val before = cmux.state.value.providers.single { it.session == "cmux-android" }
        val tree = before.state.value.tree!!; val workspace = tree.workspaces.single(); val tab = workspace.tabs.single()
        val tag = "ssh.cmux.terminal.${workspace.key}.${tab.surface}"
        ready(tag); compose.onNodeWithTag(tag).performScrollTo().performClick(); send("before owner stop λ 中")
        fun status() = runBlocking { org.json.JSONObject(cmux.connection.exec("fixture-owner-status").stdout.toString(Charsets.UTF_8)) }
        val ensures = status().getInt("phoneEnsures")
        assertEquals(0, runBlocking { cmux.connection.exec("fixture-stop-phone-owner").exitStatus })
        ready("ssh.shell.reconnect")
        assertTrue(cmux.connection.isConnected); assertFalse(status().getBoolean("phone"))
        // A separate list consumer must not restart the stopped owner. The
        // visible route still retains its ended provider until user Reconnect.
        val observer = compose.runOnIdle { SshCmuxHost(hostId, cmux.connection, lifetime, { lifetime.isActive }) }
        try {
            compose.waitUntil(15000) { !observer.state.value.loading }
            assertTrue(observer.state.value.providers.none { it.session == "cmux-android" })
            assertFalse(status().getBoolean("phone")); assertEquals(ensures, status().getInt("phoneEnsures"))
        } finally { compose.runOnIdle { observer.close() } }
        compose.onNodeWithTag("ssh.shell.reconnect").performClick()
        ready(); waitText("before owner stop λ 中"); send("after owner restart λ 中")
        // Read the current registry only after UI-driven recovery and input
        // succeed. A retired SSH host is not the route's current provider.
        cmux = runBlocking { session.cmux.open(hostId) }
        val after = cmux.state.value.providers.single { it.session == "cmux-android" }
        assertNotSame(before, after)
        assertNotEquals(tree.generation, after.state.value.tree!!.generation)
        assertEquals(tree.registry, after.state.value.tree!!.registry)
        assertEquals(tab.terminal, after.state.value.tree!!.tabs.single().terminal)
        assertEquals(ensures + 1, status().getInt("phoneEnsures"))
        capture("cmux-ssh-owner-restarted")
        compose.onNodeWithText("Back").performClick()
        openCmux(); waitText("Remote cmux λ 中")
        assertEquals(0, runBlocking { cmux.connection.exec("fixture-stop-desktop-owner").exitStatus })
        ready("ssh.shell.reconnect"); compose.onNodeWithTag("ssh.shell.reconnect").performClick()
        val error = "This desktop cmux-tui session is not running. Start it on the computer, then reconnect."
        compose.waitUntil(15000) { compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(status().getBoolean("desktop")); assertTrue(cmux.connection.isConnected)
        compose.onNodeWithTag("ssh.shell.composer").assertIsNotEnabled()
    }
    @Test fun droppedConnectionReattachesButExplicitDisconnectWaitsForUser() {
        show(); openCmux(); send("before connection loss")
        val old = cmux.connection
        compose.runOnIdle { old.close() }
        compose.waitUntil(10000) { !old.isConnected }
        ready(); waitText("before connection loss"); send("after connection loss")
        assertTrue(session.connections.statuses.value[hostId]?.phase == SshConnectionPhase.CONNECTED)
        compose.runOnIdle { session.connections.disconnect(hostId) }
        ready("ssh.shell.reconnect")
        Thread.sleep(600)
        assertEquals(SshConnectionPhase.IDLE, session.connections.statuses.value[hostId]?.phase)
        compose.onNodeWithTag("ssh.shell.composer").assertIsNotEnabled()
        assertTrue(session.hosts.state.value.host(hostId)!!.autoConnectPaused)
        compose.onNodeWithTag("ssh.shell.reconnect").performClick()
        ready(); waitText("after connection loss"); send("explicit reconnect")
        assertFalse(session.hosts.state.value.host(hostId)!!.autoConnectPaused)
    }
    @Test fun savedCmuxSelectionRestoresAcrossRuntimeAndPreservesDisconnect() {
        val restore = StateRestorationTester(compose)
        restore.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SshWorkspacesRoute(session, hostId) {}
        } } }
        openCmux(); send("saved cmux runtime")
        fun replaceRuntime() = compose.runOnIdle {
            session.close(); lifetime.cancel()
            val hosts = SshHostStore({ metadata }, { metadata = it })
            val vault = SshKeyVault(root, { true }, hosts::removeKeyReferences)
            lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            session = NativeSshSession(hosts, vault, lifetime) { lifetime.isActive }
        }
        replaceRuntime(); restore.emulateSavedInstanceStateRestore()
        ready(); waitText("saved cmux runtime"); send("restored cmux runtime")
        compose.runOnIdle { session.connections.disconnect(hostId) }
        ready("ssh.shell.reconnect")
        replaceRuntime(); restore.emulateSavedInstanceStateRestore()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Reconnect").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(session.hosts.state.value.host(hostId)!!.autoConnectPaused)
        assertTrue(session.connections.statuses.value.isEmpty())
        compose.onNodeWithText("Reconnect").performClick()
        ready(); waitText("restored cmux runtime")
        assertFalse(session.hosts.state.value.host(hostId)!!.autoConnectPaused)
        capture("cmux-ssh-restored")
    }
}
