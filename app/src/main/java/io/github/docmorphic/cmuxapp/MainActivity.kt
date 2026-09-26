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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingPairing = intent?.dataString?.takeIf { PairingCodeParser.parse(it).isSuccess }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF76B9FF),
                    background = Color(0xFF0B0C0E),
                    surface = Color(0xFF0B0C0E),
                    onBackground = Color(0xFFF4F5F7),
                    onSurface = Color(0xFFF4F5F7)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var nativeMode by remember { mutableStateOf(true) }
                    LaunchedEffect(incomingPairing) { if (incomingPairing != null) nativeMode = true }
                    if (nativeMode) NativeScreen(onUseHelper = { nativeMode = false }, incomingCode = incomingPairing)
                    else BridgeScreen(onUseNative = { nativeMode = true })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingPairing = intent.dataString?.takeIf { PairingCodeParser.parse(it).isSuccess }
    }
}
