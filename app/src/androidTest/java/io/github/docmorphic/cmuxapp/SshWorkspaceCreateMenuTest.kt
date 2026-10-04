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

class SshWorkspaceCreateMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var host by mutableStateOf(SshHostRecord(name = "First", endpoint = SshEndpoint("first", username = "fixture")))
    private var options by mutableStateOf(sshWorkspaceKinds(null, null))
    private var enabled by mutableStateOf(true)
    private var admitted = true
    private val created = mutableListOf<Pair<SshHostRecord, SshWorkspaceKind>>()
    private fun show() = compose.setContent { CmuxTheme { Surface { Column(Modifier.statusBarsPadding()) {
        SshWorkspaceCreateMenu(host, options, enabled, { admitted && host.connectsLike(it) }, { h, k -> created += h to k })
    } } } }
    private fun open() = compose.onNodeWithContentDescription("New Workspace").performClick()

    @Test fun switchingHostsWithIdenticalOptionsRetiresOldTargetAndReopeningUsesNewHost() {
        show(); open()
        val next = SshHostRecord(name = "Second", endpoint = SshEndpoint("second", username = "fixture"))
        compose.runOnIdle { host = next }
        compose.onNodeWithText("New Shell").assertDoesNotExist()
        open(); compose.onNodeWithText("New Shell").performClick()
        compose.runOnIdle { assertEquals(listOf(next to SshWorkspaceKind.SHELL), created) }
    }
    @Test fun unavailableReasonAndFreshCapabilityAndAccountChecksPreventCreation() {
        options = sshWorkspaceKinds(SshTmuxHostState(loading = false), null)
        show(); open()
        compose.onNodeWithText("tmux is not installed on this computer.").assertIsDisplayed()
        compose.onNodeWithTag("ssh.workspace.create.TMUX").assertIsNotEnabled()
        compose.runOnIdle { options = sshWorkspaceKinds(null, SshCmuxHostState(loading = false)) }
        compose.onNodeWithTag("ssh.workspace.create.CMUX_TUI").assertIsNotEnabled()
        compose.runOnIdle { admitted = false }
        compose.onNodeWithText("New Shell").performClick()
        compose.runOnIdle { assertTrue(created.isEmpty()) }
    }
}
