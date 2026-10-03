package io.github.docmorphic.cmuxapp

import android.app.Application
import android.content.*
import android.os.*
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RoutedBrowserUi(val surface: LocalBrowserSurface? = null,
    val panes: List<NativePanePickerRow> = emptyList(), val error: String? = null,
    val retired: Boolean = false, val restart: Boolean = false, val modes: Boolean = false, val linkedPanel: String? = null, val creationEnabled: Boolean = false,
    val sshPicker: SshPickerPresentation? = null)

internal class RoutedBrowserController(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val mutable = MutableStateFlow(RoutedBrowserUi())
    val state = mutable.asStateFlow()
    private val replies = mutableMapOf<Int, CompletableDeferred<Bundle>>()
    private var ticket = 0
    private var requestId: String? = null
    private var service: Messenger? = null
    private var bound = false
    private var foreground = false
    private var binding: RoutedBrowserBinding? = null
    private val endpoint = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            RoutedBrowserProtocol.RETIRE -> mutable.value = state.value.copy(retired = true)
            RoutedBrowserProtocol.CONTEXT -> mutable.value = state.value.copy(panes = RoutedBrowserProtocol.panes(message.data),
                modes = message.data.getBoolean("modes"), linkedPanel = message.data.getString("linked_panel"), creationEnabled = message.data.getBoolean("creation_enabled"),
                sshPicker = SshPickerPresentation.decode(message.data.getString("ssh_picker")))
            else -> replies.remove(message.arg1)?.complete(Bundle(message.data))
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            viewModelScope.launch {
                try {
                    val response = request(RoutedBrowserProtocol.OPEN)
                    val configured = RoutedBrowserBinding(checkNotNull(response.getString("storage")), response.getInt("port"))
                    RoutedBrowserEnvironment.prepare(app, configured)
                    RoutedBrowserEnvironment.requireReady(configured)
                    binding = configured
                    val surface = LocalBrowserSurface(checkNotNull(response.getString("surface")), response.getString("url"))
                    mutable.value = RoutedBrowserUi(surface, RoutedBrowserProtocol.panes(response), modes = response.getBoolean("modes"), linkedPanel = response.getString("linked_panel"), creationEnabled = response.getBoolean("creation_enabled"),
                        sshPicker = SshPickerPresentation.decode(response.getString("ssh_picker")))
                    publishForeground()
                    surface.state.collect { snapshot ->
                        request(RoutedBrowserProtocol.SNAPSHOT, RoutedBrowserProtocol.snapshot(snapshot))
                    }
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    mutable.value = state.value.copy(error = failure.message ?: "Could not open browser")
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { disconnected() }
        override fun onBindingDied(name: ComponentName) { disconnected() }
        override fun onNullBinding(name: ComponentName) { disconnected() }
    }
    fun begin(id: String) {
        if (requestId != null) { check(requestId == id); return }
        requestId = id
        bound = app.bindService(Intent(app, RoutedBrowserHostService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) disconnected()
    }
    private fun disconnected() {
        service = null
        replies.values.forEach { it.completeExceptionally(IllegalStateException("Browser connection ended")) }; replies.clear()
        mutable.value = state.value.copy(retired = true)
    }
    private suspend fun request(kind: Int, args: Bundle = Bundle()): Bundle {
        val peer = checkNotNull(service) { "Browser connection ended" }
        val serial = ++ticket
        val answer = CompletableDeferred<Bundle>(); replies[serial] = answer
        args.putString(RoutedBrowserProtocol.EXTRA, requestId)
        try {
            peer.send(Message.obtain(null, kind).apply { arg1 = serial; data = args; replyTo = endpoint })
            return withTimeout(20_000) { answer.await() }.also {
                it.getString("failure")?.let { message -> throw IllegalStateException(message) }
            }
        } finally { replies.remove(serial) }
    }
    suspend fun prepare(url: String?) {
        MobileDebugLog.trace(DebugOperation.BROWSER_PREPARE) { preparePage(url) }
    }
    private suspend fun preparePage(url: String?) {
        val ready = checkNotNull(binding)
        val port = request(RoutedBrowserProtocol.PREPARE, Bundle().apply { putString("url", url) }).getInt("port")
        if (port != ready.proxyPort) {
            mutable.value = state.value.copy(restart = true)
            throw CancellationException("Browser proxy restarted")
        }
        RoutedBrowserEnvironment.requireReady(ready)
    }
    fun foreground(active: Boolean) {
        foreground = active
        if (binding != null) viewModelScope.launch { runCatching { publishForeground() } }
    }
    private suspend fun publishForeground() = request(RoutedBrowserProtocol.FOREGROUND, Bundle().apply { putBoolean("active", foreground) })
    suspend fun flush() {
        state.value.surface?.let { request(RoutedBrowserProtocol.SNAPSHOT, RoutedBrowserProtocol.snapshot(it.state.value)) }
        if (binding != null) CookieManager.getInstance().flush()
    }
    suspend fun debugLogs(): String {
        check(BuildConfig.DEBUG)
        val main = request(RoutedBrowserProtocol.DEBUG_LOGS).getString("debug_logs") ?: error("Debug logs unavailable")
        return "$main\n\nBrowser process\n${debugLogSnapshot(app)}"
    }
    override fun onCleared() {
        if (bound) { app.unbindService(connection); bound = false }
        disconnected()
    }
}

/** Credential-free browser presentation; every request uses the main process's bound network. */
class RoutedBrowserActivity : ComponentActivity() {
    private lateinit var controller: RoutedBrowserController
    private var leaving = false
    private fun leave(action: String, pane: NativePanePickerRow? = null, sshCommand: SshPickerCommand? = null) {
        if (leaving) return
        leaving = true
        lifecycleScope.launch {
            withTimeoutOrNull(2_000) { runCatching { controller.flush() } }
            setResult(RESULT_OK, Intent().putExtra(RoutedBrowserProtocol.EXTRA, intent.getStringExtra(RoutedBrowserProtocol.EXTRA))
                .putExtra("action", action).putExtra("kind", pane?.kind).putExtra("pane", pane?.id).putExtra("ssh_command", sshCommand?.encode()))
            finish()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val id = intent.getStringExtra(RoutedBrowserProtocol.EXTRA)
        if (id == null) { finish(); return }
        controller = ViewModelProvider(this)[RoutedBrowserController::class.java]
        controller.begin(id)
        setContent { CmuxTheme { CompositionLocalProvider(LocalDebugLogSource provides { controller.debugLogs() }) { NativeFeedbackHost(id) { Surface(Modifier.fillMaxSize()) {
            val ui by controller.state.collectAsState()
            BackHandler { leave("back") }
            LaunchedEffect(ui.retired, ui.restart) {
                if (ui.retired) leave("retired") else if (ui.restart) leave("restart")
            }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                val page = ui.surface?.state?.collectAsState()?.value
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { leave("back") }, modifier = Modifier.semantics { contentDescription = "Back to workspaces" }) { Text("‹  Workspaces") }
                    val selected = ui.panes.singleOrNull { it.kind == "browser" && it.id == ui.linkedPanel }
                    if (ui.sshPicker != null) Box(Modifier.weight(1f)) {
                        SshBrowserPanePicker(page?.title ?: "Browser", checkNotNull(ui.sshPicker), ui.linkedPanel,
                            onSelect = { leave("pane", it) }, onCommand = { leave("ssh_command", sshCommand = it) })
                    } else NativePanePicker(page?.title ?: "Browser", ui.panes, selected, Modifier.weight(1f),
                        onSelect = { leave("pane", it) },
                        onNewWorkspace = if (ui.creationEnabled) ({ leave("new_workspace") }) else null,
                        onNewTerminal = if (ui.creationEnabled) ({ leave("new_terminal") }) else null,
                        onNewBrowser = if (selected == null) ({}) else if (ui.creationEnabled) ({ leave("new_browser") }) else null, checksNewBrowser = selected == null)
                }
                if (ui.modes) {
                    val target = ui.panes.firstOrNull { it.kind == "browser" && it.id == ui.linkedPanel }
                        ?: ui.panes.firstOrNull { it.kind == "browser" }
                    BrowserModePicker(BrowserMode.ON_DEVICE, if (target == null) "Needs cmux Browser running on the computer" else null) {
                        target?.let { leave("stream", it) }
                    }
                }
                when {
                    ui.error != null -> Column(Modifier.padding(20.dp)) {
                        Text(checkNotNull(ui.error)); TextButton(onClick = { leave("restart") }) { Text("Retry") }
                    }
                    ui.surface == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else -> LocalBrowserPane(checkNotNull(ui.surface), beforeNavigation = controller::prepare) { leave("close") }
                }
            }
        } } } } }
    }
    override fun onStart() { super.onStart(); if (::controller.isInitialized) controller.foreground(true) }
    override fun onStop() { if (::controller.isInitialized) controller.foreground(false); super.onStop() }
    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) Handler(Looper.getMainLooper()).post { Process.killProcess(Process.myPid()) }
    }
}
