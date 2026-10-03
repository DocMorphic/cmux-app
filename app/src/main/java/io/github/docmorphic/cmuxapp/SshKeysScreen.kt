package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID

private val sshMuted = Color(0xFF9B9FA8)
private const val KEY_TEXT_LIMIT = 64 * 1024

@Composable
internal fun NativeSshKeysRoute(store: NativeCredentialStore, login: String?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val revision by store.revisions.collectAsState()
    val admitted = remember(revision, login) { login != null && store.taskSession() == login }
    var vault by remember(login) { mutableStateOf<SshKeyVault?>(null) }
    var failure by remember(login) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(login, admitted, retry) {
        if (!admitted) { vault = null; return@LaunchedEffect }
        try {
            val loaded = withContext(Dispatchers.IO) { SshKeyVault.get(context) }
            if (store.taskSession() == login) { vault = loaded; failure = null }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failure = "Could not open saved SSH keys. Your saved keys have been retained."
        }
    }
    when {
        !admitted -> Column { SshKeyHeader("SSH Keys", onBack); Text("Sign in to manage SSH keys.", Modifier.padding(22.dp)) }
        vault != null -> key(login) { SshKeysScreen(vault!!, { store.taskSession() == login }, onBack) }
        else -> Column {
            SshKeyHeader("SSH Keys", onBack)
            if (failure == null) CircularProgressIndicator(Modifier.padding(22.dp)) else {
                Text(failure!!, Modifier.padding(22.dp))
                TextButton(onClick = { failure = null; retry++ }) { Text("Try again") }
            }
        }
    }
}

@Composable
internal fun SshKeysScreen(vault: SshKeyVault, permits: () -> Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keys by vault.state.collectAsState()
    var page by rememberSaveable { mutableStateOf("list") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameLabel by rememberSaveable { mutableStateOf("") }
    fun back() { if (!busy) { failure = null; if (page == "list") onBack() else page = "list" } }
    BackHandler { back() }
    fun mutate(action: () -> Unit, success: () -> Unit, cleanup: () -> Unit = {}) {
        if (busy) return
        busy = true; failure = null
        val task = scope.launch {
            try {
                withContext(Dispatchers.IO) { check(permits()) { "Sign in to manage SSH keys" }; action() }
                if (permits()) success()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failure = sshKeyError(error)
            } finally { busy = false }
        }
        // Also erase snapshots if the screen's scope was canceled before the
        // coroutine got a chance to enter its body.
        task.invokeOnCompletion { cleanup() }
    }
    if (page == "generate" || page == "import") {
        SshKeyForm(importing = page == "import", busy = busy, failure = failure, permits = permits,
            onBack = ::back, onError = { failure = it }, onSave = { label, biometric, keyBytes, passBytes ->
                if (busy) { keyBytes?.fill(0); passBytes?.fill(0) }
                else mutate({
                    if (keyBytes == null) vault.generate(label, biometric) else vault.import(label, keyBytes, passBytes)
                }, { page = "list" }, { keyBytes?.fill(0); passBytes?.fill(0) })
            })
        return
    }
    Column(Modifier.fillMaxSize().testTag("ssh.keys")) {
        SshKeyHeader("SSH Keys", ::back, !busy)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (keys.isEmpty()) item { Text("No keys yet. Generate a key on this phone or import one you already use.",
                color = sshMuted, modifier = Modifier.testTag("ssh.keys.empty")) }
            items(keys, key = { it.id }) { record ->
                SshKeyRow(record, enabled = !busy && permits(), onCopy = {
                    if (permits()) context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("SSH public key", record.publicKey.openSsh))
                }, onShare = {
                    if (permits()) context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, record.publicKey.openSsh), "Share public key"))
                }, onRename = { failure = null; renameId = record.id.toString(); renameLabel = record.label },
                    onDelete = { failure = null; deleteId = record.id.toString() })
            }
            item { Text("Private keys stay on this phone. Share only the public key with a server.", color = sshMuted, fontSize = 13.sp) }
            item { TextButton(onClick = { page = "generate"; failure = null }, enabled = !busy && permits(),
                modifier = Modifier.fillMaxWidth().testTag("ssh.keys.generate")) { Text("Generate New Key") } }
            item { TextButton(onClick = { page = "import"; failure = null }, enabled = !busy && permits(),
                modifier = Modifier.fillMaxWidth().testTag("ssh.keys.import")) { Text("Import Key") } }
            failure?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ssh.keys.error")) } }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
    }
    deleteId?.let { raw ->
        AlertDialog(onDismissRequest = { if (!busy) deleteId = null }, title = { Text("Delete this key?") },
            text = { Column {
                Text("Computers that use this key will need another key to log in. This can't be undone.")
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !busy, modifier = Modifier.testTag("ssh.keys.delete.confirm"), onClick = {
                mutate({ vault.delete(UUID.fromString(raw)) }, { deleteId = null })
            }) { Text("Delete") } }, dismissButton = { TextButton(enabled = !busy, onClick = { deleteId = null }) { Text("Cancel") } })
    }
    renameId?.let { raw ->
        AlertDialog(onDismissRequest = { if (!busy) renameId = null }, title = { Text("Rename Key") },
            text = { Column { OutlinedTextField(renameLabel, { if (it.length <= 256) renameLabel = it }, label = { Text("Name") },
                singleLine = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.testTag("ssh.keys.rename.label"))
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !busy && renameLabel.isNotBlank(), onClick = {
                mutate({ vault.rename(UUID.fromString(raw), renameLabel.trim()) }, { renameId = null })
            }, modifier = Modifier.testTag("ssh.keys.rename.confirm")) { Text("Save") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { renameId = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SshKeyHeader(title: String, onBack: () -> Unit, enabled: Boolean = true, action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, enabled = enabled, modifier = Modifier.testTag("ssh.keys.back")) { Text("‹  Back") }
        Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        action()
    }
}

@Composable
private fun SshKeyRow(record: SshKeyRecord, enabled: Boolean, onCopy: () -> Unit, onShare: () -> Unit,
    onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium, color = Color(0xFF191B1F)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(record.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("ssh.key.${record.id}.name"))
                Text(record.publicKey.algorithm + " · " + if (record.kind == SshKeyKind.IMPORTED) "Imported" else
                    "Android Keystore" + if (record.requiresBiometrics) " · Biometric confirmation" else "", color = sshMuted, fontSize = 12.sp)
                SelectionContainer { Text(record.publicKey.sha256Fingerprint, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = sshMuted) }
            }
            Box {
                TextButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier
                    .semantics { contentDescription = "Actions for ${record.label}" }
                    .testTag("ssh.key.${record.id}.actions")) { Text("•••") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf("Copy Public Key" to onCopy, "Share Public Key" to onShare, "Rename" to onRename, "Delete" to onDelete).forEach { (title, action) ->
                        DropdownMenuItem(text = { Text(title) }, onClick = { menu = false; action() })
                    }
                }
            }
        }
    }
}

@Composable
private fun SshKeyForm(importing: Boolean, busy: Boolean, failure: String?, permits: () -> Boolean,
    onBack: () -> Unit, onError: (String) -> Unit, onSave: (String, Boolean, ByteArray?, ByteArray?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var label by rememberSaveable(importing) { mutableStateOf("") }
    var biometric by rememberSaveable(importing) { mutableStateOf(false) }
    // Secret text is intentionally never saved in a Bundle or remembered across recreation.
    var privateText by remember(importing) { mutableStateOf("") }
    var passphrase by remember(importing) { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    if (importing) SshPrivateInputWindow()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && permits()) scope.launch {
            reading = true
            try {
                val loaded = withContext(Dispatchers.IO) { loadSshKeyFile(context, uri) }
                if (permits()) { privateText = loaded.second; if (label.isBlank()) label = loaded.first }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                onError("Could not read this key. Choose an OpenSSH private-key file smaller than 64 KB.")
            } finally { reading = false }
        }
    }
    val blocked = busy || reading || !permits()
    Column(Modifier.fillMaxSize().imePadding()) {
        SshKeyHeader(if (importing) "Import Key" else "Generate Key", onBack, !blocked) {
            TextButton(enabled = !blocked && (!importing || privateText.isNotBlank()), modifier = Modifier.testTag("ssh.keys.save"), onClick = {
                val name = label.trim().ifBlank { if (importing) "Imported Key" else "${Build.MODEL} Key" }
                val bytes = if (importing) privateText.trim().toByteArray() else null
                if (bytes != null && bytes.size >= KEY_TEXT_LIMIT) {
                    bytes.fill(0); onError("Choose an OpenSSH private key smaller than 64 KB.")
                } else {
                    val pass = passphrase.takeIf { it.isNotEmpty() }?.toByteArray()
                    onSave(name, biometric, bytes, pass)
                }
            }) { Text(if (importing) "Import" else "Create") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(label, { if (it.length <= 256) label = it }, label = { Text("Name") },
                placeholder = { Text(if (importing) "Imported Key" else "${Build.MODEL} Key") }, singleLine = true,
                enabled = !blocked, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().testTag("ssh.keys.label"))
            if (importing) {
                OutlinedTextField(privateText, { if (it.length < KEY_TEXT_LIMIT) privateText = it else onError("Choose a key smaller than 64 KB.") },
                    label = { Text("OpenSSH Private Key") }, minLines = 5, maxLines = 9, enabled = !blocked,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth().testTag("ssh.keys.private"))
                TextButton(enabled = !blocked, onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("ssh.keys.file")) { Text("Choose File…") }
                Text("Paste or choose an Ed25519 or ECDSA private key, including its BEGIN and END OPENSSH PRIVATE KEY lines.", color = sshMuted, fontSize = 13.sp)
                OutlinedTextField(passphrase, { if (it.length < KEY_TEXT_LIMIT) passphrase = it }, label = { Text("Passphrase, if required") },
                    singleLine = true, enabled = !blocked, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("ssh.keys.passphrase"))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Require biometric confirmation", Modifier.weight(1f))
                    Switch(biometric, { biometric = it }, enabled = !blocked, modifier = Modifier
                        .semantics { contentDescription = "Require biometric confirmation" }.testTag("ssh.keys.biometric"))
                }
                Text("The private key is created in Android Keystore and cannot be exported. With biometric confirmation on, each connection needs your approval and cannot reconnect unattended.", color = sshMuted, fontSize = 13.sp)
            }
            if (blocked) LinearProgressIndicator(Modifier.fillMaxWidth())
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ssh.keys.error")) }
        }
    }
}

@Composable
private fun SshPrivateInputWindow() {
    var context = LocalContext.current
    while (context is ContextWrapper && context !is Activity) context = context.baseContext
    val window = (context as? Activity)?.window
    DisposableEffect(window) {
        val alreadySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** Bounded before decoding, including for providers that omit or lie about size. */
internal fun readSshKeyText(input: InputStream): String {
    val bytes = ByteArray(KEY_TEXT_LIMIT)
    try {
        var used = 0
        while (used < bytes.size) {
            val count = input.read(bytes, used, bytes.size - used)
            if (count < 0) return bytes.copyOf(used).let { owned ->
                try { owned.decodeToString(throwOnInvalidSequence = true) } finally { owned.fill(0) }
            }
            check(count > 0) { "Could not read the key file" }
            used += count
        }
        error("Choose a key smaller than 64 KB")
    } finally { bytes.fill(0) }
}
private fun loadSshKeyFile(context: Context, uri: Uri): Pair<String, String> {
    val text = checkNotNull(context.contentResolver.openInputStream(uri)).use(::readSshKeyText)
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.')?.take(256) else null
    }.orEmpty()
    return name to text
}
private fun sshKeyError(error: Exception): String = when {
    error.message?.contains("passphrase", ignoreCase = true) == true -> "The passphrase is missing or incorrect. Check it and try again."
    error is IllegalArgumentException -> error.message?.takeIf { it.startsWith("Use an ") } ?: "Check the key name and OpenSSH private-key format. Ed25519 and ECDSA are supported."
    error is java.security.InvalidAlgorithmParameterException -> "Could not create this key. If biometric confirmation is enabled, set up a strong biometric in Android Settings first."
    else -> "Could not save the key. Check that the phone is unlocked and try again."
}
