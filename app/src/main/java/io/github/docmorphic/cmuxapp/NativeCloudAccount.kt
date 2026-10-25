package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Build
import io.github.docmorphic.cmuxapp.iroh.IrohInstallationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The controller must close this client on account/team retirement; creation itself performs no I/O. */
internal fun nativeCloudApi(account:NativeAccount,teams:NativeAccountTeams,owner:CloudAccountScope,
    requests:CloudApiRequests=CloudApiRequests()):CloudApi = CloudApi(owner,credentials={
    withContext(Dispatchers.IO) {
        if(!account.isCloudScopeCurrent(teams,owner))throw CancellationException("Cloud account changed")
        val context=try{account.cloudTokenContext(teams,owner)}catch(failure:NativeSignInFailure){
            if(failure.reason!=NativeSignInError.UNAUTHORIZED)throw failure
            null
        }
        if(!account.isCloudScopeCurrent(teams,owner))throw CancellationException("Cloud account changed")
        context?.let{CloudCredentials(owner,it.pair.access,it.pair.refresh)}
    }
},isCurrent={it==owner && account.isCloudScopeCurrent(teams,owner)},requests=requests)

internal class CloudTunnelMaterial(val identity: CloudTunnelIdentity, val configuration: CloudWireGuardConfig) {
    override fun toString() = "CloudTunnelMaterial(redacted)"
}

/** Explicit startup step for an admitted tunnel owner; never invoked by merely constructing the Cloud UI. */
internal suspend fun prepareNativeCloudTunnel(context: Context, api: CloudApi,
    owner: CloudAccountScope, isCurrent:()->Boolean): CloudTunnelMaterial {
    fun admitted() { if (!isCurrent()) throw CancellationException("Cloud account changed") }
    admitted()
    val (deviceId, identity) = withContext(Dispatchers.IO) {
        admitted()
        val application = context.applicationContext
        // This cdylib has separate DNS/TLS globals from the existing Iroh FFI library.
        CloudNativeBindings.initialize(application)
        val registryId = IrohInstallationStore(application).loadOrCreateDeviceId()
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
