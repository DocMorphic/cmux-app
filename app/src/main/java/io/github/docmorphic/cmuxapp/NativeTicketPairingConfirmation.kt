package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun NativeTicketPairingConfirmation(proposal: NativeTicketPairing.Proposal, onDismiss: () -> Unit,
                                             onConnect: (NativeTicketPairingRoutes.Choice) -> Unit) {
    var selected by remember(proposal.id) { mutableIntStateOf(0) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Connect to this Mac?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(proposal.ticket.displayName?.takeIf { it.isNotBlank() } ?: "cmux")
            Spacer(Modifier.height(12.dp))
            proposal.choices.forEachIndexed { index, choice ->
                Row(Modifier.fillMaxWidth().clickable { selected = index }.testTag("ticket.route.$index"),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == index, onClick = { selected = index })
                    Text(choice.label)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(when {
                proposal.admissions[proposal.choices[selected]]?.savedGrant != null ->
                    "cmux will use this Mac’s previously authorized Tailscale address and saved connection settings."
                proposal.choices[selected].pairing is PairingCode.Tailscale ->
                    "cmux will send your account session to this Mac over Tailscale. Continue only if this address came from your Mac."
                else -> "cmux will verify this Mac in your account and selected team before connecting."
            })
            if (proposal.ticket.context().isExpired(System.currentTimeMillis())) {
                Spacer(Modifier.height(8.dp))
                Text("This ticket has expired. cmux will use your account to reconnect; its expired token will not be sent.")
            }
        } },
        confirmButton = { TextButton(onClick = { onConnect(proposal.choices[selected]) },
            modifier = Modifier.testTag("ticket.connect")) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
