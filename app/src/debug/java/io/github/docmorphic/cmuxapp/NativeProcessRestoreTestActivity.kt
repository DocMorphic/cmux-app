package io.github.docmorphic.cmuxapp

import android.os.Bundle
import android.os.Build
import android.os.Process
import java.io.File

/** Emulator-only isolated process. The instrumentation process owns the fixture Mac and survives its death. */
class NativeProcessRestoreTestActivity : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only fixture" }
        // The instrumentation process may already own the default WebView directory.
        // Configure this emulator-only process once, before MainActivity creates any WebView.
        if (Build.VERSION.SDK_INT >= 28 && !webViewConfigured) {
            android.webkit.WebView.setDataDirectorySuffix("restore_test")
            webViewConfigured = true
        }
        super.onCreate(savedInstanceState)
        val port = intent.getIntExtra("fixturePort", 0)
        check(port in 1024..65535)
        File(filesDir, "pane-process-status").writeText("${Process.myPid()}\n${savedInstanceState != null}\ncreated\n")
    }
    private companion object { var webViewConfigured = false }

    private val fixture by lazy {
        val port = intent.getIntExtra("fixturePort", 0)
        check(port in 1024..65535)
        NativeConnector { _, _ -> MobileRpcClient(PairingCode.Route("127.0.0.1", port), { "fixture-token" }).also { it.connect() } }
    }
    override fun screenConnector(): NativeConnector = fixture

    override fun onStop() {
        super.onStop()
        File(filesDir, "pane-process-stopped").writeText(Process.myPid().toString())
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        File(filesDir, "pane-process-saved").writeText(Process.myPid().toString())
    }
}
