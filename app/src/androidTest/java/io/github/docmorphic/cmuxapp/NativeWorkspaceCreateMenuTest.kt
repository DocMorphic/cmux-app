package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class NativeWorkspaceCreateMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val stable = NativeCredentialStore.PairedMac("route-stable", "mac", "Stable Mac", "default")
    private val nightly = stable.copy(code = "route-nightly", name = "Nightly Mac", instanceTag = "nightly")
    private var rows by mutableStateOf(listOf(stable, nightly))
    private var selection by mutableStateOf<String?>(null)
    private var owner by mutableStateOf(NativeComputerMenuOwner("login", null))
    private var open by mutableStateOf(false)
    private var connected by mutableStateOf(setOf(stable.origin, nightly.origin))
    private var busy by mutableStateOf(false)
    private var allowed = true
    private val created = mutableListOf<NativeCredentialStore.PairedMac>()

    private var sshTargets by mutableStateOf(emptyList<NativeSshCreateTarget>())
    private val sshCreated = mutableListOf<Pair<UUID, SshWorkspaceKind>>()
    private var sshAllowed = true
    private var sshScope: CoroutineScope? = null
    private var sshSession: NativeSshSession? = null
    private var sshRoot: File? = null
    private fun addSsh(count: Int) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { sshScope = it }
        var saved: String? = null
        val hosts = SshHostStore({ saved }, { saved = it })
        val root = File(compose.activity.noBackupFilesDir, "menu-test-${UUID.randomUUID()}").also { sshRoot = it }
        val session = NativeSshSession(hosts, SshKeyVault(root, { true }, hosts::removeKeyReferences), scope) { scope.isActive }.also { sshSession = it }
        sshTargets = (1..count).map {
            val host = SshHostRecord(name = "SSH $it", endpoint = SshEndpoint("host$it.test", 22, "user"))
            hosts.upsert(host)
            NativeSshCreateTarget(session, host, null, sshWorkspaceKinds(null, null))
        }
    }
    @After fun cleanupSsh() {
        compose.runOnIdle { sshSession?.close(); sshScope?.cancel() }
        sshRoot?.deleteRecursively()
    }
    private fun content() {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.statusBarsPadding()) {
            NativeWorkspaceCreateMenu(rows, NativeMacAppearances(), NativeMacPresenceState(),
                rows.associate { NativeMacIdentity(it.deviceId, it.instanceTag) to NativeComputerConnection(
                    if (it.origin in connected) NativeFeedAvailability.CONNECTED else NativeFeedAvailability.OFFLINE) },
                open, { open = it }, owner, selection, busy, { it == owner && allowed },
                { NativeComputerMenuPairing.isCurrent(it, rows) && it.origin in connected },
                { created += it }, null, sshTargets = sshTargets,
                canCreateSsh = { target, _ -> sshAllowed && sshTargets.any { it.session === target.session && it.host.connectsLike(target.host) } },
                onCreateSsh = { target, kind -> sshCreated += target.host.id to kind })
        } } } }
    }
    private fun openMenu() = compose.onNodeWithContentDescription("New Workspace").performClick()

    @Test fun mixedChooserKeepsIdenticalKindsBoundToTheirDisplayedSshHost() {
        addSsh(2); rows = listOf(stable); content()
        val first = sshTargets[0].host.id; val second = sshTargets[1].host.id
        openMenu(); compose.onNodeWithTag("workspace.create.ssh:$first").performClick()
        compose.onNodeWithTag("ssh.workspace.create.TMUX").performClick()
        openMenu(); compose.onNodeWithTag("workspace.create.ssh:$second").performClick()
        compose.onNodeWithTag("ssh.workspace.create.TMUX").performClick()
        compose.runOnIdle { assertEquals(listOf(first to SshWorkspaceKind.TMUX, second to SshWorkspaceKind.TMUX), sshCreated); assertTrue(created.isEmpty()) }
    }

    @Test fun singleSshOpensKindsAndEditedRouteOrRevokedAuthorityCannotDispatch() {
        addSsh(1); rows = emptyList(); content(); openMenu()
        compose.onNodeWithTag("ssh.workspace.create.SHELL").assertIsDisplayed()
        compose.runOnIdle { sshTargets = sshTargets.map { it.copy(host = it.host.copy(endpoint = it.host.endpoint.copy(port = 2222))) } }
        compose.onNodeWithTag("ssh.workspace.create.SHELL").assertIsNotEnabled()
        compose.runOnIdle { open = false }
        openMenu()
        compose.runOnIdle { sshAllowed = false }
        compose.onNodeWithTag("ssh.workspace.create.SHELL").performClick()
        compose.runOnIdle { assertTrue(sshCreated.isEmpty()) }
    }

    @Test fun singleMacTapCreatesDirectlyAndHoldOnlyOpensOptions() {
        rows = listOf(stable)
        content(); openMenu()
        compose.runOnIdle { assertEquals(listOf(stable), created); created.clear() }
        compose.onNodeWithContentDescription("New Workspace").performTouchInput { longClick() }
        compose.onNode(hasText("New Workspace") and hasAnyAncestor(isPopup())).assertIsDisplayed()
        compose.onNodeWithText("New task").assertDoesNotExist()
        compose.runOnIdle { assertTrue(created.isEmpty()) }
    }

    @Test fun sameDeviceDifferentBuildUsesDisplayedTargetAndNextOpeningUpdatesItsName() {
        content(); openMenu()
        compose.onAllNodesWithContentDescription("Computer status: Connected").assertCountEquals(2)
        compose.runOnIdle { rows = listOf(nightly.copy(name = "Renamed nightly"), stable) }
        compose.onNodeWithText("Nightly Mac").performClick()
        compose.runOnIdle { assertEquals(listOf(nightly), created) }
        openMenu(); compose.onNodeWithText("Renamed nightly").assertIsDisplayed()
    }

    @Test fun replacementDisconnectionAndAuthorityRevocationCannotSendAnOldMenuAction() {
        content(); openMenu()
        compose.runOnIdle { rows = listOf(stable, nightly.copy(code = "replacement")) }
        compose.onNodeWithText("Nightly Mac").assertIsNotEnabled()
        compose.runOnIdle { connected = emptySet() }
        compose.onNodeWithText("Stable Mac").assertIsNotEnabled()
        compose.runOnIdle { connected = setOf(stable.origin); allowed = false }
        compose.onNodeWithText("Stable Mac").performClick()
        compose.runOnIdle { assertTrue(created.isEmpty()) }
    }

    @Test fun accountAndFilterChangesDismissTargetsAndSingleSelectionCannotChooseOtherMac() {
        content(); openMenu()
        compose.runOnIdle { owner = owner.copy(login = "new-login") }
        compose.waitUntil { !open }
        openMenu()
        compose.runOnIdle { selection = nightly.origin; rows = listOf(nightly) }
        compose.waitUntil { !open }
        compose.onNodeWithContentDescription("New Workspace").performTouchInput { longClick() }
        compose.onNodeWithText("Stable Mac").assertDoesNotExist()
        compose.onNode(hasText("New Workspace") and hasAnyAncestor(isPopup())).performClick()
        compose.runOnIdle { assertEquals(listOf(nightly), created); busy = true }
        compose.onNodeWithContentDescription("New Workspace").assertIsNotEnabled()
    }
}
