package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*

/** Details uses the same saved-route boundary as reconnect, including strict methods and revocation. */
internal class NativeSavedComputerCheck(
    private val team: NativeTeamScope,
    private val target: NativeComputerTarget,
    private val permits: () -> Boolean,
    private val saved: () -> List<NativeCredentialStore.PairedMac>,
    private val allows: (NativeCredentialStore.PairedMac) -> Boolean,
    private val connect: suspend (NativeCredentialStore.PairedMac) -> MobileRpcClient
) {
    private fun selected() = saved().filter { NativeComputerTarget.from(it, team)?.let { row ->
        canonicalMacDeviceId(row.deviceId) == canonicalMacDeviceId(target.deviceId) && row.buildTag == target.buildTag
    } == true }.singleOrNull()?.takeIf(allows)

    fun available(): Boolean = runCatching { permits() && selected() != null }.getOrDefault(false)

    suspend fun run(timeoutMillis: Long = 30_000): NativeConnectionReport = try {
        withTimeout(timeoutMillis) {
            check(permits()) { "Account or team changed" }
            val captured = selected() ?: return@withTimeout NativeConnectionReport(failure = NativeConnectionReport.Failure.CONNECTION)
            connect(captured).use { client ->
                check(permits() && selected() == captured) { "Saved connection changed" }
                NativeConnectionCheck.run(client, captured).also {
                    check(permits() && selected() == captured) { "Saved connection changed" }
                }
            }
        }
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        NativeConnectionReport(failure = when {
            failure is TimeoutCancellationException -> NativeConnectionReport.Failure.TIMEOUT
            !permits() -> NativeConnectionReport.Failure.ACCOUNT
            failure is TailscaleReadinessException -> NativeConnectionReport.Failure.TAILSCALE
            failure is MacUpdateRequired -> NativeConnectionReport.Failure.UPDATE
            failure is MacBuildNotSupported -> NativeConnectionReport.Failure.BUILD
            else -> NativeConnectionReport.Failure.CONNECTION
        })
    }
}
