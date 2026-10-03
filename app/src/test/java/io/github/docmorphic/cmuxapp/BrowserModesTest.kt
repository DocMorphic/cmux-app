package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BrowserModesTest {
    private val key = LocalBrowserKey("account", "team", "computer", "workspace")
    private val workspace = NativeWorkspace(key.workspaceId, "Workspace", listOf(NativeTerminal("t", "Terminal")),
        null, false, null, null, false, listOf(NativeBrowser("a", "A"), NativeBrowser("b", "B")), null, null, null)
    @Test fun phonePageWinsOverRemoteUrlUntilExplicitStreamSwitch() = runTest {
        val navigation = LocalBrowserNavigation(backgroundScope)
        navigation.openOnDevice(key, workspace, "b", "http://localhost:3000/first")
        val page = navigation.state.value.local!!.surface
        assertEquals("b", page.linkedStreamPanelId)
        assertEquals("http://localhost:3000/first", page.takeWork().url)
        val token = page.attach(); page.location(token, "http://localhost:3000/phone", "Phone", false, false); page.detach(token)
        navigation.leave(close = true)
        assertFalse(page.state.value.closed); assertTrue(navigation.prefersOnDevice(key, "b"))
        navigation.openOnDevice(key, workspace, "b", "http://localhost:3000/changed-on-server")
        assertSame(page, navigation.state.value.local!!.surface)
        assertEquals("http://localhost:3000/phone", page.state.value.url)
        assertFalse(navigation.switchToStream(key.copy(computerId = "other"), workspace, "b"))
        assertFalse(navigation.switchToStream(key, workspace, "missing"))
        assertTrue(navigation.switchToStream(key, workspace, "b"))
        assertFalse(navigation.prefersOnDevice(key, "b")); assertTrue(page.state.value.closed)
        navigation.openOnDevice(key, workspace, "b", "http://localhost:3000/new")
        assertNotSame(page, navigation.state.value.local!!.surface)
        assertEquals("http://localhost:3000/new", navigation.state.value.local!!.surface.takeWork().url)
    }
    @Test fun panelPreferencesAreScopedAndInactivePagesRetireWithOwner() {
        val store = LocalBrowserStore()
        val a = store.openOnDevice(key, "same-panel", "https://one.example/")
        val other = key.copy(computerId = "other")
        val b = store.openOnDevice(other, "same-panel", "https://two.example/")
        store.close(key); store.close(other)
        assertNotSame(a, b); assertFalse(a.state.value.closed); assertFalse(b.state.value.closed)
        store.retainComputers(setOf(key.computerId))
        assertTrue(b.state.value.closed); assertFalse(a.state.value.closed)
        assertFalse(store.prefersOnDevice(other, "same-panel"))
        store.retainAccount("new-account", "team")
        assertTrue(a.state.value.closed); assertFalse(store.prefersOnDevice(key, "same-panel"))
    }
    @Test fun removedPanelsRetireActiveAndInactivePagesWithoutReplacingOtherTabs() = runTest {
        val navigation = LocalBrowserNavigation(backgroundScope)
        navigation.openOnDevice(key, workspace, "a", "https://a.example/"); val a = navigation.state.value.local!!.surface
        navigation.openOnDevice(key, workspace, "b", "https://b.example/"); val b = navigation.state.value.local!!.surface
        navigation.retainPanels(key, workspace.copy(browsers = listOf(NativeBrowser("b", "B"))))
        assertTrue(a.state.value.closed); assertFalse(b.state.value.closed)
        navigation.retainPanels(key, workspace.copy(browsers = emptyList()))
        assertTrue(b.state.value.closed); assertNull(navigation.state.value.local)
        assertFalse(navigation.prefersOnDevice(key, "b"))
    }
    @Test fun nonWebSeedsUseDefaultAndClearingRetiresEveryRememberedPage() {
        val store = LocalBrowserStore(defaultUrl = "https://default.example/")
        val pages = listOf("javascript:alert(1)", "file:///secret", "about:blank", "not a url").mapIndexed { i, url ->
            store.openOnDevice(key, "panel-$i", url).also { assertEquals("https://default.example/", it.takeWork().url) }
        }
        store.clear(); assertTrue(pages.all { it.state.value.closed })
        assertFalse(store.prefersOnDevice(key, "panel-0"))
    }
    @Test fun durableSshPanelIdSurvivesOwnerRestartButNotContentOrWorkspaceReplacement() {
        val ref = SshCmuxBrowserSelection("owner", "registry", "boot-a", 1, "workspace-key", "ws_a", 7, "tab_a", "brw_a")
        assertEquals(ref.panelId, ref.copy(generation = "boot-b", surface = 100).panelId)
        assertNotEquals(ref.panelId, ref.copy(contentResource = "brw_b").panelId)
        assertNotEquals(ref.panelId, ref.copy(workspaceKey = "workspace-b").panelId)
        val legacy = ref.copy(contentResource = null, workspaceKey = null, workspaceResource = null)
        assertNotEquals(legacy.panelId, legacy.copy(generation = "boot-b").panelId)
        assertEquals(emptyList<NativeTerminal>(), sshBrowserWorkspace(SshWorkspaceTarget.Browser(ref), "Browser").terminals)
        assertEquals(ref.panelId, sshBrowserWorkspace(SshWorkspaceTarget.Browser(ref), "Browser").browsers.single().id)
    }
    @Test fun macInventoryRetiresLinkedTabsOnlyWhenAuthoritative() = runTest {
        val mac = NativeCredentialStore.PairedMac("fixture-code", "mac", "Mac", accountUserId = "account", accountTeamId = "team")
        val scoped = key.copy(computerId = mac.origin)
        val navigation = LocalBrowserNavigation(backgroundScope)
        navigation.openOnDevice(scoped, workspace, "a", "https://a.example/")
        val a = navigation.state.value.local!!.surface
        navigation.openOnDevice(scoped, workspace, "b", "https://b.example/")
        val b = navigation.state.value.local!!.surface
        val source = NativeFeedSource(mac, availability = NativeFeedAvailability.OFFLINE,
            hasWorkspaceSnapshot = true, workspaces = listOf(workspace.copy(browsers = emptyList())))
        navigation.observeWorkspaces(source)
        navigation.observeWorkspaces(source.copy(availability = NativeFeedAvailability.CONNECTED, hasWorkspaceSnapshot = false))
        assertFalse(a.state.value.closed); assertFalse(b.state.value.closed)
        navigation.observeWorkspaces(source.copy(availability = NativeFeedAvailability.CONNECTED))
        assertTrue(a.state.value.closed); assertTrue(b.state.value.closed)
        assertNull(navigation.state.value.local)
        assertFalse(navigation.prefersOnDevice(scoped, "a")); assertFalse(navigation.prefersOnDevice(scoped, "b"))
    }

}
