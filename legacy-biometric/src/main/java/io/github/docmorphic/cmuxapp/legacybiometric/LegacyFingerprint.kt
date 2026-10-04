package io.github.docmorphic.cmuxapp.legacybiometric

import android.content.Context
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.security.Signature

/** API 26–27 only. Keeps the original operation-bound authentication contract. */
@Suppress("DEPRECATION")
object LegacyFingerprint {
    fun authenticate(context: Context, signature: Signature, cancellation: CancellationSignal,
        onSuccess: (Signature?) -> Unit, onError: () -> Unit, onHelp: (String) -> Unit,
        onFailed: () -> Unit) {
        check(Build.VERSION.SDK_INT in 26..27) { "Use the system biometric prompt on API 28+" }
        val manager = context.getSystemService(FingerprintManager::class.java)
        if (manager == null || !manager.isHardwareDetected || !manager.hasEnrolledFingerprints()) {
            onError()
            return
        }
        manager.authenticate(FingerprintManager.CryptoObject(signature), cancellation, 0,
            object : FingerprintManager.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult) {
                    onSuccess(result.cryptoObject?.signature)
                }
                override fun onAuthenticationError(code: Int, text: CharSequence) = onError()
                override fun onAuthenticationHelp(code: Int, text: CharSequence) = onHelp(text.toString())
                override fun onAuthenticationFailed() = onFailed()
            }, Handler(Looper.getMainLooper()))
    }
}
