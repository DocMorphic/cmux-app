package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*

internal enum class SshKeyInstallStage { PASSWORD_LOGIN, WRITE_KEY, VERIFY_KEY }
internal class SshKeyInstallFailure(val stage: SshKeyInstallStage) : Exception(when (stage) {
    SshKeyInstallStage.PASSWORD_LOGIN -> "Could not sign in with the password. Check the server identity, username and password, and that the server allows password login."
    SshKeyInstallStage.WRITE_KEY -> "Could not confirm the authorized_keys update. The key may already be installed. You can retry explicitly or add the public key manually."
    SshKeyInstallStage.VERIFY_KEY -> "The key was added, but a key-only login did not succeed. Check the server's SSH key settings and try again."
})

/** Explicit, idempotent public-key append followed by an independent key-only proof. */
internal object SshKeyInstaller {
    // Only the public key travels on stdin. No password or user-controlled shell
    // words enter this command, and an existing authorized_keys is never replaced.
    val command = "sh -c " + SshTmuxEncoding.shellQuote(
        "umask 077; IFS= read -r key || exit 64; d=\"\$HOME/.ssh\"; f=\"\$d/authorized_keys\"; " +
            "mkdir -p \"\$d\" && touch \"\$f\" && chmod 700 \"\$d\" && chmod 600 \"\$f\" && " +
            "{ grep -qxF \"\$key\" \"\$f\" || printf '\\n%s\\n' \"\$key\" >> \"\$f\"; }")

    /** Takes ownership of password. No retry and no persistent password state. */
    suspend fun install(hosts: SshHostStore, vault: SshKeyVault, expected: SshHostRecord, password: ByteArray,
        admitted: () -> Boolean, askTrust: suspend (SshTrustQuestion) -> Boolean,
        authorize: suspend (SshKeyRecord, SshPreparedSignature) -> Unit,
    ) = coroutineScope {
        try {
            check(admitted()) { "Sign in before installing an SSH key" }
            check(hosts.state.value.host(expected.id)?.connectsLike(expected) == true) { "SSH computer changed" }
            var plan = hosts.dialPlan(expected.id)
            for (hop in plan.hops) {
                check(hosts.setAutoConnectPaused(plan, hop.hostId, false)) { "SSH route changed" }
                plan = hosts.dialPlan(expected.id)
            }
            val key = checkNotNull(vault.state.value.firstOrNull { it.id == expected.keyId }) { "Choose an available SSH key" }
            val fixedPlan = plan
            fun current() = admitted() && isActive && hosts.isCurrent(fixedPlan) &&
                vault.state.value.any { it.id == key.id && it.publicKey == key.publicKey }
            var stage = SshKeyInstallStage.PASSWORD_LOGIN
            try {
                SshTransport.connectForKeyInstallation(hosts, vault, expected.id, this, ::current, password, askTrust, authorize).use { connection ->
                    stage = SshKeyInstallStage.WRITE_KEY
                    check(current()) { "SSH key setup retired" }
                    val result = connection.exec(command, maxOutputBytes = 64 * 1024, stdin = (key.publicKey.openSsh + "\n").toByteArray())
                    check(result.exitStatus == 0) { "Key update failed" }
                }
                stage = SshKeyInstallStage.VERIFY_KEY
                check(current()) { "SSH key setup retired" }
                SshTransport.connect(hosts, vault, expected.id, this, ::current, explicit = false, askTrust, authorize).use {
                    check(current() && it.isConnected) { "SSH key setup retired" }
                }
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                // Never surface remote stderr, authentication messages or secret-bearing exceptions.
                throw SshKeyInstallFailure(stage)
            }
        } finally { password.fill(0) }
    }
}
