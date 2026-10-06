package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Build
import io.github.docmorphic.cmuxapp.iroh.IrohInstallationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The controller must close this client on account/team retirement; creation itself performs no I/O. */
internal fun nativeCloudApi(account: NativeAccount, teams: NativeAccountTeams, scope: NativeTeamScope): CloudApi {
    val owner = CloudAccountScope(scope.login, scope.userId, scope.teamId, scope.generation)
    return CloudApi(owner, credentials = {
        withContext(Dispatchers.IO) {
            if (!teams.isCurrent(scope)) throw CancellationException("Cloud account changed")
            // Reuse the account's coherent snapshot across refresh. Never combine an
            // access token from one login with a refresh token from its replacement.
            val snapshot = account.webSessionSnapshot(scope.login)
            if (!teams.isCurrent(scope)) throw CancellationException("Cloud account changed")
            snapshot?.let { CloudCredentials(owner, it.accessToken, it.refreshToken) }
        }
    }, isCurrent = { it == owner && teams.isCurrent(scope) })
}

internal class CloudTunnelMaterial(val identity: CloudTunnelIdentity, val configuration: CloudWireGuardConfig) {
    override fun toString() = "CloudTunnelMaterial(redacted)"
}

/** Explicit startup step for an admitted tunnel owner; never invoked by merely constructing the Cloud UI. */
internal suspend fun prepareNativeCloudTunnel(context: Context, api: CloudApi,
    teams: NativeAccountTeams, owner: NativeTeamScope): CloudTunnelMaterial {
    fun admitted() { if (!teams.isCurrent(owner)) throw CancellationException("Cloud account changed") }
    admitted()
    val (deviceId, identity) = withContext(Dispatchers.IO) {
        admitted()
        val application = context.applicationContext
        val registryId = IrohInstallationStore(application).storedDeviceId()
            ?: error("The cmux device identity is not ready yet")
        val identity = nativeCloudIdentityStore(application).resolve()
        admitted()
        registryId to identity
    }
    admitted()
    val enrollment = api.enroll(identity.terminalKey.publicKey, deviceId, identity.fingerprint,
        CloudTunnelPurpose.TERMINAL, TerminalDeviceIdentity(Build.MODEL).name)
    admitted()
    check(enrollment.fingerprint == identity.fingerprint) { "Cloud enrollment returned a different device identity" }
    return CloudTunnelMaterial(identity, CloudWireGuardConfig.make(enrollment, identity.terminalKey))
}
