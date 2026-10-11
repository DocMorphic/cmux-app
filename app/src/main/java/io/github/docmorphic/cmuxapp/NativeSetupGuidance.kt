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

/** Only typed authentication rejection counts; display/error text is never evidence. */
internal fun nativeSetupRequiresReauth(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause }.take(8).any {
        (it is MobileRpcException && it.fromHostResponse && it.code == "unauthorized") ||
            IrohV2Wire.isAuthenticationFailure(it)
    }
