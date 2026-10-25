package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Build
import io.github.docmorphic.cmuxapp.iroh.IrohInstallationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Capture live credentials on explicit device/enrollment work, never on binding. Only the revocation
 * closure can use that old pair after retirement; enrollment always rechecks current
 * account state through nativeCloudApi. Neither construction nor binding enrolls. */
internal suspend fun nativeCloudVpnAccess(context: Context, account: NativeAccount,
    teams: NativeAccountTeams, scope: CloudAccountScope,requests:CloudApiRequests=CloudApiRequests(),
    deviceOverride:(suspend()->CloudVpnDevice)?=null): CloudVpnAccess = withContext(Dispatchers.IO) {
    fun admitted() { if (!account.isCloudScopeCurrent(teams,scope)) throw CancellationException("Cloud VPN account changed") }
    admitted()
    val application = context.applicationContext
    val apiOwner = scope
    val cleanupCredentials = java.util.concurrent.atomic.AtomicReference<CloudCredentials?>(null)
    suspend fun capture():CloudCredentials?=try {
        account.cloudTokenContext(teams,scope).let{CloudCredentials(scope,it.pair.access,it.pair.refresh)}
    }catch(_:Exception){currentCoroutineContext().ensureActive();null}
    CloudVpnAccess(CloudVpnOwner(scope.user, scope.team?.trim()?.takeIf(String::isNotEmpty)), { account.isCloudScopeCurrent(teams,scope) }, device = {
        withContext(Dispatchers.IO) {
            admitted()
            // Original VPN composition maps every credential failure to nil before resolving identity.
            cleanupCredentials.set(capture());admitted()
            val device=deviceOverride?.invoke() ?: run {
                val identity=nativeCloudIdentityStore(application).resolve()
                CloudVpnDevice(IrohInstallationStore(application).loadOrCreateDeviceId(),identity.fingerprint)
            }
            admitted()
            device
        }
    }, enroll = { key, device ->
        admitted()
        CloudApi(apiOwner,{cleanupCredentials.get()},{it==apiOwner && account.isCloudScopeCurrent(teams,scope)},requests).use { api ->
            api.enroll(key.publicKey, device.deviceId, device.fingerprint, CloudTunnelPurpose.BROWSER,
                TerminalDeviceIdentity(Build.MODEL).name)
        }
    }, revoke = { fingerprint ->
        // Long-lived VPNs may outlive an access token. Refresh only while this exact
        // owner is still admitted, then retain the coherent pair for later retirement.
        if (account.isCloudScopeCurrent(teams,scope)) withContext(Dispatchers.IO) {
            val fresh=capture()
            if(fresh!=null && account.isCloudScopeCurrent(teams,scope))cleanupCredentials.set(fresh)
        }
        // Restrict this retained capability to DELETE browser peer for the captured
        // user/team. A replacement account's credentials are never substituted.
        val captured = cleanupCredentials.get()
        CloudApi(apiOwner, { captured }, { it == apiOwner },requests).use { api ->
            api.revoke(fingerprint, CloudTunnelPurpose.BROWSER)
        }
    }, session = scope.login)
}
