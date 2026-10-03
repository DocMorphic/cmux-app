package io.github.docmorphic.cmuxapp

internal enum class NativeOnboardingProgress { WELCOME, CONNECT, COMPLETE }
internal enum class NativeOnboardingStage { AGENTS, NOTIFICATIONS, PUSH, PAIRING, CONNECT }
internal enum class NativeOnboardingMethod { AUTOMATIC, TAILSCALE }
internal enum class NativeOnboardingPhase { IDLE, SEARCHING, FALLBACK, READY;
    companion object {
        fun resolve(ready: Boolean, searching: Boolean, finished: Boolean) = when {
            ready -> READY
            searching -> SEARCHING
            finished -> FALLBACK
            else -> IDLE
        }
    }
}

/** Per-install milestones. Replay has no reference to this writer. */
internal class NativeOnboardingProgressStore(
    private val read: () -> String?, private val write: (String) -> Boolean,
    private val forceComplete: Boolean = false
) {
    val progress: NativeOnboardingProgress get() = if (forceComplete) NativeOnboardingProgress.COMPLETE else
        NativeOnboardingProgress.entries.firstOrNull { it.name == read() } ?: NativeOnboardingProgress.WELCOME
    fun connect() { if (progress != NativeOnboardingProgress.COMPLETE) save(NativeOnboardingProgress.CONNECT) }
    fun complete() = save(NativeOnboardingProgress.COMPLETE)
    private fun save(value: NativeOnboardingProgress) {
        if (!forceComplete) check(write(value.name)) { "Could not save introduction progress. Try again." }
    }
    companion object { const val KEY = "onboarding.redesign.progress.v1" }
}

internal fun nativeOnboardingEligible(progress: NativeOnboardingProgress, signedIn: Boolean,
    verifiedAccount: Boolean, cached: Boolean, explicitRoute: Boolean): Boolean =
    progress != NativeOnboardingProgress.COMPLETE && signedIn && verifiedAccount && !cached && !explicitRoute

internal data class NativeOnboardingChrome(val primary: String?, val secondary: String? = null,
    val back: Boolean, val skip: Boolean) {
    companion object {
        fun forStage(stage: NativeOnboardingStage, phase: NativeOnboardingPhase, method: NativeOnboardingMethod): NativeOnboardingChrome {
            val primary = when (stage) {
                NativeOnboardingStage.AGENTS, NativeOnboardingStage.NOTIFICATIONS -> "Continue"
                NativeOnboardingStage.PUSH -> "Enable Notifications"
                NativeOnboardingStage.PAIRING -> "I've enabled iOS pairing"
                NativeOnboardingStage.CONNECT -> when (phase) {
                    NativeOnboardingPhase.READY -> "Open Workspaces"
                    NativeOnboardingPhase.SEARCHING -> null
                    else -> if (method == NativeOnboardingMethod.TAILSCALE) "Scan Pairing Code"
                        else if (phase == NativeOnboardingPhase.FALLBACK) "Check Again" else "Check for My Mac"
                }
            }
            val secondary = when {
                stage == NativeOnboardingStage.PUSH -> "Not Now"
                stage == NativeOnboardingStage.CONNECT && method == NativeOnboardingMethod.TAILSCALE && phase == NativeOnboardingPhase.FALLBACK -> "Check Again"
                else -> null
            }
            return NativeOnboardingChrome(primary, secondary, stage != NativeOnboardingStage.AGENTS,
                stage.ordinal < NativeOnboardingStage.PAIRING.ordinal)
        }
    }
}

/** A replay never starts a new connection until the user asks for one. */
internal fun nativeOnboardingMayChoose(visible: Boolean, foreground: Boolean, explicitPairing: Boolean,
    replay: Boolean, requested: Boolean, automatic: Boolean, hasCode: Boolean, busy: Boolean, ready: Boolean): Boolean =
    visible && foreground && !explicitPairing && (!replay || requested) && automatic && !hasCode && !busy && !ready
