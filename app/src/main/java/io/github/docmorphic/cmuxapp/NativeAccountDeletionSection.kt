package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun NativeAccountDeletionButton(login: String?, receipt: NativeAccountDeletionReceipt?, begin: (String) -> Boolean) {
    // A confirmation never survives recreation or a different login.
    var confirming by remember(login) { mutableStateOf(false) }
    val pending = receipt?.result == NativeAccountDeletionResult.PROCESSING
    val destructive = Color(0xFFFF9999)
    TextButton(onClick = { confirming = true }, enabled = login != null && receipt == null,
        modifier = Modifier.padding(horizontal = 14.dp)) {
        Text(if (pending) "Deleting Account…" else "Delete Account", color = destructive)
    }
    if (confirming && login != null) AlertDialog(onDismissRequest = { confirming = false },
        title = { Text("Delete Account?") },
        text = { Text("This permanently deletes your cmux account and cmux data. You will be signed out on this device.") },
        confirmButton = { TextButton(onClick = { confirming = false; begin(login) }) { Text("Delete Account", color = destructive) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } })
}

/** Lives outside the Settings branch, so navigation cannot swallow a completed deletion. */
@Composable
internal fun NativeAccountDeletionAlerts(receipt: NativeAccountDeletionReceipt?, onSignOut: (String) -> Boolean,
                                        onAcknowledge: (NativeAccountDeletionReceipt) -> Boolean) {
    val currentSignOut by rememberUpdatedState(onSignOut)
    var localFailure by remember(receipt) { mutableStateOf(false) }
    LaunchedEffect(receipt) {
        if (receipt?.result == NativeAccountDeletionResult.COMPLETED) localFailure = !currentSignOut(receipt.login)
    }
    if (receipt != null && receipt.result != NativeAccountDeletionResult.PROCESSING &&
        (receipt.result != NativeAccountDeletionResult.COMPLETED || localFailure)) {
        val acknowledge = {
            localFailure = !(if (receipt.result.signsOut) onSignOut(receipt.login) else onAcknowledge(receipt))
        }
        AlertDialog(onDismissRequest = acknowledge,
            title = { Text(if (receipt.result == NativeAccountDeletionResult.COMPLETED) "Account Deleted" else receipt.result.title) },
            text = { Text((if (receipt.result == NativeAccountDeletionResult.COMPLETED) "Your account was deleted." else receipt.result.message) +
                if (localFailure) " Could not update local account state. Try again." else "") },
            confirmButton = { TextButton(onClick = acknowledge) {
                Text(if (localFailure && receipt.result.signsOut) "Retry sign out" else "OK")
            } })
    }
}
