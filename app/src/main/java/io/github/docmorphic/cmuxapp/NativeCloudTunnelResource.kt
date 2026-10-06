package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

internal class NativeCloudTunnelResource(
    private val tunnel: CloudNativeTunnel, val connections: CloudMachineConnections<CloudNativeSession>
) : AutoCloseable {
    /** Synchronous admission fence; native destruction follows on the IO worker. */
    fun retire() = connections.close()
    override fun close() { retire(); tunnel.close() }
}

internal fun cloudDaemonStateDirectory(root: File, owner: NativeTeamScope): File {
    // Exclude the transient generation: known-daemon identity survives re-login
    // for the same account/team, without sharing it across different owners.
    val key = JSONArray(listOf("https://cmux.com", owner.userId, owner.teamId)).toString()
    val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    // Rust creates this directory with its own private-mode policy.
    return File(root, "cloud-daemons-$hash")
}

/** Runs on the tunnel controller's IO worker; no suspension after allocating the native handle. */
internal suspend fun startNativeCloudTunnelResource(context: Context, parent: CoroutineScope,
    api: CloudApi, teams: NativeAccountTeams, owner: NativeTeamScope): NativeCloudTunnelResource {
    val material = prepareNativeCloudTunnel(context, api, teams, owner)
    val directory = cloudDaemonStateDirectory(context.noBackupFilesDir, owner)
    val deviceName = TerminalDeviceIdentity(Build.MODEL).name
    val tunnel = CloudNativeTunnel.start(material.configuration)
    try {
        return NativeCloudTunnelResource(tunnel, CloudMachineConnections(parent, api, material.identity.fingerprint,
            { teams.isCurrent(owner) }, { endpoint -> tunnel.connect(endpoint, directory, deviceName) },
            retireSession = CloudNativeSession::retire))
    } catch (failure: Throwable) { tunnel.close(); throw failure }
}
