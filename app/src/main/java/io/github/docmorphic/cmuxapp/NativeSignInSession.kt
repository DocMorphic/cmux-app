package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class NativeSignInState(val email: String = "", val code: String = "", val codeSent: Boolean = false,
    val busy: Boolean = false, val error: NativeSignInError? = null, val completion: Long? = null,
    val verificationRequired: Boolean = false, val recoveryError: NativeSignInRecoveryError? = null,
    val billingRecoveryAccepted: Boolean = false, val authenticating: Boolean = false,
    val codeFocusRequest: Long? = null, val emailFocusRequest: Long? = null)

/** Owner-confined, memory-only form and requests. Configuration changes do not retire this owner. */
internal class NativeSignInSession(parent: CoroutineScope, private val send: suspend (String) -> Unit,
    private val authenticate: suspend (String) -> Unit, private val clearChallenge: () -> Unit = {},
    private val requestVerification: suspend (String) -> Unit = { throw NativeSignInRecoveryFailure(NativeSignInRecoveryError.UNAVAILABLE) },
    private val requestBilling: suspend (String) -> Unit = { throw NativeSignInRecoveryFailure(NativeSignInRecoveryError.UNAVAILABLE) },
    private val verificationTimeoutMillis: Long = 60_000) : AutoCloseable {
    init { require(verificationTimeoutMillis > 0) }
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutable = MutableStateFlow(NativeSignInState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var emailFocusCounter = 0L
    private var pending: Job? = null
    // A cancelled exchange can outlive its UI waiter. Track it until terminal,
    // including through multiple replacement edits or a timed-out preflight.
    private val exchanges = mutableSetOf<Deferred<Unit>>()
    fun editEmail(value: String) {
        if (!job.isActive || state.value.busy || state.value.completion != null) return
        if (value != state.value.email) {
            clearChallenge()
            mutable.value = NativeSignInState(email = value)
        }
    }
    fun editCode(value: String) {
        val form = state.value
        if (!job.isActive || form.completion != null || !form.codeSent || (form.busy && !form.authenticating) || value == form.code) return
        val code = nativeSignInCode(value)
        mutable.value = form.copy(code = code)
        // SwiftUI applies .assign, then receives .verify for the normalized
        // six-character value. An unchanged value is not a new edit event.
        if (code.length == 6) request(signingIn = true, automatic = true)
    }
    fun requestCode() = request(signingIn = false)
    fun submitCode() = request(signingIn = true)
    private fun request(signingIn: Boolean, automatic: Boolean = false) {
        val form = state.value
        val valid = if (signingIn) form.codeSent && form.code.length == 6 else form.email.isNotBlank()
        if (!job.isActive || (form.busy && !(automatic && form.authenticating)) || form.completion != null || !valid) return
        val admitted = ++generation
        val previous = if (signingIn) exchanges.toList() else emptyList()
        if (signingIn) {
            previous.forEach { it.cancel() }
            pending?.cancel()
        }
        mutable.value = form.copy(busy = true, error = null, recoveryError = null, completion = null, codeSent = if (signingIn) form.codeSent else false,
            code = if (signingIn) form.code else "", authenticating = signingIn)
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (signingIn) {
                    // Original beginSignInFlow cancels/joins older token work
                    // with the60s network bound before starting a new exchange.
                    withTimeout(verificationTimeoutMillis) { previous.forEach { it.join() } }
                    ensureActive()
                    if (generation != admitted) return@launch
                    val exchange = scope.async(start = CoroutineStart.LAZY) { authenticate(form.code) }
                    exchanges.add(exchange)
                    exchange.invokeOnCompletion { exchanges.remove(exchange) }
                    exchange.start()
                    try { withTimeout(verificationTimeoutMillis) { exchange.await() } }
                    finally { exchange.cancel() }
                } else send(form.email)
                ensureActive()
                if (generation != admitted) return@launch
                mutable.value = if (signingIn) NativeSignInState(completion = admitted)
                    else mutable.value.copy(codeSent = true, verificationRequired = false,
                        codeFocusRequest = if (form.verificationRequired) null else admitted)
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation != admitted) return@launch
                if (!signingIn && failure is NativeAccountRequestFailure && failure.requiresEmailVerification) {
                    // Enter recovery even if its email provider is unavailable.
                    mutable.value = mutable.value.copy(verificationRequired = true, codeSent = false, code = "")
                    if (form.verificationRequired) mutable.value = mutable.value.copy(recoveryError = NativeSignInRecoveryError.PENDING)
                    else try {
                        requestVerification(form.email)
                        ensureActive()
                    } catch (recoveryIssue: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (generation == admitted) recoveryFailure(recoveryIssue)
                    }
                } else {
                    val display = nativeSignInError(failure) ?: return@launch
                    mutable.value = mutable.value.copy(
                    // Original SignInView.verifyCode clears the submitted code
                    // after failure; retry requires explicit input again.
                    code = if (signingIn) "" else mutable.value.code,
                    error = display)
                }
            } finally {
                if (job.isActive && generation == admitted) mutable.value = mutable.value.copy(busy = false, authenticating = false)
            }
        }
        pending = task
        task.start()
    }
    fun consumeCodeFocus(request: Long): Boolean {
        if (!job.isActive || !state.value.codeSent || state.value.codeFocusRequest != request) return false
        mutable.value = state.value.copy(codeFocusRequest = null)
        return true
    }
    fun consumeEmailFocus(request: Long): Boolean {
        val form = state.value
        if (!job.isActive || form.busy || form.codeSent || form.verificationRequired || form.emailFocusRequest != request) return false
        mutable.value = form.copy(emailFocusRequest = null)
        return true
    }
    private fun recoveryFailure(failure: Exception) {
        mutable.value = mutable.value.copy(recoveryError = (failure as? NativeSignInRecoveryFailure)?.reason
            ?: NativeSignInRecoveryError.UNAVAILABLE)
    }
    fun resendVerification() = recover(billing = false)
    fun recoverBilling() = recover(billing = true)
    private fun recover(billing: Boolean) {
        val form = state.value
        if (!job.isActive || form.busy || !form.verificationRequired || form.completion != null) return
        val admitted = ++generation
        mutable.value = form.copy(busy = true, error = null, recoveryError = null,
            billingRecoveryAccepted = if (billing) false else form.billingRecoveryAccepted)
        pending = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (billing) requestBilling(form.email) else requestVerification(form.email)
                ensureActive()
                if (generation == admitted && billing) mutable.value = mutable.value.copy(billingRecoveryAccepted = true)
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == admitted) recoveryFailure(failure)
            } finally {
                if (job.isActive && generation == admitted) mutable.value = mutable.value.copy(busy = false)
            }
        }
    }
    fun useDifferentEmail() = returnToEmail(focusEmail = false)
    fun returnFromCode(focusEmail: Boolean) = returnToEmail(focusEmail)
    private fun returnToEmail(focusEmail: Boolean) {
        val form = state.value
        if (!job.isActive || (form.busy && !form.authenticating) || form.completion != null) return
        val focus = if (focusEmail) ++emailFocusCounter else null
        if (form.authenticating) {
            // Original code-view Back changes entry mode without cancelling the
            // admitted exchange; the email form stays busy until it finishes.
            mutable.value = form.copy(codeSent = false, code = "", error = null, codeFocusRequest = null, emailFocusRequest = focus)
            return
        }
        clear()
        mutable.value = NativeSignInState(email = form.email, emailFocusRequest = focus)
    }
    fun consumeCompletion(value: Long): Boolean {
        if (!job.isActive || state.value.completion != value) return false
        mutable.value = state.value.copy(completion = null)
        return true
    }
    fun clear() {
        ++generation; pending?.cancel(); pending = null; exchanges.toList().forEach { it.cancel() }; clearChallenge()
        mutable.value = NativeSignInState()
    }
    override fun close() { clear(); job.cancel() }
}
