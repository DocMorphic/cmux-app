package io.github.docmorphic.cmuxapp

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
internal fun NativeNotificationRecoveryDialog(state: NativeNotificationRouteRecovery,
    onRetry: () -> Unit, onCancel: () -> Unit) {
    if (state.phase !in setOf(NativeNotificationRouteRecovery.Phase.FAILED, NativeNotificationRouteRecovery.Phase.RECONNECTING)) return
    val connecting = state.phase == NativeNotificationRouteRecovery.Phase.RECONNECTING
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(if (connecting) "Opening notification" else "Could not open notification") },
        text = { Text(if (connecting) "Reconnecting to the notification’s Mac…" else
            state.message ?: "Reconnect to this Mac and try again.") },
        confirmButton = { if (!connecting) TextButton(onClick = onRetry) { Text("Retry") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}
