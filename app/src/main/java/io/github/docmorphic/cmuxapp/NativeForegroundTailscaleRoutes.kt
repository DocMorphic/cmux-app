package io.github.docmorphic.cmuxapp

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject

/** Explicit route management for one already admitted Mac. Never returns a usable connection. */
internal class NativeForegroundTailscaleRoutes(private val access: NativeForegroundComputerDetailAccess,
    private val runtime: NativeIrohRuntime, private val read: () -> JSONObject?,
    private val update: ((JSONObject) -> Unit) -> Unit,
    private val resolve: suspend (PairingCode.Route, () -> Boolean) -> PairingCode.Route,
    private val dial: suspend (PairingCode.Route, () -> Boolean, suspend () -> String?) -> MobileRpcClient,
    private val token: suspend () -> String?, private val forceToken: suspend () -> String?) {
    private fun grants(allowed: () -> Boolean, beforeCommit: () -> Unit = {}) = TailscaleGrantStore(
        read = { check(allowed()) { "Computer route owner changed" }; read() },
        update = { action ->
            check(allowed()) { "Computer route owner changed" }
            update { value ->
                // Production teams and credentials share accountStateLock; no authority registry lock is entered here.
                check(allowed() && nativeSessionOwns(value,access.team.login)) { "Computer route owner changed" }
                action(value)
                check(allowed()) { "Computer route owner changed" }
                beforeCommit()
            }
        })
    fun routes() = grants(access::current).computer(access.team, access.target)
    fun remove(grant: TailscaleSavedGrant) = grants(access::current).removeRoute(access.team, access.target, grant, access::current)

    suspend fun pair(pairing: PairingCode.Tailscale, replacing: TailscaleSavedGrant? = null) = withContext(Dispatchers.IO) {
        check(access.current()) { "Computer route owner changed" }
        replacing?.let { require(it.user == access.team.userId && it.team == access.team.teamId &&
            it.device == canonicalMacDeviceId(access.target.deviceId) && it.build == access.target.buildTag) { "Computer route target changed" } }
        access.management.routeProbe(access::current).use { permit ->
            val operation = checkNotNull(currentCoroutineContext()[Job])
            val store = grants({ operation.isActive && permit.current() }) {
                operation.ensureActive(); permit.beginCommit()
            }
            TailscalePairingAuthority({ access.team }, { it == access.team && permit.current() }, store,
                resolve = { route, allowed -> check(permit.canProbe()); resolve(route, allowed) },
                dial = { route, allowed, bearer ->
                    check(permit.canProbe()) { "Computer route probe ended" }
                    val candidate = dial(route, allowed, bearer)
                    // A probe must never acquire ownership of the existing terminal root.
                    require(candidate.compatibilityWire !== access.client.compatibilityWire) { "Computer route probe borrowed its root" }
                    try {
                        candidate.configureAccountTokenRefresh {
                            check(permit.canProbe() && allowed()) { "Computer route probe ended" }
                            forceToken().also { check(permit.canProbe() && allowed()) { "Computer route probe ended" } }
                        }
                        candidate.withAttachTicket(null) { check(permit.canProbe()) { "Computer route probe ended" } }
                    } catch (failure: Throwable) { candidate.close(); throw failure }
                }, expected = { access.mac }, replacing = replacing,
                admitCompatibility = { owner, client, host ->
                    check(owner == access.team); runtime.admitForegroundRoute(access, client, host)
                }, manualTicket = { client, route, host, owner ->
                    check(owner == access.team); runtime.requestForegroundRouteTicket(access, client, route, host)
                }).use { authority ->
                    authority.authorize(pairing)
                    coroutineScope {
                        val monitor = launch { while (isActive) { delay(250); authority.retireInvalid() } }
                        try {
                            authority.connect(pairing, expectedScope = access.team, token = token).use { }
                        } finally { monitor.cancelAndJoin() }
                    }
                }
        }
    }

    companion object {
        fun create(context: Context, access: NativeForegroundComputerDetailAccess, runtime: NativeIrohRuntime,
            store: NativeCredentialStore): NativeForegroundTailscaleRoutes {
            val application = context.applicationContext
            val account = NativeAccount(store)
            return NativeForegroundTailscaleRoutes(access, runtime, store::load, store::update,
                resolve = { route, allowed -> TailscaleRoute.resolvePeer(application, route, allowed) },
                dial = { route, allowed, bearer -> MobileRpcClient(TailscaleRoute.resolve(application, route, allowed), bearer) },
                token = account::accessToken, forceToken = { account.accessToken(true) })
        }
    }
}
