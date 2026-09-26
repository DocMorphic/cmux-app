package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val page = Color(0xFF0B0C0E)
private val panel = Color(0xFF191B1F)
private val line = Color(0xFF292C31)
private val muted = Color(0xFF969AA3)
private val blue = Color(0xFF76B9FF)

@Composable
fun BridgeScreen(onUseNative: () -> Unit = {}) {
    val context = LocalContext.current
    val pairingStore = remember(context) { BridgePairingStore(context.applicationContext) }
    var pairingText by remember { mutableStateOf("") }
    var client by remember { mutableStateOf(pairingStore.load()?.let(::BridgeClient)) }
    var workspaces by remember { mutableStateOf<List<BridgeWorkspace>>(emptyList()) }
    var notifications by remember { mutableStateOf<List<BridgeNotification>>(emptyList()) }
    var notificationTab by remember { mutableStateOf(false) }
    var selectedWorkspace by remember { mutableStateOf<String?>(null) }
    var selectedSurface by remember { mutableStateOf<String?>(null) }
    var terminalText by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scanner = remember(context) {
        GmsBarcodeScanning.getClient(
            context,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
        )
    }

    suspend fun refresh(active: BridgeClient) {
        loading = true
        runCatching { active.workspaces() }.fold(
            onSuccess = { workspaces = it; error = null },
            onFailure = { error = it.message ?: "Could not reach your Mac" }
        )
        runCatching { active.notifications() }.fold(
            onSuccess = { notifications = it },
            onFailure = { if (notificationTab) error = it.message ?: "Could not load notifications" }
        )
        loading = false
    }

    LaunchedEffect(client, selectedSurface) {
        val active = client ?: return@LaunchedEffect
        while (selectedSurface == null) {
            refresh(active)
            delay(3_000)
        }
    }
    LaunchedEffect(client, selectedSurface) {
        val active = client ?: return@LaunchedEffect
        val surface = selectedSurface ?: return@LaunchedEffect
        terminalText = ""
        while (true) {
            runCatching { active.screen(surface) }.fold(
                onSuccess = { terminalText = it; error = null },
                onFailure = { error = it.message ?: "Terminal disconnected" }
            )
            delay(1_000)
        }
    }
    BackHandler(enabled = selectedSurface != null) {
        selectedSurface = null
        selectedWorkspace = null
    }

    Column(Modifier.fillMaxSize().background(page).statusBarsPadding().navigationBarsPadding().imePadding()) {
        when {
            client == null -> PairView(
                pairingText = pairingText,
                onPairingChange = { pairingText = it; error = null },
                onConnect = {
                    runCatching { BridgePairing.parse(pairingText).also(pairingStore::save) }.fold(
                        onSuccess = { client = BridgeClient(it); pairingText = "" },
                        onFailure = { error = it.message ?: "Invalid pairing link" }
                    )
                },
                onScan = {
                    scanner.startScan()
                        .addOnSuccessListener { barcode ->
                            pairingText = barcode.rawValue.orEmpty()
                            error = if (pairingText.isBlank()) "The QR code was empty" else null
                        }
                        .addOnFailureListener { error = it.message ?: "Could not scan QR code" }
                },
                onUseNative = onUseNative,
                error = error
            )
            selectedSurface == null -> WorkspaceView(
                workspaces = workspaces,
                notifications = notifications,
                notificationTab = notificationTab,
                onTabChange = { notificationTab = it; error = null },
                search = search,
                onSearchChange = { search = it },
                loading = loading,
                error = error,
                onRefresh = { client?.let { active -> scope.launch { refresh(active) } } },
                onDisconnect = {
                    pairingStore.clear()
                    client = null
                    workspaces = emptyList()
                    notifications = emptyList()
                    notificationTab = false
                    selectedWorkspace = null
                    selectedSurface = null
                    error = null
                },
                onUseNative = onUseNative,
                onOpen = { workspace, terminal ->
                    selectedWorkspace = workspace.id
                    selectedSurface = terminal.id
                },
                onOpenNotification = { notification ->
                    client?.let { active ->
                        scope.launch {
                            runCatching { active.markNotificationRead(notification.id) }
                                .onFailure { error = it.message ?: "Could not mark notification read" }
                            runCatching { active.notifications() }.onSuccess { notifications = it }
                        }
                    }
                    val workspace = workspaces.firstOrNull { it.id == notification.workspaceId }
                    val terminal = workspace?.terminals?.firstOrNull { it.id == notification.surfaceId }
                        ?: workspace?.terminals?.firstOrNull()
                    if (workspace != null && terminal != null) {
                        selectedWorkspace = workspace.id
                        selectedSurface = terminal.id
                    }
                }
            )
            else -> {
                val workspace = workspaces.firstOrNull { it.id == selectedWorkspace }
                val surface = selectedSurface.orEmpty()
                val terminal = workspace?.terminals?.firstOrNull { it.id == surface }
                TerminalView(
                    title = terminal?.title?.ifBlank { workspace?.title ?: "Terminal" } ?: workspace?.title ?: "Terminal",
                    workspace = workspace,
                    selectedSurface = surface,
                    output = terminalText,
                    input = input,
                    error = error,
                    onInputChange = { input = it },
                    onBack = { selectedSurface = null; selectedWorkspace = null },
                    onSelectSurface = { selectedSurface = it },
                    onSend = {
                        val active = client
                        val text = input
                        if (active != null && text.isNotBlank()) {
                            input = ""
                            scope.launch {
                                runCatching { active.sendText(surface, text); active.sendKey(surface, "enter") }
                                    .onFailure { error = it.message ?: "Could not send input" }
                            }
                        }
                    },
                    onKey = { key ->
                        client?.let { active ->
                            scope.launch {
                                runCatching { active.sendKey(surface, key) }
                                    .onFailure { error = it.message ?: "Could not send key" }
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PairView(
    pairingText: String,
    onPairingChange: (String) -> Unit,
    onConnect: () -> Unit,
    onScan: () -> Unit,
    onUseNative: () -> Unit,
    error: String?
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.cmux_logo), "cmux logo", Modifier.size(76.dp))
        Spacer(Modifier.height(26.dp))
        Text("Connect to cmux", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Your Mac workspaces, on your phone.", color = muted, fontSize = 16.sp)
        Spacer(Modifier.height(32.dp))
        Text("PAIRING LINK", color = muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pairingText,
            onValueChange = onPairingChange,
            placeholder = { Text("Paste your cmux-app://pair link", color = muted) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = panel,
                unfocusedContainerColor = panel,
                focusedIndicatorColor = blue,
                unfocusedIndicatorColor = line
            )
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onConnect,
            enabled = pairingText.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = blue, contentColor = Color(0xFF0B1722))
        ) { Text("Connect", fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Text("Scan QR code", color = blue)
        }
        TextButton(onClick = onUseNative, modifier = Modifier.fillMaxWidth()) {
            Text("Pair directly with cmux", color = blue)
        }
        if (error != null) {
            Spacer(Modifier.height(14.dp))
            Text(error, color = Color(0xFFFF8D8D), fontSize = 13.sp)
        }
        Spacer(Modifier.height(32.dp))
        Text("Unofficial Android companion · Connects through your private Mac helper and Tailscale", color = muted, fontSize = 12.sp)
    }
}

@Composable
private fun WorkspaceView(
    workspaces: List<BridgeWorkspace>,
    notifications: List<BridgeNotification>,
    notificationTab: Boolean,
    onTabChange: (Boolean) -> Unit,
    search: String,
    onSearchChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onUseNative: () -> Unit,
    onOpen: (BridgeWorkspace, BridgeTerminal) -> Unit,
    onOpenNotification: (BridgeNotification) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.cmux_logo), "cmux", Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text(if (notificationTab) "Notifications" else "Workspaces", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("Refresh", color = blue) }
        }
        if (error != null) {
            Text(error, Modifier.fillMaxWidth().background(Color(0xFF402626)).padding(12.dp), color = Color(0xFFFFA4A4), fontSize = 13.sp)
        }
        if (loading && workspaces.isEmpty()) {
            Text("Connecting to your Mac…", Modifier.padding(24.dp), color = muted)
        }
        if (notificationTab) {
            LazyColumn(Modifier.weight(1f)) {
                items(notifications, key = { it.id }) { notification ->
                    NotificationRow(notification, onOpenNotification)
                }
                if (!loading && notifications.isEmpty()) {
                    item { Text("No notifications yet.", Modifier.padding(24.dp), color = muted) }
                }
            }
        } else {
            val filtered = workspaces.filter {
                it.title.contains(search, ignoreCase = true) ||
                    it.terminals.any { terminal -> terminal.title.contains(search, ignoreCase = true) }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { workspace -> WorkspaceRow(workspace, onOpen) }
                if (!loading && filtered.isEmpty()) {
                    item {
                        Text(
                            if (workspaces.isEmpty()) "No workspaces are open on your Mac." else "No matching workspaces.",
                            Modifier.padding(24.dp), color = muted
                        )
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(page).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (!notificationTab) {
                OutlinedTextField(
                    value = search,
                    onValueChange = onSearchChange,
                    placeholder = { Text("Search workspaces", color = muted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(focusedContainerColor = panel, unfocusedContainerColor = panel)
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onTabChange(false) }) { Text("Workspaces", color = if (!notificationTab) blue else muted) }
                TextButton(onClick = { onTabChange(true) }) {
                    val unread = notifications.count { !it.isRead }
                    Text(if (unread > 0) "Notifications ($unread)" else "Notifications", color = if (notificationTab) blue else muted)
                }
                TextButton(onClick = onUseNative) { Text("Direct cmux", color = blue, fontSize = 12.sp) }
                TextButton(onClick = onDisconnect) { Text("Disconnect", color = muted, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun NotificationRow(notification: BridgeNotification, onOpen: (BridgeNotification) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(notification) }.padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier.size(38.dp).background(if (notification.isRead) line else Color(0xFF173955), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text("●", color = if (notification.isRead) muted else blue, fontSize = 16.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(notification.title.ifBlank { "Notification" }, fontWeight = if (notification.isRead) FontWeight.Normal else FontWeight.SemiBold, maxLines = 2)
            if (notification.subtitle.isNotBlank()) Text(notification.subtitle, color = muted, fontSize = 13.sp, maxLines = 2)
            if (notification.body.isNotBlank()) Text(notification.body, color = muted, fontSize = 13.sp, maxLines = 3)
            notification.createdAt?.let { Text(it, color = muted, fontSize = 11.sp) }
        }
        Text("›", color = muted, fontSize = 24.sp)
    }
    Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(1.dp).background(line))
}

@Composable
private fun WorkspaceRow(workspace: BridgeWorkspace, onOpen: (BridgeWorkspace, BridgeTerminal) -> Unit) {
    val first = workspace.terminals.firstOrNull()
    Row(
        Modifier.fillMaxWidth().clickable(enabled = first != null) { first?.let { onOpen(workspace, it) } }
            .padding(start = 18.dp, end = 16.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).background(Color(0xFF25233C), CircleShape), contentAlignment = Alignment.Center) {
            Text("⌘", color = Color(0xFFB6A5FF), fontSize = 25.sp)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(workspace.title.ifBlank { "Workspace" }, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                when (workspace.terminals.size) {
                    0 -> "No terminal"
                    1 -> first?.title?.ifBlank { "Terminal" } ?: "Terminal"
                    else -> "${workspace.terminals.size} terminals · ${first?.title.orEmpty()}"
                },
                color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text("›", color = muted, fontSize = 27.sp)
    }
    Box(Modifier.fillMaxWidth().padding(start = 74.dp).height(1.dp).background(line))
}

@Composable
private fun TerminalView(
    title: String,
    workspace: BridgeWorkspace?,
    selectedSurface: String,
    output: String,
    input: String,
    error: String?,
    onInputChange: (String) -> Unit,
    onBack: () -> Unit,
    onSelectSurface: (String) -> Unit,
    onSend: () -> Unit,
    onKey: (String) -> Unit
) {
    val scroll = rememberScrollState()
    LaunchedEffect(output) { scroll.animateScrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹  Workspaces", color = blue, fontSize = 15.sp) }
            Spacer(Modifier.weight(1f))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.weight(0.5f))
            Text("▣", color = muted, fontSize = 20.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(line))
        if (workspace != null && workspace.terminals.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                workspace.terminals.forEachIndexed { index, terminal ->
                    Text(
                        terminal.title.ifBlank { "Terminal ${index + 1}" },
                        Modifier.background(if (terminal.id == selectedSurface) Color(0xFF2D3540) else panel, RoundedCornerShape(16.dp))
                            .clickable { onSelectSurface(terminal.id) }.padding(horizontal = 12.dp, vertical = 7.dp),
                        color = if (terminal.id == selectedSurface) blue else muted,
                        fontSize = 12.sp
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF111316))) {
            SelectionContainer {
                Text(
                    output.ifBlank { "Waiting for terminal output…" },
                    Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp),
                    color = Color(0xFFE0E5EB),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        if (error != null) Text(error, Modifier.fillMaxWidth().background(Color(0xFF402626)).padding(8.dp), color = Color(0xFFFFA4A4), fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(panel).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Esc" to "escape", "Tab" to "tab", "Ctrl+C" to "ctrl+c", "↑" to "up", "↓" to "down", "←" to "left", "→" to "right").forEach { (label, key) ->
                TextButton(onClick = { onKey(key) }) { Text(label, color = Color(0xFFE1E4E8), fontSize = 13.sp) }
            }
        }
        Row(Modifier.fillMaxWidth().background(panel).padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                placeholder = { Text("Message", color = muted) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(26.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF25282D), unfocusedContainerColor = Color(0xFF25282D))
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onSend, enabled = input.isNotBlank()) {
                Text("↑", color = if (input.isNotBlank()) blue else muted, fontSize = 24.sp)
            }
        }
    }
}
