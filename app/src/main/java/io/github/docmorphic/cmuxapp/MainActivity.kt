package io.github.docmorphic.cmuxapp

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*

class MainActivity : ComponentActivity() {
    private var incomingPairing by mutableStateOf<String?>(null)
    private var incomingNotificationRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingPairing = intent?.dataString?.takeIf { PairingCodeParser.parse(it).isSuccess }
        incomingNotificationRoute = if (savedInstanceState != null) savedInstanceState.getString("notification_route")
            else NativeNotificationDelivery.routeFromIntent(this, intent)
        if (NativeNotificationService.isEnabled(this)) {
            runCatching { startForegroundService(Intent(this, NativeNotificationService::class.java)) }
        }
        setContent {
            CmuxTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var nativeMode by remember { mutableStateOf(true) }
                    LaunchedEffect(incomingPairing, incomingNotificationRoute) {
                        if (incomingPairing != null || incomingNotificationRoute != null) nativeMode = true
                    }
                    if (nativeMode) NativeScreen(onUseHelper = { nativeMode = false },
                        incomingCode = incomingPairing, incomingNotificationRoute = incomingNotificationRoute,
                        onNotificationHandled = { route ->
                            if (incomingNotificationRoute == route) {
                                incomingNotificationRoute = null
                                intent?.data = null
                            }
                        })
                    else BridgeScreen(onUseNative = { nativeMode = true })
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("notification_route", incomingNotificationRoute)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingPairing = intent.dataString?.takeIf { PairingCodeParser.parse(it).isSuccess }
        incomingNotificationRoute = NativeNotificationDelivery.routeFromIntent(this, intent)
    }
}

@Composable
fun CmuxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF76B9FF),
        background = Color(0xFF0B0C0E),
        surface = Color(0xFF0B0C0E),
        onBackground = Color(0xFFF4F5F7),
        onSurface = Color(0xFFF4F5F7)
    ), content = content)
}
