package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.util.UUID

@Composable
internal fun NativeSshComputersRoute(runtime: NativeSshRuntime, onBack: () -> Unit) {
    val state by runtime.state.collectAsState()
    val session = state.resource?.takeIf { it.admitted() }
    when {
        session != null -> key(state.login) { SshComputersScreen(session, onBack) }
        else -> Column(Modifier.padding(22.dp)) {
            BackHandler(onBack = onBack)
            TextButton(onClick = onBack) { Text("Back") }
            Text("SSH Computers", style = MaterialTheme.typography.headlineSmall)
            when {
                state.login == null -> Text("Sign in to manage SSH computers.")
                state.failed -> {
                    Text("Could not open saved SSH computers. Your saved data has been retained.")
                    TextButton(onClick = runtime::retry) { Text("Try again") }
                }
                else -> CircularProgressIndicator()
            }
        }
    }
}

@Composable
internal fun SshComputersScreen(session: NativeSshSession, onBack: () -> Unit) {
    val hosts by session.hosts.state.collectAsState()
    val keys by session.vault.state.collectAsState()
    val statuses by session.connections.statuses.collectAsState()
    val shells by session.shells.state.collectAsState()
    var selectedShell by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTmux by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var keyScreen by rememberSaveable { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun mutate(action: () -> Unit, done: () -> Unit = {}) {
        if (busy) return
        busy = true; failure = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { check(session.admitted()); action() }
                if (session.admitted()) done()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failure = if (error is SshHostEditConflict) error.message else "Could not save this change. Check the selected key and jump host, then try again."
            } finally { busy = false }
        }
    }
    fun connect(id: UUID) {
        scope.launch {
            try { session.connections.open(id) }
            catch (error: Exception) { if (error is CancellationException) throw error /* status is shown on the row */ }
        }
    }
    val editorState = rememberSaveableStateHolder()
    var original by rememberSaveable(editing, stateSaver = SshHostEditSaver) { mutableStateOf(editing?.takeUnless { it == "new" }?.let { id -> hosts.hosts.firstOrNull { it.id.toString() == id } }) }
    val activeShell = shells.firstOrNull { it.id == selectedShell }
    selectedTmux?.let { id ->
        val host = hosts.hosts.firstOrNull { it.id.toString() == id }
        if (host != null) { SshWorkspacesRoute(session, host.id) { selectedTmux = null }; return }
        LaunchedEffect(id) { selectedTmux = null }
    }
    if (activeShell != null) {
        key(activeShell.id) {
            var files by remember { mutableStateOf(false) }
            var browser by remember { mutableStateOf<SshBrowserPresentation?>(null) }
            val shellState by activeShell.state.collectAsState()
            val target = SshWorkspaceTarget.Shell(activeShell.id)
            val layout = SshPickerLayout(listOf(SshPickerSection(0, "Terminals", listOf(SshPickerRow(target, activeShell.title)))))
            fun ownsShell() = session.admitted() && selectedShell == activeShell.id &&
                session.shells.state.value.any { it === activeShell } && session.hosts.state.value.hosts.any { it.id == activeShell.hostId }
            fun canCreate() = ownsShell() && !busy && activeShell.state.value.phase == SshShellPhase.RUNNING
            fun livePicker(): SshPickerPresentation? = if (!ownsShell()) null else
                SshPickerPresentation(layout, !busy && shellState.phase == SshShellPhase.RUNNING, true)
            val picker = livePicker() ?: SshPickerPresentation(layout, false, false)
            fun replaceShell(reconnect: Boolean) {
                if (!ownsShell() || busy || (!reconnect && !canCreate())) return
                busy = true; failure = null
                scope.launch {
                    try {
                        val next = if (reconnect) session.shells.reconnect(activeShell.id) else session.shells.create(activeShell.hostId)
                        // A completed creation stays in inventory if the user has
                        // left this shell while the SSH request was in flight.
                        if (session.admitted() && selectedShell == activeShell.id) selectedShell = next.id
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        currentCoroutineContext().ensureActive()
                        if (ownsShell() && session.connections.statuses.value[activeShell.hostId]?.phase != SshConnectionPhase.IDLE)
                            failure = error.message ?: if (reconnect) "Could not reconnect SSH shell" else "Could not create SSH workspace"
                    } finally { busy = false }
                }
            }
            fun openBrowser() {
                if (!canCreate()) return
                try {
                    browser = SshBrowserPresentation(session.browsers.network(activeShell.hostId), sshBrowserWorkspace(target, activeShell.title))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failure = error.message ?: "Could not open SSH browser"
                }
            }
            if (files) SshFilesSheet(session, activeShell.hostId, activeShell) { files = false }
            browser?.let { presentation ->
                SshBrowserSheet(presentation, sshPicker = picker, sshPickerSource = ::livePicker, onSshCommand = { command ->
                    if (livePicker()?.permits(command) == true && canCreate() && command.operation == SshPickerOperation.WORKSPACE) {
                        browser = null; replaceShell(false)
                    }
                }, onRoute = { route ->
                    if (ownsShell() && route.workspaceId == presentation.workspace.id && route.browserId == null &&
                        route.terminalId?.let(SshWorkspaceTarget::decode) == target) browser = null
                }) { browser = null }
            }
            SshShellScreen(activeShell, reconnecting = busy, reconnectError = failure, onFiles = { files = true },
                onReconnect = { replaceShell(true) }, onBrowser = ::openBrowser,
                panePicker = { onText ->
                    SshPanePicker(activeShell.title, layout, target, picker.enabled, onSelect = {},
                        onNewWorkspace = { replaceShell(false) }, onBrowser = ::openBrowser, onText = onText)
                }) { selectedShell = null; failure = null }
        }
        return
    }
    if (keyScreen) {
        SshKeysScreen(session.vault, session.admitted) { keyScreen = false }
        return
    }
    if (editing != null) {
        if (editing != "new" && original == null) { LaunchedEffect(editing) { editing = null }; return }
        editorState.SaveableStateProvider(editing!!) {
            SshComputerEditor(original, hosts.hosts, keys, busy, failure,
                onBack = { if (!busy) { editing?.let(editorState::removeState); editing = null; failure = null } }, onKeys = { keyScreen = true }, onSave = { row ->
                    mutate({
                        // Vault deletion takes this same lock before removing host
                        // references. Do not reintroduce a just-deleted key reference.
                        synchronized(session.vault) {
                            check(row.keyId == null || session.vault.state.value.any { it.id == row.keyId })
                            session.hosts.saveEdit(original, row)
                        }
                    }) {
                        editing?.let(editorState::removeState); editing = null
                        if (original?.connectsLike(row) != true || statuses[row.id]?.phase != SshConnectionPhase.CONNECTED) connect(row.id)
                    }
                }, onInstall = { row, password ->
                    try {
                        withContext(Dispatchers.IO) {
                            synchronized(session.vault) {
                                check(session.isOpen && session.vault.state.value.any { it.id == row.keyId }) { "SSH key unavailable" }
                                session.hosts.saveEdit(original, row)
                            }
                        }
                        original = row
                        try { session.installKey(row, password) }
                        finally {
                            // Installation explicitly resumes a paused route; only
                            // adopt that known change, never a concurrent edit.
                            val current = session.hosts.state.value.host(row.id)
                            if (current == row.copy(autoConnectPaused = false)) original = current
                        }
                    } finally { password.fill(0) }
                })
        }
        return
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().testTag("ssh.computers")) {
        Row(Modifier.fillMaxWidth().padding(14.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("SSH Computers", Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { editing = "new" }, modifier = Modifier.testTag("ssh.computers.add")) { Text("Add") }
        }
        LazyColumn(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (hosts.hosts.isEmpty()) item { Text("Connect to a computer using SSH. Add its address and a key authorized on that computer.") }
            items(hosts.hosts, key = { it.id }) { host ->
                val status = statuses[host.id] ?: SshConnectionStatus(SshConnectionPhase.IDLE)
                Card(Modifier.fillMaxWidth().testTag("ssh.host.${host.id}")) {
                    Column(Modifier.padding(16.dp)) {
                        Text(host.name, style = MaterialTheme.typography.titleMedium)
                        Text("${host.endpoint.username}@${host.endpoint.host}:${host.endpoint.port}")
                        Text(when (status.phase) {
                            SshConnectionPhase.IDLE -> if (host.autoConnectPaused) "Disconnected · automatic connection paused" else "Disconnected"
                            SshConnectionPhase.CONNECTING -> "Connecting…"
                            SshConnectionPhase.CONNECTED -> "Connected"
                            SshConnectionPhase.FAILED -> status.error ?: "Connection failed"
                        }, color = if (status.phase == SshConnectionPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            if (status.phase in listOf(SshConnectionPhase.CONNECTED, SshConnectionPhase.CONNECTING))
                                TextButton(onClick = { mutate({ session.connections.disconnect(host.id) }) }, enabled = !busy) { Text("Disconnect") }
                            else TextButton(onClick = { connect(host.id) }, enabled = !busy,
                                modifier = Modifier.testTag("ssh.host.${host.id}.connect")) { Text("Connect") }
                            TextButton(onClick = { editing = host.id.toString(); failure = null }, enabled = !busy) { Text("Edit") }
                            TextButton(onClick = { deleting = host.id.toString() }, enabled = !busy) { Text("Delete") }
                        }
                        TextButton(onClick = {
                            failure = null
                            scope.launch {
                                try { selectedShell = session.shells.create(host.id).id }
                                catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    failure = error.message ?: "Could not open SSH shell"
                                }
                            }
                        }, enabled = !busy, modifier = Modifier.testTag("ssh.host.${host.id}.shell")) { Text("New Shell") }
                        TextButton(onClick = { selectedTmux = host.id.toString() }, enabled = !busy,
                            modifier = Modifier.testTag("ssh.host.${host.id}.tmux")) { Text("Workspaces") }
                        shells.filter { it.hostId == host.id }.forEach { shell ->
                            val shellState by shell.state.collectAsState()
                            Row(Modifier.fillMaxWidth()) {
                                TextButton(onClick = { selectedShell = shell.id }, modifier = Modifier.weight(1f).testTag("ssh.shell.${shell.id}.open")) {
                                    Text(shell.title + if (shellState.phase == SshShellPhase.ENDED) " · Ended" else "")
                                }
                                TextButton(onClick = { session.shells.remove(shell.id) }) { Text("Close") }
                            }
                        }
                    }
                }
            }
            failure?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { TextButton(onClick = { keyScreen = true }) { Text("SSH Keys") } }
        }
    }
    hosts.hosts.firstOrNull { it.id.toString() == deleting }?.let { host ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("Delete ${host.name}?") },
            text = { Text("This removes the saved computer and disconnects its SSH sessions. Computers using it as a jump host will lose that route.") },
            confirmButton = { TextButton(onClick = { mutate({ session.hosts.delete(host.id) }) { deleting = null } }, enabled = !busy) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }, enabled = !busy) { Text("Cancel") } })
    }
}

@Composable
private fun SshComputerEditor(original: SshHostRecord?, hosts: List<SshHostRecord>, keys: List<SshKeyRecord>,
    busy: Boolean, failure: String?, onBack: () -> Unit, onKeys: () -> Unit, onSave: (SshHostRecord) -> Unit,
    onInstall: suspend (SshHostRecord, ByteArray) -> Unit,
) {
    val draftId = rememberSaveable { (original?.id ?: UUID.randomUUID()).toString() }
    var installing by remember { mutableStateOf(false) }
    // Only the fact that setup was in flight survives recreation, never a password
    // or an instruction to retry a possibly completed remote write.
    var installationPending by rememberSaveable { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf<SshHostRecord?>(null) }
    var installMessage by remember { mutableStateOf<String?>(null) }
    var installSucceeded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var installJob by remember { mutableStateOf<Job?>(null) }
    val blocked = busy || installing
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var address by rememberSaveable { mutableStateOf(original?.endpoint?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf(original?.endpoint?.port?.toString() ?: "22") }
    var username by rememberSaveable { mutableStateOf(original?.endpoint?.username.orEmpty()) }
    var keyId by rememberSaveable { mutableStateOf((original?.keyId ?: if (original == null) keys.firstOrNull()?.id else null)?.toString()) }
    var jumpId by rememberSaveable { mutableStateOf(original?.jumpHostId?.toString()) }
    val endpoint = runCatching { SshEndpoint.fromDraft(address, port, username) }.getOrNull()
    val validKey = keyId == null || keys.any { it.id.toString() == keyId }
    val validJump = jumpId == null || hosts.any { it.id.toString() == jumpId && it.id != original?.id }
    val selectedKey = keys.firstOrNull { it.id.toString() == keyId }
    val valid = endpoint != null && validKey && validJump && name.none { it.isISOControl() }
    fun record(): SshHostRecord {
        val base = original ?: SshHostRecord(id = UUID.fromString(draftId), name = name.trim().ifEmpty { endpoint!!.host }, endpoint = endpoint!!)
        return base.copy(name = name.trim().ifEmpty { endpoint!!.host }, endpoint = endpoint!!,
            keyId = keyId?.let(UUID::fromString), jumpHostId = jumpId?.let(UUID::fromString))
    }
    LaunchedEffect(address, port, username, keyId, jumpId) { installMessage = null; installSucceeded = false }
    askPassword?.let { target -> SshInstallPasswordDialog(target, onDismiss = { askPassword = null }, onInstall = { password ->
        askPassword = null; installing = true; installationPending = true; installMessage = null; installSucceeded = false
        installJob = scope.launch {
            try {
                onInstall(target, password)
                installationPending = false; installSucceeded = true; installMessage = "Key installed. This phone can now log in without a password."
            } catch (error: Exception) {
                if (error is CancellationException) {
                    installMessage = "Installation canceled. The key may already be installed; you can retry explicitly."
                    throw error
                }
                installationPending = false
                installMessage = if (error is SshKeyInstallFailure || error is SshHostEditConflict) error.message
                    else "Could not install the key. Check the selected key and computer, then try again."
            } finally { password.fill(0); installing = false; installJob = null }
        }
    }) }
    BackHandler { if (!blocked) onBack() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp).testTag("ssh.host.editor"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack, enabled = !blocked) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                onSave(record())
            }, enabled = !blocked && valid,
                modifier = Modifier.testTag("ssh.host.save")) { Text("Save") }
        }
        Text(if (original == null) "Add SSH Computer" else "Edit SSH Computer", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, enabled = !blocked, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ssh.host.name"))
        OutlinedTextField(address, { address = it }, label = { Text("Host address") }, enabled = !blocked, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ssh.host.address"))
        OutlinedTextField(port, { port = it }, label = { Text("Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !blocked, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ssh.host.port"))
        OutlinedTextField(username, { username = it }, label = { Text("Username") }, enabled = !blocked, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ssh.host.username"))
        SshChoice("SSH key", keyId, listOf(null to "None") + keys.map { it.id.toString() to it.label }, !blocked) { keyId = it }
        TextButton(onClick = onKeys, enabled = !blocked) { Text("Manage SSH Keys") }
        SshChoice("Jump host", jumpId, listOf(null to "None") + hosts.filter { it.id != original?.id }.map { it.id.toString() to it.name }, !blocked) { jumpId = it }
        selectedKey?.let { key ->
            SshKeyInstallSection(key, enabled = !blocked && valid, installing = installing,
                message = installMessage ?: if (installationPending && !installing)
                    "Previous installation interrupted. The key may already be installed; retry explicitly to verify it." else null,
                succeeded = installSucceeded, onInstall = { askPassword = record() },
                onCancel = { installJob?.cancel() })
        }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) CircularProgressIndicator()
    }
}

@Composable
private fun SshChoice(label: String, selected: String?, values: List<Pair<String?, String>>, enabled: Boolean, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${values.firstOrNull { it.first == selected }?.second ?: "Unavailable — select again"}")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            values.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) }) }
        }
    }
}

/** Retain the editor's base record as well as its fields across state restoration,
 * so restoring an old draft cannot bless a newer route as its comparison base. */
internal val SshHostEditSaver = Saver<SshHostRecord?, List<String>>(
    save = { host -> host?.let { listOf(it.id.toString(), it.name, it.endpoint.host, it.endpoint.port.toString(),
        it.endpoint.username, it.keyId?.toString().orEmpty(), it.jumpHostId?.toString().orEmpty(),
        it.idleClose.name, it.createdAtMillis.toString(), it.autoConnectPaused.toString()) } ?: emptyList() },
    restore = { fields -> if (fields.size != 10) null else runCatching {
        SshHostRecord(UUID.fromString(fields[0]), fields[1], SshEndpoint(fields[2], fields[3].toInt(), fields[4]),
            fields[5].takeIf(String::isNotEmpty)?.let(UUID::fromString), fields[6].takeIf(String::isNotEmpty)?.let(UUID::fromString),
            SshIdleClosePolicy.valueOf(fields[7]), fields[8].toLong(), fields[9].toBooleanStrict())
    }.getOrNull() },
)
