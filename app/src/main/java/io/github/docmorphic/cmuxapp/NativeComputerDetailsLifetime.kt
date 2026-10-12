package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

internal class NativeComputerDetailsState {
    private val presentation = mutableStateOf<NativeComputerDetailsPresentation?>(null)
    var value: NativeComputerDetailsPresentation?
        get() = presentation.value
        set(next) {
            val previous = presentation.value
            if (previous === next) return
            previous?.lifetime?.close()
            presentation.value = next
        }
}

/** Retains one confirmed action across view recreation, without retaining an Activity callback. */
internal class NativeComputerDetailsLifetime {
    private data class Attached(val token: Any, val callbacks: NativeComputerForgetCallbacks)
    private val attached = AtomicReference<Attached?>(null)
    private var action: Job? = null
    private var closed = false
    private var completionDelivered = false
    private var flow: NativeComputerForgetFlow? = null
    val confirmation = mutableStateOf(false)
    fun forget(factory: () -> NativeComputerForgetFlow): NativeComputerForgetFlow =
        flow ?: factory().also { flow = it }
    fun attach(token: Any, callbacks: NativeComputerForgetCallbacks) { attached.set(Attached(token, callbacks)) }
    fun update(token: Any, callbacks: NativeComputerForgetCallbacks) {
        val previous = attached.get() ?: return
        if (previous.token === token) attached.compareAndSet(previous, Attached(token, callbacks))
    }
    fun detach(token: Any) {
        val previous = attached.get() ?: return
        if (previous.token === token) attached.compareAndSet(previous, null)
    }
    fun prepare(team: NativeTeamScope, target: NativeComputerTarget, rows: List<NativeCredentialStore.PairedMac>,
        fallback: () -> Unit) {
        val current = attached.get()
        if (current != null) current.callbacks.started(team, target, rows) else fallback()
    }
    fun confirm(scope: CoroutineScope) {
        if (closed || action?.isActive == true) return
        val captured = flow ?: return
        action = scope.launch { captured.confirm() }
    }
    fun deliverFinished(): Boolean {
        if (closed || completionDelivered || flow?.state?.value?.finished != true) return false
        val callbacks = attached.get()?.callbacks ?: return false
        completionDelivered = true
        callbacks.finished()
        return true
    }
    fun canHost(selectedOwner: Boolean, foregroundCurrent: Boolean, login: String?, team: NativeTeamScope): Boolean {
        if (closed) return false
        if (selectedOwner) return true
        if (login != team.login) return false
        val removal = flow?.state?.value
        return foregroundCurrent || removal?.busy == true ||
            (removal?.remoteConfirmed == true && !removal.finished) ||
            (removal?.finished == true && !completionDelivered) || flow?.canRetry() == true
    }
    fun close() { closed = true; action?.cancel(); action = null; attached.set(null); flow = null; confirmation.value = false }
}
