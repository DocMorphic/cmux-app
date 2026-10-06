package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Build
import io.github.docmorphic.cmuxapp.iroh.IrohInstallationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Capture a coherent token pair while the owner is admitted. Only the revocation
 * closure can use that old pair after retirement; enrollment always rechecks current
 * account state through nativeCloudApi. Neither construction nor binding enrolls. */
internal suspend fun nativeCloudVpnAccess(context: Context, account: NativeAccount,
    teams: NativeAccountTeams, scope: NativeTeamScope): CloudVpnAccess = withContext(Dispatchers.IO) {
    fun admitted() { if (!teams.isCurrent(scope)) throw CancellationException("Cloud VPN account changed") }
    admitted()
    val snapshot = account.webSessionSnapshot(scope.login) ?: throw CloudNotSignedIn()
    admitted()
    val application = context.applicationContext
    val apiOwner = CloudAccountScope(scope.login, scope.userId, scope.teamId, scope.generation)
    val cleanupCredentials = CloudCredentials(apiOwner, snapshot.accessToken, snapshot.refreshToken)
    CloudVpnAccess(CloudVpnOwner(scope.userId, scope.teamId), { teams.isCurrent(scope) }, device = {
        withContext(Dispatchers.IO) {
            admitted()
            val identity = nativeCloudIdentityStore(application).resolve()
            val deviceId = IrohInstallationStore(application).loadOrCreateDeviceId()
            admitted()
            CloudVpnDevice(deviceId, identity.fingerprint)
        }
    }, enroll = { key, device ->
        admitted()
        nativeCloudApi(account, teams, scope).use { api ->
            api.enroll(key.publicKey, device.deviceId, device.fingerprint, CloudTunnelPurpose.BROWSER,
                TerminalDeviceIdentity(Build.MODEL).name)
        }
    }, revoke = { fingerprint ->
        // Restrict this retained capability to DELETE browser peer for the captured
        // user/team. A replacement account's credentials are never substituted.
        CloudApi(apiOwner, { cleanupCredentials }, { it == apiOwner }).use { api ->
            api.revoke(fingerprint, CloudTunnelPurpose.BROWSER)
        }
    })
}
