package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.docmorphic.cmuxapp.iroh.IrxConnectionDiagnostics
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeConnectionCheckSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pendingCheckCannotRepeatAndShareUsesDisplayedSafeReport() {
        val gate = CompletableDeferred<NativeConnectionReport>()
        var checks = 0
        var shared: String? = null
        val result = NativeConnectionReport(true, true,
            IrxConnectionDiagnostics(IrxConnectionDiagnostics.Route.PRIVATE_NETWORK, 12), 20)
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding()) {
            NativeConnectionCheckSection(true, { checks++; gate.await() }, { shared = it })
        } } }
        compose.onNodeWithText("Check Connection").performClick()
        compose.onNodeWithText("Checking…").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Share Connection Report").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, checks); gate.complete(result) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Share Connection Report").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("LAN or Private VPN").assertIsDisplayed()
        compose.onNodeWithText("12 ms").assertIsDisplayed()
        capture("connection-check-private-route")
        compose.onNodeWithText("Share Connection Report").performClick()
        compose.runOnIdle { assertEquals(result.shareText(), shared) }
    }

    @Test fun changingConnectionDiscardsLateResultsFromPreviousMac() {
        val oldWire = DiagnosticWire("old-mac", hold = true)
        val newWire = DiagnosticWire("new-mac", hold = false)
        val oldClient = MobileRpcClient(oldWire, { "fixture-token" })
        val newClient = MobileRpcClient(newWire, { "fixture-token" })
        kotlinx.coroutines.runBlocking { oldClient.connect(); newClient.connect() }
        val oldMac = NativeCredentialStore.PairedMac("cmux-ios://attach?v=2&r=100.64.0.1:58465", "old-mac", "Old Mac")
        val newMac = NativeCredentialStore.PairedMac("cmux-ios://attach?v=2&r=100.64.0.2:58465", "new-mac", "New Mac")
        var current by mutableStateOf(oldClient to oldMac)
        try {
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding()) {
                NativeConnectionCheckSettings(current.first, listOf(oldMac, newMac), current.second.code, true)
            } } }
            compose.onNodeWithText("Check Connection").performClick()
            compose.waitUntil(5000) { oldWire.requests.isNotEmpty() }
            compose.runOnIdle { current = newClient to newMac }
            compose.onNodeWithText("Check Connection").assertIsEnabled()
            kotlinx.coroutines.runBlocking { oldWire.answer(oldWire.requests.single()) }
            compose.waitForIdle()
            compose.onNodeWithText("Share Connection Report").assertDoesNotExist()
            compose.onNodeWithText("Active Route").assertDoesNotExist()
            compose.onNodeWithText("Check Connection").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Share Connection Report").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("Verified").assertCountEquals(2)
            assertEquals(listOf("mobile.host.status"), oldWire.requests.map { it.getString("method") })
            assertEquals(listOf("mobile.host.status", "mobile.workspace.list"), newWire.requests.map { it.getString("method") })
        } finally { oldClient.close(); newClient.close() }
    }

    private class DiagnosticWire(val host: String, val hold: Boolean) : MobileRpcTransport {
        val requests = java.util.concurrent.CopyOnWriteArrayList<org.json.JSONObject>()
        val incoming = kotlinx.coroutines.channels.Channel<ByteArray>(8)
        override suspend fun connect() { }
        override suspend fun read() = incoming.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            val request = org.json.JSONObject(MobileFrameDecoder().feed(bytes).single().decodeToString())
            requests += request
            if (!hold) answer(request)
        }
        suspend fun answer(request: org.json.JSONObject) {
            val reply = org.json.JSONObject().put("id", request.getString("id")).put("ok", true)
                .put("result", org.json.JSONObject().put("mac_device_id", host))
            incoming.send(MobileFrameCodec.encode(reply.toString().toByteArray()))
        }
        override fun close() { incoming.close() }
    }

    @Test fun disabledCheckCannotRunAndFailedCheckShowsAction() {
        var enabled by mutableStateOf(false)
        var checks = 0
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding()) {
            NativeConnectionCheckSection(enabled, { checks++; NativeConnectionReport(failure = NativeConnectionReport.Failure.TIMEOUT) }, {})
        } } }
        compose.onNodeWithText("Check Connection").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, checks); enabled = true }
        compose.onNodeWithText("Check Connection").performClick()
        compose.onNodeWithText(NativeConnectionReport.Failure.TIMEOUT.advice).assertIsDisplayed()
        compose.onNodeWithText("Verified (Iroh QUIC)").assertDoesNotExist()
        compose.onNodeWithText("Share Connection Report").assertIsDisplayed()
    }

    private fun capture(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        try { java.io.File(folder, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { image.recycle() }
    }
}
