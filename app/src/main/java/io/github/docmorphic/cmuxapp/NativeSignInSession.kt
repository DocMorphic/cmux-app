package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class NativeSignInState(val email: String = "", val code: String = "", val codeSent: Boolean = false,
    val busy: Boolean = false, val error: String? = null, val completion: Long? = null)

/** Owner-confined, memory-only form and requests. Configuration changes do not retire this owner. */
internal class NativeSignInSession(parent: CoroutineScope, private val send: suspend (String) -> Unit,
    private val authenticate: suspend (String) -> Unit, private val clearChallenge: () -> Unit = {}) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutable = MutableStateFlow(NativeSignInState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var pending: Job? = null
    fun editEmail(value: String) {
        if (!job.isActive || state.value.busy || state.value.completion != null) return
        if (value != state.value.email) {
            clearChallenge()
            mutable.value = NativeSignInState(email = value)
        }
    }
    fun editCode(value: String) {
        if (job.isActive && !state.value.busy && state.value.completion == null && state.value.codeSent)
            mutable.value = state.value.copy(code = value.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.take(6))
    }
    fun requestCode() = request(signingIn = false)
    fun submitCode() = request(signingIn = true)
    private fun request(signingIn: Boolean) {
        val form = state.value
        val valid = if (signingIn) form.codeSent && form.code.length == 6 else form.email.isNotBlank()
        if (!job.isActive || form.busy || form.completion != null || !valid) return
        val admitted = ++generation
        mutable.value = form.copy(busy = true, error = null, completion = null, codeSent = if (signingIn) form.codeSent else false,
            code = if (signingIn) form.code else "")
        pending = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (signingIn) authenticate(form.code) else send(form.email)
                ensureActive()
                if (generation != admitted) return@launch
                mutable.value = if (signingIn) NativeSignInState(completion = admitted)
                    else mutable.value.copy(codeSent = true)
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == admitted) mutable.value = mutable.value.copy(
                    // Original SignInView.verifyCode clears the submitted code
                    // after failure; retry requires explicit input again.
                    code = if (signingIn) "" else mutable.value.code,
                    error = failure.message
                        ?: if (signingIn) "Could not sign in. Please try again." else "Could not send the code. Please try again.")
            } finally {
                if (job.isActive && generation == admitted) mutable.value = mutable.value.copy(busy = false)
            }
        }
    }
    fun consumeCompletion(value: Long): Boolean {
        if (!job.isActive || state.value.completion != value) return false
        mutable.value = state.value.copy(completion = null)
        return true
    }
    fun clear() {
        ++generation; pending?.cancel(); pending = null; clearChallenge()
        mutable.value = NativeSignInState()
    }
    override fun close() { clear(); job.cancel() }
}
