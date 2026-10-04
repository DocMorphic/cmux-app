package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*

internal data class NativeSshCreateTarget(val session: NativeSshSession, val host: SshHostRecord,
    val connection: SshConnectionStatus?, val options: List<SshWorkspaceKindOption>) {
    val name get() = host.name
    val status get() = when (connection?.phase) {
        SshConnectionPhase.CONNECTED -> "Connected"
        SshConnectionPhase.CONNECTING -> "Connecting…"
        SshConnectionPhase.FAILED -> "Connection failed"
        else -> "Not connected"
    }
}

@Composable
internal fun nativeSshCreateTargets(session: NativeSshSession?): List<NativeSshCreateTarget> {
    if (session == null || !session.isOpen) return emptyList()
    val hosts by session.hosts.state.collectAsState()
    val connections by session.connections.statuses.collectAsState()
    return hosts.hosts.map { host -> key(session, host.id) {
        val tmux = session.tmux.peek(host.id)?.state?.collectAsState()?.value
        val cmux = session.cmux.peek(host.id)?.state?.collectAsState()?.value
        NativeSshCreateTarget(session, host, connections[host.id], sshWorkspaceKinds(tmux, cmux))
    } }
}
