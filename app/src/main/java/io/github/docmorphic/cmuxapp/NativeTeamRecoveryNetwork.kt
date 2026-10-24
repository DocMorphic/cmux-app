package io.github.docmorphic.cmuxapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.*

/** Default Internet path restoration shortens a missing-scope wait without bypassing throttles. */
internal class NativeTeamRecoveryNetwork(context:Context,private val scope:CoroutineScope,
    private val recover:suspend ()->Unit) : AutoCloseable {
    private val manager=context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val lock=Any()
    private var network:Network?=null
    private var ready=false
    private var closed=false
    private var registered=false
    private val callback=object:ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(value:Network,capabilities:NetworkCapabilities) {
            val signal=synchronized(lock) {
                if(closed)return
                network=value
                val next=capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                (next && !ready).also {ready=next}
            }
            if(signal)scope.launch {
                if(synchronized(lock){closed})return@launch
                try {recover()} catch(_:Exception){currentCoroutineContext().ensureActive()}
            }
        }
        override fun onLost(value:Network) {synchronized(lock){if(network==value){network=null;ready=false}}}
    }
    init {registered=try {manager?.registerDefaultNetworkCallback(callback);manager!=null} catch(_:Exception){false}}
    override fun close() {
        val remove=synchronized(lock){closed=true;registered.also {registered=false}}
        if(remove)runCatching {manager?.unregisterNetworkCallback(callback)}
    }
}
