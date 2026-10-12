package io.github.docmorphic.cmuxapp

/** Guidance only. Reading this policy cannot dial, discover, or admit a connection. */
internal enum class NativeSetupGuidance(val id: String) {
    NOT_SIGNED_IN("notSignedIn"), NEVER_PAIRED("signedInNeverPaired"),
    MAC_UNREACHABLE("macUnreachable"), ACCOUNT_MISMATCH("accountMismatch");

    companion object {
        fun resolve(signedIn: Boolean, hasKnownPairedMac: Boolean, hasAccountMismatch: Boolean) = when {
            hasAccountMismatch -> ACCOUNT_MISMATCH
            !signedIn -> NOT_SIGNED_IN
            hasKnownPairedMac -> MAC_UNREACHABLE
            else -> NEVER_PAIRED
        }
        fun ordered(highlight: NativeSetupGuidance?) =
            highlight?.let { listOf(it) + entries.filter { gate -> gate != it } } ?: entries.toList()
    }
}

/** Match the iOS auth sink only for host-reported RPC errors or typed control failures.
 * General network/local exception text never establishes an authentication failure. */
internal fun nativeSetupRequiresReauth(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause }.take(8).any {
        (it is MobileRpcException && it.fromHostResponse && (
            it.code?.trim()?.lowercase() in setOf("unauthorized", "forbidden", "invalid_token",
                "token_expired", "expired_token", "auth_required") ||
            it.message?.trim()?.lowercase()?.let { message ->
                listOf("unauthorized", "forbidden", "invalid token", "expired token", "token expired")
                    .any(message::contains)
            } == true)) ||
            IrohV2Wire.isAuthenticationFailure(it)
    }

/** A new automatic session cannot repair an auth/permission/protocol terminal failure. */
internal fun nativeAutomaticReconnectStops(failure: Throwable): Boolean =
    nativeSetupRequiresReauth(failure) || generateSequence(failure) { it.cause }.take(8).any {
        IrohV2Recovery.stops(it) || (it is io.github.docmorphic.cmuxapp.iroh.IrxWire.AdmissionRejected &&
            it.code.terminalForRedial)
    }
