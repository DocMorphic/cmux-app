package io.github.docmorphic.cmuxapp

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Ephemeral UI state: never restore suppression from a saved navigation checkpoint. */
internal data class NativeNotificationSelection(val login: String, val origin: String,
    val workspace: String, val terminal: String?)

internal class NativeNotificationVisibility {
    private val selections = mutableMapOf<Any, NativeNotificationSelection>()
    @Synchronized fun update(owner: Any, selection: NativeNotificationSelection?) {
        if (selection == null) selections.remove(owner) else selections[owner] = selection
    }
    @Synchronized fun suppresses(login: String?, origins: Set<String>, item: NativeNotification): Boolean =
        !login.isNullOrBlank() && item.workspaceId.isNotBlank() && selections.values.any {
            it.login == login && it.origin in origins && it.workspace == item.workspaceId &&
                (item.surfaceId == null || item.surfaceId == it.terminal)
        }

    companion object {
        val shared = NativeNotificationVisibility()
        fun suppresses(context: Context, login: String?, mac: NativeCredentialStore.PairedMac?, item: NativeNotification): Boolean {
            if (mac == null || !context.getSystemService(PowerManager::class.java).isInteractive ||
                context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
            return shared.suppresses(login, mac.origins, item)
        }
    }
}

/** Pause removes the selection immediately, independently of the next Compose frame. */
internal class NativeNotificationVisibilityOwner(private val lifecycle: Lifecycle,
    private val visibility: NativeNotificationVisibility = NativeNotificationVisibility.shared) : AutoCloseable {
    private var selection: NativeNotificationSelection? = null
    private var closed = false
    private val observer = LifecycleEventObserver { _, _ -> publish() }
    init { lifecycle.addObserver(observer) }
    fun update(value: NativeNotificationSelection?) { selection = value; publish() }
    private fun publish() = visibility.update(this,
        selection.takeIf { !closed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) })
    override fun close() { closed = true; lifecycle.removeObserver(observer); visibility.update(this, null) }
}

@Composable internal fun ObserveNativeNotificationSelection(lifecycle: Lifecycle, selection: NativeNotificationSelection?) {
    val owner = remember(lifecycle) { NativeNotificationVisibilityOwner(lifecycle) }
    DisposableEffect(owner) { onDispose { owner.close() } }
    SideEffect { owner.update(selection) }
}
