package io.github.docmorphic.cmuxapp

import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RoutedBrowserMenuRetirementTest {
    @Test fun removedWorkspaceBeforeBrowserAttachmentRemainsRetired() = runBlocking {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Disposable emulator required" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val retirement = CompletableDeferred<Unit>()
        var prepared = false
        val network = object : RoutedBrowserNetwork {
            override val storageId = UUID.randomUUID().toString().replace("-", "")
            override val retired = retirement
            override suspend fun requiresProxy() = true
            override suspend fun prepare(loopbackPort: Int?): Int { prepared = true; return 1234 }
        }
        val workspace = parseWorkspaces(org.json.JSONObject("""{"workspaces":[{"id":"workspace","title":"Fixture","terminals":[]}]}""")).single()
        val navigation = LocalBrowserNavigation(lifetime, LocalBrowserStore())
        var entry: RoutedBrowserSessions.Entry? = null
        var releases = 0
        try {
            withContext(Dispatchers.Main) {
                val key = LocalBrowserKey("fixture", null, "computer", workspace.id)
                navigation.restoreRemembered(key, workspace)
                val registered = RoutedBrowserSessions.register(context, network, checkNotNull(navigation.state.value.local), workspace, { releases++ }, {})
                entry = registered
                RoutedBrowserSessions.refreshMenu(registered.id, null)
                // A restored snapshot must not resurrect this presentation.
                RoutedBrowserSessions.refreshMenu(registered.id, RoutedBrowserMenu(workspace, creationEnabled = true))
                val received = CompletableDeferred<Unit>()
                RoutedBrowserSessions.attach(registered, Messenger(Handler(Looper.getMainLooper()) { message ->
                    if (message.what == RoutedBrowserProtocol.RETIRE) received.complete(Unit)
                    true
                }))
                withTimeout(5_000) { received.await() }
                assertTrue(registered.menuRetired)
                assertFalse(registered.creationEnabled)
                try {
                    RoutedBrowserSessions.prepare(registered, "http://localhost:1234")
                    fail("Retired workspace prepared a browser route")
                } catch (_: IllegalStateException) { }
                assertFalse(prepared)
            }
        } finally {
            withContext(Dispatchers.Main) {
                entry?.let { RoutedBrowserSessions.abandon(context, it.id); RoutedBrowserSessions.consume(it.id) }
                navigation.clear(); retirement.complete(Unit); lifetime.cancel()
            }
        }
        assertEquals(1, releases)
    }
}
