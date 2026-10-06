package io.github.docmorphic.cmuxapp

import android.os.Bundle
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*

open class MainActivity : ComponentActivity() {
    private val routesModel by lazy { androidx.lifecycle.ViewModelProvider(this)[NativeLaunchRoutesViewModel::class.java] }
    private var launchRoutes: NativeLaunchRoutes
        get() = routesModel.routes
        set(value) { routesModel.routes = value }
    internal open fun screenConnector(): NativeConnector? = null
    private fun routes(intent: Intent?) = NativeLaunchRoutes.incoming(intent?.dataString,
        NativeNotificationDelivery.routeFromIntent(this, intent))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        if (!routesModel.initialized) launchRoutes = when {
            savedInstanceState?.containsKey(NativeLaunchRoutes.STATE_KEY) == true ->
                NativeLaunchRoutes.decode(savedInstanceState.getString(NativeLaunchRoutes.STATE_KEY))
            savedInstanceState != null -> NativeLaunchRoutes.incoming(null, savedInstanceState.getString("notification_route"))
            else -> routes(intent)
        }
        routesModel.initialized = true
        if (launchRoutes.pairing?.let { PairingCodeParser.parse(it).isFailure } == true) intent?.data = null
        if (NativeNotificationService.isEnabled(this)) {
            runCatching { startForegroundService(Intent(this, NativeNotificationService::class.java)) }
        }
        lifecycleScope.launch {
            try { PhoneReplyWork.recover(applicationContext) }
            catch (_: Exception) { currentCoroutineContext().ensureActive() }
            try { PhoneFcmWork.recover(applicationContext) }
            catch (_: Exception) { currentCoroutineContext().ensureActive() }
        }
        val suppressNoticeLaunch = BuildConfig.DEBUG && intent?.getBooleanExtra(SUPPRESS_WHATS_NEW_EXTRA, false) == true
        setContent {
            CompositionLocalProvider(LocalSuppressWhatsNewLaunch provides suppressNoticeLaunch) { CmuxTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var nativeMode by remember { mutableStateOf(true) }
                    LaunchedEffect(launchRoutes) {
                        if (launchRoutes.pairing != null || launchRoutes.notification != null) nativeMode = true
                    }
                    if (nativeMode) NativeScreen(onUseHelper = { nativeMode = false },
                        incomingCode = launchRoutes.pairing, incomingNotificationRoute = launchRoutes.notification,
                        connector = screenConnector(),
                        onPairingHandled = { value ->
                            launchRoutes = launchRoutes.handledPairing(value)
                            if (intent?.dataString == value) intent?.data = null
                        },
                        onNotificationHandled = { value ->
                            launchRoutes = launchRoutes.handledNotification(value)
                            if (NativeNotificationDelivery.routeFromIntent(this, intent) == value) intent?.data = null
                        })
                    else BridgeScreen(onUseNative = { nativeMode = true })
                }
            } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(NativeLaunchRoutes.STATE_KEY, launchRoutes.encode())
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val next = routes(intent)
        // Launcher reentry and unrelated intents must not discard a link awaiting sign-in.
        if (next.pairing != null || next.notification != null) launchRoutes = next
        if (next.pairing?.let { PairingCodeParser.parse(it).isFailure } == true) intent.data = null
    }
}

@Composable
fun CmuxTheme(content: @Composable () -> Unit) {
    NativeHapticsProvider {
        MaterialTheme(colorScheme = darkColorScheme(
            primary = Color(0xFF76B9FF),
            background = Color(0xFF0B0C0E),
            surface = Color(0xFF0B0C0E),
            onBackground = Color(0xFFF4F5F7),
            onSurface = Color(0xFFF4F5F7)
        )) { FileSaveHost(content) }
    }
}

/** Unconfirmed bearer-bearing links survive rotation in memory, never process-state serialization. */
internal class NativeLaunchRoutesViewModel : androidx.lifecycle.ViewModel() {
    var initialized = false
    var routes by mutableStateOf(NativeLaunchRoutes())
    override fun onCleared() { routes = NativeLaunchRoutes() }
}
