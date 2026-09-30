package io.github.docmorphic.cmuxapp

import android.os.Bundle
import android.os.Build
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import java.io.File

/** Emulator-only isolated process. The instrumentation process owns the fixture Mac and survives its death. */
class NativeProcessRestoreTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only fixture" }
        super.onCreate(savedInstanceState)
        val port = intent.getIntExtra("fixturePort", 0)
        check(port in 1024..65535)
        File(filesDir, "pane-process-status").writeText("${Process.myPid()}\n${savedInstanceState != null}\ncreated\n")
        setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = androidx.compose.runtime.remember(port) {
                NativeConnector { _, _ -> MobileRpcClient(PairingCode.Route("127.0.0.1", port), { "fixture-token" }).also { it.connect() } }
            })
        } } }
    }
    override fun onStop() {
        super.onStop()
        File(filesDir, "pane-process-stopped").writeText(Process.myPid().toString())
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        File(filesDir, "pane-process-saved").writeText(Process.myPid().toString())
    }
}
