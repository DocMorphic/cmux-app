package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeLegacyTailscalePolicyTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val grant = TailscaleSavedGrant("grant", "user", "team", "source", "mac", "default", PairingCode.Route("100.99.1.2", 58465))
    private val grants = TailscaleGrantStore({ JSONObject() }, { error("No writes") })
    private val native = NativeCredentialStore.PairedMac("cmux-ios://attach?v=3&i=${"ab".repeat(32)}&d=mac", "mac", "Mac", "default",
        accountUserId = "user", accountTeamId = "team")

    @Test fun directNeverFallsBackAndAutomaticOnlyKeepsPreNativeLegacyRoute() {
        for (known in listOf(false, true)) {
            assertFalse(NativeLegacyTailscalePolicy.permits(NativeMacConnectionMethod.DIRECT, known))
            assertTrue(NativeLegacyTailscalePolicy.permits(NativeMacConnectionMethod.TAILSCALE, known))
        }
        assertTrue(NativeLegacyTailscalePolicy.permits(NativeMacConnectionMethod.IROH, false))
        assertFalse(NativeLegacyTailscalePolicy.permits(NativeMacConnectionMethod.IROH, true))
    }
    @Test fun nativeIdentityRequiresExactOwnerDeviceAndBuild() {
        assertTrue(NativeLegacyTailscalePolicy.hasNativeIdentity(grant, team, listOf(native), grants))
        for (other in listOf(native.copy(instanceTag = "nightly"), native.copy(deviceId = "other"),
            native.copy(accountUserId = "other"), native.copy(accountTeamId = "other"),
            native.copy(accountUserId = null, accountTeamId = null), native.copy(code = "cmux-ios://attach?v=2&r=100.99.1.2:58465"))) {
            assertFalse(NativeLegacyTailscalePolicy.hasNativeIdentity(grant, team, listOf(other), grants))
        }
        assertFalse(NativeLegacyTailscalePolicy.hasNativeIdentity(grant.copy(build = null), team, listOf(native), grants))
    }
}
