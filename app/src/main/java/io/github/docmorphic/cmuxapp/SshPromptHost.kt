package io.github.docmorphic.cmuxapp

import android.hardware.biometrics.BiometricPrompt
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Mounted at the signed-in root, independently of editor/navigation lifetime. */
@Composable
internal fun NativeSshPromptHost(runtime: NativeSshRuntime) {
    val state by runtime.state.collectAsState()
    state.resource?.takeIf { it.admitted() }?.let { session ->
        key(session) { SshPromptHost(session) }
    }
}

@Composable
internal fun SshPromptHost(session: NativeSshSession) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val prompts by session.connections.prompts.collectAsState()
    val biometrics by session.biometrics.collectAsState()
    if (!started || !session.admitted()) return
    val prompt = prompts.firstOrNull()
    // Serialize the two kinds of modal prompt. Each biometric request retains
    // the exact operation prepared by the transport, not a generic success bit.
    if (prompt != null) {
        val question = prompt.question
        val changed = question.prior.pinned != null
        var error by remember(prompt.id) { mutableStateOf<String?>(null) }
        fun answer(trust: Boolean) {
            runCatching { session.connections.answer(prompt.id, trust) }
                .onFailure { error = "Could not save this decision. Try again." }
        }
        AlertDialog(onDismissRequest = { answer(false) }, modifier = Modifier.testTag("ssh.trust"),
            title = { Text(if (changed) "SSH host key changed" else "Verify SSH computer") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${question.endpoint.host}:${question.endpoint.port}")
                Text(if (changed) "This computer has a different identity from the one saved on your phone. Verify the new fingerprint with its administrator before replacing it."
                    else "Verify this fingerprint with the computer’s administrator before connecting.")
                SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    question.prior.pinned?.let { Text("Saved: ${it.sha256Fingerprint}", fontFamily = FontFamily.Monospace) }
                    Text("Presented: ${question.presented.algorithm}\n${question.presented.sha256Fingerprint}", fontFamily = FontFamily.Monospace)
                } }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(onClick = { answer(true) }, modifier = Modifier.testTag("ssh.trust.accept")) { Text(if (changed) "Replace and Connect" else "Trust and Connect") } },
            dismissButton = { TextButton(onClick = { answer(false) }) { Text("Cancel") } })
    } else biometrics.firstOrNull()?.let { request ->
        key(request.id) { SshBiometricPrompt(request) { signature -> session.answerBiometric(request.id, signature) } }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun SshBiometricPrompt(request: SshBiometricRequest, answer: (java.security.Signature?) -> Unit) {
    val context = LocalContext.current
    val latestAnswer by rememberUpdatedState(answer)
    var message by remember { mutableStateOf("Touch the fingerprint sensor to use ${request.key.label}.") }
    DisposableEffect(request.id, context) {
        var active = true
        val signal = CancellationSignal()
        val executor = Executor { task -> Handler(Looper.getMainLooper()).post(task) }
        fun finish(signature: java.security.Signature?) { if (active) latestAnswer(signature) }
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val builder = BiometricPrompt.Builder(context).setTitle("Use SSH Key")
                    .setSubtitle(request.key.label).setNegativeButton("Cancel", executor) { _, _ -> finish(null) }
                if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                builder.build().authenticate(BiometricPrompt.CryptoObject(request.operation.signature), signal, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { finish(result.cryptoObject?.signature) }
                        override fun onAuthenticationError(code: Int, text: CharSequence) { finish(null) }
                    })
            } else {
                val manager = context.getSystemService(FingerprintManager::class.java)
                if (manager == null || !manager.isHardwareDetected || !manager.hasEnrolledFingerprints()) finish(null)
                else manager.authenticate(FingerprintManager.CryptoObject(request.operation.signature), signal, 0,
                    object : FingerprintManager.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult) { finish(result.cryptoObject?.signature) }
                        override fun onAuthenticationError(code: Int, text: CharSequence) { finish(null) }
                        override fun onAuthenticationHelp(code: Int, text: CharSequence) { message = text.toString() }
                        override fun onAuthenticationFailed() { message = "Fingerprint not recognized. Try again." }
                    }, Handler(Looper.getMainLooper()))
            }
        } catch (_: Exception) { finish(null) }
        onDispose {
            // An old Activity's cancellation callback cannot answer a prompt
            // presented by its replacement. Cancellation retires only its UI.
            active = false
            signal.cancel()
        }
    }
    if (Build.VERSION.SDK_INT < 28) AlertDialog(onDismissRequest = { answer(null) }, title = { Text("Use SSH Key") },
        text = { Text(message) }, confirmButton = {}, dismissButton = { TextButton(onClick = { answer(null) }) { Text("Cancel") } })
}
