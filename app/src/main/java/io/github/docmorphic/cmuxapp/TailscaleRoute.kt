package io.github.docmorphic.cmuxapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import javax.net.SocketFactory

/** Resolve official QR routes through an active VPN and dial only 100.64/10 peers. */
object TailscaleRoute {
    data class DialTarget(val route: PairingCode.Route, val socketFactory: SocketFactory)

    fun resolve(context: Context, route: PairingCode.Route): DialTarget {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val vpn = manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } ?: error("Connect Tailscale on this phone first")
        val addresses = vpn.getAllByName(route.host)
        val peer = addresses.firstOrNull { address ->
            address is Inet4Address && address.address.size == 4 &&
                (address.address[0].toInt() and 0xff) == 100 &&
                (address.address[1].toInt() and 0xff) in 64..127
        } ?: error("The QR route did not resolve to a Tailscale peer")
        return DialTarget(PairingCode.Route(peer.hostAddress, route.port), vpn.socketFactory)
    }
}
