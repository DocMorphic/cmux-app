package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class SshWorkspaceCreationTest {
    @Test fun unprobedHostsOfferEveryKindInIosOrder() {
        val options = sshWorkspaceKinds(null, null)
        assertEquals(listOf(SshWorkspaceKind.CMUX_TUI, SshWorkspaceKind.TMUX, SshWorkspaceKind.SHELL), options.map { it.kind })
        assertTrue(options.all { it.unavailableReason == null && !it.needsInstall })
    }
    @Test fun probingOneProviderDoesNotHideShellOrInventAvailabilityOfAnother() {
        val options = sshWorkspaceKinds(SshTmuxHostState(loading = false), null)
        assertNotNull(options[1].unavailableReason)
        assertNull(options[0].unavailableReason); assertNull(options[2].unavailableReason)
    }
    @Test fun supportedPlatformOffersInstallAndInstalledBinaryOverridesPlatformRestriction() {
        val platform = SshCmuxPlatform("Linux", "aarch64")
        assertTrue(sshWorkspaceKinds(null, SshCmuxHostState(loading = false, platform = platform))[0].needsInstall)
        val unsupported = platform.copy(os = "Unknown")
        assertNotNull(sshWorkspaceKinds(null, SshCmuxHostState(loading = false, platform = unsupported))[0].unavailableReason)
        assertNull(sshWorkspaceKinds(null, SshCmuxHostState(loading = false, available = true, platform = unsupported))[0].unavailableReason)
    }
    @Test fun failedProbeDoesNotPromiseInstallationOnAnUnknownPlatform() {
        val options = sshWorkspaceKinds(SshTmuxHostState(loading = false, error = "Probe failed"), SshCmuxHostState(loading = false))
        assertEquals("Probe failed", options[1].unavailableReason)
        assertNotNull(options[0].unavailableReason); assertFalse(options[0].needsInstall)
    }
}
