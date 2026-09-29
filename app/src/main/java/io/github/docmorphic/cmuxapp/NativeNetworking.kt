package io.github.docmorphic.cmuxapp

import io.github.docmorphic.cmuxapp.iroh.IrxEndpointStatus
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Presentation projection deliberately contains no relay credentials or peer/device identifiers. */
internal data class NativeNetworkingSnapshot(
    val runtime: Runtime,
    val discovery: Discovery,
    val revision: Long?,
    val permissionExpiresAt: Long?,
    val permissionValid: Boolean,
    val relays: List<Relay>,
    val homeRelay: String?,
    val sampledAt: Long
) {
    enum class Runtime { WAITING_FOR_MAC, ACTIVE, STOPPED }
    enum class Discovery { PUSH, POLLING, UNAVAILABLE }
    data class Relay(val url: String, val expiresAt: Long, val usable: Boolean, val home: Boolean)

    companion object {
        fun from(control: IrohV2ControlState, endpoint: IrxEndpointStatus?, now: Long): NativeNetworkingSnapshot {
            val home = endpoint?.takeIf { it.open }?.homeRelayUrl?.let(::displayRelay)
            return NativeNetworkingSnapshot(
                runtime = when { endpoint == null -> Runtime.WAITING_FOR_MAC; endpoint.open -> Runtime.ACTIVE; else -> Runtime.STOPPED },
                discovery = when { !control.ready -> Discovery.UNAVAILABLE; control.mode == "websocket" -> Discovery.PUSH;
                    control.mode == "http" -> Discovery.POLLING; else -> Discovery.UNAVAILABLE },
                revision = control.directoryRevision,
                permissionExpiresAt = control.permissionExpiresAt,
                permissionValid = control.ready && (control.permissionExpiresAt ?: 0) > now,
                relays = control.relays.mapNotNull { relay -> displayRelay(relay.url)?.let { url ->
                    Relay(url, relay.expiresAt, relay.expiresAt > now, home == url)
                } }, homeRelay = home, sampledAt = now)
        }

        // Reject URLs that could accidentally expose credentials in a diagnostic view.
        private fun displayRelay(raw: String): String? {
            if (raw.length > 2048) return null
            val url = raw.toHttpUrlOrNull() ?: return null
            return url.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() &&
                it.query == null && it.fragment == null && it.encodedPath == "/" }?.toString()
        }
    }
}
