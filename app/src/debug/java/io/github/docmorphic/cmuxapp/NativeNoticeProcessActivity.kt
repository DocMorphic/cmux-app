package io.github.docmorphic.cmuxapp

import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Cookie
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import java.io.File
import java.util.UUID

/** Emulator-only process host for the production notice renderer, ledger and presentation UI. */
class NativeNoticeProcessActivity : ComponentActivity() {
    private lateinit var center: NativeWhatsNewCenter
    private lateinit var presentation: NativeWhatsNewPresentation
    private lateinit var archive: NativeNoticeArchiveOwner
    private var probe: GeckoSession? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        super.onCreate(savedInstanceState)
        val id = checkNotNull(intent.getStringExtra("fixtureId"))
        check(UUID.fromString(id).toString() == id)
        val port = intent.getIntExtra("fixturePort", 0); check(port in 1024..65535)
        val origin = "http://127.0.0.1:$port"
        val pid = Process.myPid()
        val directory = File(noBackupFilesDir, "notice-process-$id").apply { mkdirs() }
        val store = NativeWhatsNewFileStore(directory)
        val page = WhatsNewPage("web", "Process recovery notice", WhatsNewBody.Web("$origin/page?label=$pid"))
        center = NativeWhatsNewCenter(listOf(page), "0.2.0", WhatsNewChannel.DEV, store, origin)
        val exchange: suspend (String) -> List<Cookie> = {
            File(directory, "exchange-$pid").writeText("started")
            if (intent.getBooleanExtra("holdExchange", false)) awaitCancellation()
            listOf(Cookie.Builder().name("stack-access").value("fixture-$pid")
                .hostOnlyDomain("127.0.0.1").path("/").httpOnly().build())
        }
        presentation = NativeWhatsNewPresentation(center, lifecycleScope) { content, _, dark ->
            NativeNoticeRenderer(this, lifecycleScope, center.webPolicy, (content.body as WhatsNewBody.Web).url,
                dark, NativeWhatsNewWebLoad.LAUNCH_DEADLINE_MS, { !isDestroyed }, exchange).also {
                File(directory, "context-$pid").writeText(field<String>(it, "contextId"))
                val renderer = it
                lifecycleScope.launch {
                    val outcome = renderer.load.outcome()
                    File(directory, "load-$pid").writeText("$outcome ${renderer.diagnostic}")
                }
            }
        }
        archive = NativeNoticeArchiveOwner(this, lifecycleScope)
        fun probeOldContext() {
            val old = checkNotNull(intent.getStringExtra("oldContext"))
            check(old.startsWith("cmux-notice-") && UUID.fromString(old.removePrefix("cmux-notice-")).toString() == old.removePrefix("cmux-notice-"))
            val runtime = field<GeckoRuntime>(NativeNoticeEngine.get(this), "runtime")
            probe = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).contextId(old).build()).also {
                it.open(runtime); it.loadUri("$origin/probe?label=$pid")
            }
        }
        lifecycleScope.launch {
            var previous = ""
            while (isActive) {
                val pages = field<Map<WhatsNewPage, NativeNoticeRenderer>>(archive, "pages")
                val status = pages.values.joinToString("\n") { "${it.load.phase.value} ${it.diagnostic}" }
                if (status.isNotEmpty() && status != previous) {
                    File(directory, "archive-$pid").writeText(status); previous = status
                }
                // Inspect while the actual archive page is still open, after production
                // engine setup; never let last-private-page cleanup explain the result.
                if (intent.hasExtra("oldContext") && probe == null &&
                    pages.values.any { it.load.phase.value == WhatsNewWebPhase.LOADED }) probeOldContext()
                delay(500)
            }
        }
        lifecycleScope.launch { center.refresh { """{"visibleEntryIds":["web"]}""" } }
        setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            val state by center.state.collectAsState()
            var showArchive by remember { mutableStateOf(false) }
            Column(Modifier.safeDrawingPadding()) {
                Text("Notice process $pid")
                Text(if (state.initialRefreshComplete) "Notice refresh complete" else "Refreshing notices")
                Text("Unseen notices: ${state.unseen.size}")
                TextButton(onClick = { showArchive = true }) { Text("Open notice archive") }
            }
            NativeWhatsNewHost(center, presentation, "fixture-$id", true, showArchive, { showArchive = false },
                NativeMacCompatibilityPolicy.baked, archive, { it == "fixture-$id" }, exchange)
        } } }
        File(directory, "pid").writeText(pid.toString())
    }
    override fun onDestroy() {
        probe?.close()
        if (::presentation.isInitialized) presentation.close()
        if (::archive.isInitialized) archive.close()
        if (::center.isInitialized) center.close()
        super.onDestroy()
    }
    private fun <T> field(instance: Any, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
    }
}
