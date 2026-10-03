package io.github.docmorphic.cmuxapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

@Composable
internal fun SshKeyInstallSection(key: SshKeyRecord, enabled: Boolean, installing: Boolean,
    message: String?, succeeded: Boolean, onInstall: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    Text("Set Up This Key on the Server", style = MaterialTheme.typography.titleMedium)
    SelectionContainer { Text(key.publicKey.openSsh, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    Row {
        TextButton(onClick = { context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("SSH public key", key.publicKey.openSsh)) }) { Text("Copy Public Key") }
        TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, key.publicKey.openSsh)
        }, "Share Public Key")) }) { Text("Share") }
    }
    TextButton(onClick = onInstall, enabled = enabled, modifier = Modifier.testTag("ssh.key.install")) {
        Text(if (installing) "Installing…" else "Install with Password…")
    }
    if (installing) {
        CircularProgressIndicator()
        TextButton(onClick = onCancel) { Text("Cancel Installation") }
    }
    message?.let { Text(it, modifier = Modifier.testTag(if (succeeded) "ssh.key.install.success" else "ssh.key.install.failure"),
        color = if (succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
    Text("Add this public key to ~/.ssh/authorized_keys on the computer, or install it with your password once. " +
        "The password is never saved. Some servers disable password login; use the manual method if needed.",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun SshInstallPasswordDialog(target: SshHostRecord, onDismiss: () -> Unit, onInstall: (ByteArray) -> Unit) {
    // Deliberately not saveable: rotation, dismissal and submit discard the field.
    var password by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { password = ""; onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Install Key with Password") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Enter the password for ${target.endpoint.username}@${target.endpoint.host} one time. " +
                "cmux adds this phone's public key to ~/.ssh/authorized_keys, then verifies a key-only login.")
            OutlinedTextField(password, { if (it.length <= 4096) password = it }, singleLine = true,
                label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.testTag("ssh.key.install.password"))
        } }, confirmButton = { TextButton(onClick = {
            val secret = password.toByteArray(); password = ""; onInstall(secret)
        }, enabled = password.isNotEmpty(), modifier = Modifier.testTag("ssh.key.install.submit")) { Text("Install") } },
        dismissButton = { TextButton(onClick = { password = ""; onDismiss() }) { Text("Cancel") } })
}
