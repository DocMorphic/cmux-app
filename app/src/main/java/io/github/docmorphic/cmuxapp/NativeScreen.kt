package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject

private val nativePage = Color(0xFF0B0C0E)
private val nativePanel = Color(0xFF191B1F)
private val nativeAccent = Color(0xFF76B9FF)
private val nativeMuted = Color(0xFF9B9FA8)

private data class NativeWorkspace(
    val id: String, val title: String, val terminals: List<NativeTerminal>,
    val directory: String?, val hasUnread: Boolean, val lastActivityAt: Double?
)
private data class NativeTerminal(val id: String, val title: String)
private data class NativeNotification(
    val id: String, val workspaceId: String, val surfaceId: String?,
    val title: String, val body: String, val isRead: Boolean
)

@Composable
fun NativeScreen(onUseHelper: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val terminalColumns = (configuration.screenWidthDp / 8).coerceIn(24, 120)
    val terminalRows = ((configuration.screenHeightDp - 210) / 16).coerceIn(10, 60)
    val store = remember(context) { NativeCredentialStore(context.applicationContext) }
    val account = remember(store) { NativeAccount(store) }
    val scanner = remember(context) {
        GmsBarcodeScanning.getClient(context,
            GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build())
    }
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(account.isSignedIn()) }
    var code by remember { mutableStateOf(store.load()?.optString("pairing_code").orEmpty()) }
    var pairingText by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var retryDelay by remember { mutableLongStateOf(2_000) }
    var client by remember { mutableStateOf<MobileRpcClient?>(null) }
    var hostName by remember { mutableStateOf("cmux") }
    var workspaces by remember { mutableStateOf<List<NativeWorkspace>>(emptyList()) }
    var notifications by remember { mutableStateOf<List<NativeNotification>>(emptyList()) }
    var notificationTab by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var selectedWorkspace by remember { mutableStateOf<NativeWorkspace?>(null) }
    var selectedTerminal by remember { mutableStateOf<NativeTerminal?>(null) }
    var input by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf(RenderGrid()) }
    var gridRevision by remember { mutableIntStateOf(0) }

    fun applyFrame(value: JSONObject) {
        val frame = value.optJSONObject("render_grid") ?: value
        if (frame.optString("format") == "cmux.render-grid.v1") {
            if (grid.apply(frame)) gridRevision++
            else scope.launch {
                val active = client ?: return@launch
                val workspace = selectedWorkspace ?: return@launch
                val terminal = selectedTerminal ?: return@launch
                runCatching { active.replay(workspace.id, terminal.id, terminalColumns, terminalRows) }
                    .onSuccess { grid.apply(it.optJSONObject("render_grid") ?: it); gridRevision++ }
                    .onFailure { error = it.message }
            }
        }
    }

    LaunchedEffect(signedIn, code, retry) {
        client?.close(); client = null
        if (!signedIn || code.isBlank()) return@LaunchedEffect
        busy = true
        try {
            val pairing = PairingCodeParser.parse(code).getOrThrow()
            require(pairing is PairingCode.Tailscale) { "This cmux pairing code uses a transport this build cannot connect to yet" }
            pairing.stackUserId?.let { expected ->
                val actual = account.userId()
                require(actual == expected) { "This Mac is signed in to a different cmux account" }
            }
            var connected: MobileRpcClient? = null
            var lastError: Throwable? = null
            for (route in pairing.routes) {
                val candidate = MobileRpcClient(route, account::accessToken)
                try { candidate.connect(); connected = candidate; break }
                catch (failure: Throwable) { lastError = failure; candidate.close() }
            }
            val active = connected ?: throw lastError ?: IllegalStateException("No Tailscale route is reachable")
            try {
                val status = active.hostStatus()
                hostName = status.optString("mac_display_name").ifBlank { "cmux" }
                val listing = active.workspaces()
                workspaces = parseWorkspaces(listing)
                runCatching { active.notifications() }.onSuccess { notifications = parseNotifications(it) }
                client = active
                store.update { it.put("pairing_code", code) }
                error = null
                retryDelay = 2_000
            } catch (failure: Throwable) { active.close(); throw failure }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: "Could not connect to cmux"
            busy = false
            delay(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(30_000)
            retry++
        }
        busy = false
    }

    LaunchedEffect(client) {
        val active = client ?: return@LaunchedEffect
        active.disconnected.collect { failure ->
            error = failure.message ?: "cmux disconnected"
            delay(2_000)
            retry++
        }
    }

    LaunchedEffect(client, selectedTerminal) {
        val active = client ?: return@LaunchedEffect
        if (selectedTerminal != null) return@LaunchedEffect
        while (true) {
            runCatching { active.workspaces() }.onSuccess { workspaces = parseWorkspaces(it); error = null }
                .onFailure { error = it.message }
            runCatching { active.notifications() }.onSuccess { notifications = parseNotifications(it) }
                .onFailure { if (notificationTab) error = it.message }
            delay(5_000)
        }
    }

    LaunchedEffect(client, selectedWorkspace, selectedTerminal, terminalColumns, terminalRows) {
        val active = client ?: return@LaunchedEffect
        val workspace = selectedWorkspace ?: return@LaunchedEffect
        val terminal = selectedTerminal ?: return@LaunchedEffect
        grid = RenderGrid(); gridRevision++
        val eventJob = launch {
            active.events.collect { event ->
                if (event.topic == "terminal.render_grid") {
                    val frame = event.payload.optJSONObject("render_grid") ?: event.payload
                    if (frame.optString("surface_id") == terminal.id) applyFrame(event.payload)
                }
            }
        }
        try {
            active.subscribe(listOf("terminal.render_grid", "workspace.list.changed"))
            applyFrame(active.replay(workspace.id, terminal.id, terminalColumns, terminalRows))
        } catch (failure: Throwable) { error = failure.message ?: "Terminal replay failed" }
        try { eventJob.join() } finally { eventJob.cancel() }
    }

    DisposableEffect(client) {
        val active = client
        onDispose { active?.close() }
    }
    BackHandler(enabled = selectedTerminal != null) { selectedTerminal = null; selectedWorkspace = null }

    Column(Modifier.fillMaxSize().background(nativePage).statusBarsPadding().navigationBarsPadding().imePadding()) {
        when {
            !signedIn -> {
                NativeHeader("Sign in to cmux")
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text("Use the same cmux account as your Mac.", color = nativeMuted)
                    Spacer(Modifier.height(24.dp))
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        scope.launch { busy = true; runCatching { account.sendCode(email) }
                            .onSuccess { codeSent = true; error = null }
                            .onFailure { error = it.message }; busy = false }
                    }, enabled = !busy && email.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Email me a sign-in code") }
                    if (codeSent) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(otp, { otp = it }, Modifier.fillMaxWidth(), label = { Text("Code or link code") })
                        Button(onClick = {
                            scope.launch { busy = true; runCatching { account.signIn(otp) }
                                .onSuccess { signedIn = true; error = null }
                                .onFailure { error = it.message }; busy = false }
                        }, enabled = !busy && otp.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
                    }
                    TextButton(onClick = onUseHelper) { Text("Use existing helper connection") }
                }
            }
            code.isBlank() -> {
                NativeHeader("Pair your Mac")
                Column(Modifier.padding(24.dp)) {
                    Text("Open cmux Mobile Pairing on your Mac and scan its QR code.", color = nativeMuted)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = {
                        scanner.startScan().addOnSuccessListener { barcode ->
                            val scanned = barcode.rawValue.orEmpty()
                            PairingCodeParser.parse(scanned).fold(
                                onSuccess = { code = scanned; error = null },
                                onFailure = { error = it.message }
                            )
                        }.addOnFailureListener { error = it.message }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Scan cmux QR code") }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(pairingText, { pairingText = it }, Modifier.fillMaxWidth(), label = { Text("Or paste pairing code") })
                    Button(onClick = {
                        PairingCodeParser.parse(pairingText).fold(
                            onSuccess = { code = pairingText.trim(); error = null },
                            onFailure = { error = it.message }
                        )
                    }, enabled = pairingText.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Connect") }
                    TextButton(onClick = onUseHelper) { Text("Use existing helper connection") }
                }
            }
            selectedTerminal != null -> {
                val terminal = selectedTerminal!!
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selectedTerminal = null; selectedWorkspace = null }) {
                        Text("‹  ${workspaces.size}", color = nativeAccent)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(terminal.title.ifBlank { selectedWorkspace?.title ?: "Terminal" },
                        Modifier.background(nativePanel, RoundedCornerShape(18.dp))
                            .padding(horizontal = 15.dp, vertical = 7.dp),
                        fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Text("▣", color = nativeMuted, fontSize = 20.sp)
                }
                selectedWorkspace?.terminals?.takeIf { it.size > 1 }?.let { terminals ->
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        terminals.forEach { item ->
                            TextButton(onClick = { selectedTerminal = item }) {
                                Text(item.title.ifBlank { "Terminal" },
                                    color = if (item.id == terminal.id) nativeAccent else nativeMuted,
                                    fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
                val currentGrid = grid
                @Suppress("UNUSED_VARIABLE") val observedRevision = gridRevision
                RenderGridView(currentGrid, Modifier.fillMaxWidth().weight(1f))
                Row(Modifier.horizontalScroll(rememberScrollState()).background(nativePanel)) {
                    listOf("Esc" to "\u001b", "Tab" to "\t", "Ctrl+C" to "\u0003", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C")
                        .forEach { (label, sequence) ->
                            TextButton(onClick = {
                                scope.launch { runCatching { client?.input(selectedWorkspace!!.id, terminal.id, sequence) }
                                    .onFailure { error = it.message } }
                            }) { Text(label) }
                        }
                }
                Row(Modifier.fillMaxWidth().background(nativePanel).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("Terminal input") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            val value = input; input = ""
                            scope.launch { runCatching { client?.input(selectedWorkspace!!.id, terminal.id, "$value\r") }
                                .onFailure { error = it.message } }
                        }))
                    TextButton(onClick = {
                        val value = input; input = ""
                        scope.launch { runCatching { client?.input(selectedWorkspace!!.id, terminal.id, "$value\r") }
                            .onFailure { error = it.message } }
                    }) { Text("Send") }
                }
            }
            else -> {
                Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { error = "Connected to $hostName" }) { Text("◉", color = nativeMuted, fontSize = 23.sp) }
                    Text(if (notificationTab) "Notifications" else "Workspaces", fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { notificationTab = !notificationTab }) {
                        Text(if (notificationTab) "▤" else "☷", color = nativeMuted, fontSize = 21.sp)
                    }
                    TextButton(onClick = {
                        val active = client ?: return@TextButton
                        scope.launch { runCatching { active.request("workspace.create") }
                            .onSuccess { workspaces = parseWorkspaces(it); notificationTab = false; error = null }
                            .onFailure { error = it.message } }
                    }) { Text("+", color = nativeAccent, fontSize = 25.sp) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (client == null && !busy) {
                    Column(Modifier.padding(horizontal = 18.dp)) {
                        Button(onClick = { retryDelay = 2_000; retry++ }) { Text("Retry connection") }
                        TextButton(onClick = { store.update { it.put("pairing_code", "") }; code = "" }) { Text("Pair a different Mac") }
                    }
                }
                if (notificationTab) {
                    LazyColumn(Modifier.weight(1f)) {
                        items(notifications, key = { it.id }) { notification ->
                            Column(Modifier.fillMaxWidth().clickable {
                                val active = client
                                if (active != null) scope.launch {
                                    runCatching { active.markNotificationRead(notification.id) }
                                        .onSuccess { notifications = parseNotifications(active.notifications()) }
                                        .onFailure { error = it.message }
                                }
                                val workspace = workspaces.firstOrNull { it.id == notification.workspaceId }
                                val terminal = workspace?.terminals?.firstOrNull { it.id == notification.surfaceId }
                                    ?: workspace?.terminals?.firstOrNull()
                                if (workspace != null && terminal != null) {
                                    selectedWorkspace = workspace; selectedTerminal = terminal
                                }
                            }.padding(horizontal = 18.dp, vertical = 14.dp)) {
                                Text(notification.title, fontWeight = if (notification.isRead) FontWeight.Normal else FontWeight.SemiBold)
                                if (notification.body.isNotBlank()) Text(notification.body, color = nativeMuted, maxLines = 3)
                            }
                            HorizontalDivider(color = Color(0xFF292C31))
                        }
                        if (notifications.isEmpty()) item { Text("No notifications yet.", Modifier.padding(24.dp), color = nativeMuted) }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(workspaces.filter { it.title.contains(search, true) ||
                            it.directory?.contains(search, true) == true ||
                            it.terminals.any { terminal -> terminal.title.contains(search, true) }
                        }, key = { it.id }) { workspace ->
                            NativeWorkspaceRow(workspace) {
                                workspace.terminals.firstOrNull()?.let { terminal ->
                                    selectedWorkspace = workspace; selectedTerminal = terminal
                                }
                            }
                            HorizontalDivider(color = Color(0xFF292C31))
                        }
                        if (workspaces.isEmpty()) item { Text("No workspaces yet.", Modifier.padding(24.dp), color = nativeMuted) }
                    }
                }
                if (!notificationTab) {
                    OutlinedTextField(search, { search = it },
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                        placeholder = { Text("⌕  Search", color = nativeMuted) },
                        singleLine = true, shape = RoundedCornerShape(28.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { notificationTab = false }) { Text("Workspaces", color = if (notificationTab) nativeMuted else nativeAccent) }
                    TextButton(onClick = { notificationTab = true }) {
                        val unread = notifications.count { !it.isRead }
                        Text(if (unread > 0) "Notifications ($unread)" else "Notifications",
                            color = if (notificationTab) nativeAccent else nativeMuted)
                    }
                    TextButton(onClick = { account.signOut(); signedIn = false; client?.close(); client = null }) {
                        Text("Sign out", color = nativeMuted)
                    }
                }
            }
        }
        if (error != null) Text(error.orEmpty(), Modifier.fillMaxWidth().background(Color(0xFF402626)).padding(12.dp), color = Color(0xFFFFAAAA))
    }
}

@Composable
private fun NativeHeader(title: String) {
    Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.cmux_logo), "cmux", Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun parseWorkspaces(value: JSONObject): List<NativeWorkspace> {
    val array = value.optJSONArray("workspaces") ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val workspace = array.optJSONObject(index) ?: continue
            val id = workspace.optString("id")
            if (id.isBlank()) continue
            val terminals = mutableListOf<NativeTerminal>()
            val items = workspace.optJSONArray("terminals")
            if (items != null) for (terminalIndex in 0 until items.length()) {
                val terminal = items.optJSONObject(terminalIndex) ?: continue
                val terminalId = terminal.optString("id")
                if (terminalId.isNotBlank()) terminals += NativeTerminal(terminalId, terminal.optString("title"))
            }
            add(NativeWorkspace(
                id, workspace.optString("title", "Workspace"), terminals,
                workspace.optString("current_directory").takeIf { it.isNotBlank() && it != "null" },
                workspace.optBoolean("has_unread"),
                workspace.optDouble("last_activity_at").takeIf { it > 0 }
            ))
        }
    }
}

@Composable
private fun NativeWorkspaceRow(workspace: NativeWorkspace, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = workspace.terminals.isNotEmpty(), onClick = onOpen)
        .padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        if (workspace.hasUnread) Text("●", color = nativeAccent, fontSize = 9.sp, modifier = Modifier.width(10.dp))
        else Spacer(Modifier.width(10.dp))
        val colors = listOf(Color(0xFFFFB52E), Color(0xFF58CFA2), Color(0xFF83B9FF), Color(0xFFFF8E80))
        Box(Modifier.size(37.dp).background(colors[(workspace.id.hashCode() and Int.MAX_VALUE) % colors.size], CircleShape),
            contentAlignment = Alignment.Center) { Text("›", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(workspace.title.ifBlank { "Workspace" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(workspace.directory ?: workspace.terminals.firstOrNull()?.title.orEmpty(),
                color = nativeMuted, fontSize = 11.sp, maxLines = 1)
        }
        workspace.lastActivityAt?.let { seconds ->
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date((seconds * 1000).toLong())),
                color = nativeMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text("›", color = nativeMuted, fontSize = 22.sp)
    }
}

private fun parseNotifications(value: JSONObject): List<NativeNotification> {
    val array = value.optJSONArray("notifications") ?: return emptyList()
    return buildList {
        for (index in 0 until minOf(array.length(), 500)) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            add(NativeNotification(
                id, item.optString("workspace_id"),
                item.optString("surface_id").takeIf { it.isNotBlank() && it != "null" },
                item.optString("title").take(512), item.optString("body").take(4096), item.optBoolean("is_read")
            ))
        }
    }
}
