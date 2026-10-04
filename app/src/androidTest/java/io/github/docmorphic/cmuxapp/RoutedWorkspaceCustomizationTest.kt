package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RoutedWorkspaceCustomizationTest {
    @Test fun editorProtocolPreservesEmptyTruncatedAndFailureRebaseValues() {
        val draft = WorkspaceCustomizationDraft("Workspace", "null\n中 🚀", "#12ABEF", true, true)
        assertEquals(draft, RoutedWorkspaceCustomizationProtocol.draft(RoutedWorkspaceCustomizationProtocol.draft(draft)))
        val result = WorkspaceCustomizationResult(false, draft, draft.copy(description = null, color = null), "Save rejected")
        assertEquals(result, RoutedWorkspaceCustomizationProtocol.result(RoutedWorkspaceCustomizationProtocol.result(result)))
        assertEquals(WorkspaceCustomizationResult(true), RoutedWorkspaceCustomizationProtocol.result(
            RoutedWorkspaceCustomizationProtocol.result(WorkspaceCustomizationResult(true))))
    }

    @Test fun duplicateSaveIsRejectedAndEndingBrowserCancelsTheInFlightMutation() = runBlocking {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Disposable emulator required" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val retired = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val network = object : RoutedBrowserNetwork {
            override val storageId = UUID.randomUUID().toString().replace("-", "")
            override val retired = retired
            override suspend fun requiresProxy() = true
            override suspend fun prepare(loopbackPort: Int?) = 1234
        }
        val workspace = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"workspace","title":"Fixture","terminals":[]}]}""")).single()
        val draft = WorkspaceCustomizationDraft.from(workspace)
        val navigation = LocalBrowserNavigation(owner, LocalBrowserStore())
        var entry: RoutedBrowserSessions.Entry? = null
        var calls = 0
        try {
            withContext(Dispatchers.Main) {
                navigation.restoreRemembered(LocalBrowserKey("fixture", null, "computer", workspace.id), workspace)
                val registered = RoutedBrowserSessions.register(context, network, checkNotNull(navigation.state.value.local), workspace, {}, {},
                    customizationEnabled = true, customize = { _, _ ->
                        calls++; entered.complete(Unit)
                        try { awaitCancellation() } finally { cancelled.complete(Unit) }
                    })
                entry = registered
                val first = launch { RoutedBrowserSessions.customize(registered, draft, draft.copy(name = "New name")) }
                withTimeout(5_000) { entered.await() }
                try { RoutedBrowserSessions.customize(registered, draft, draft.copy(name = "Duplicate")); fail("Duplicate save accepted") }
                catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("already in progress")) }
                assertEquals(1, calls)
                RoutedBrowserSessions.abandon(context, registered.id)
                withTimeout(5_000) { cancelled.await(); first.join() }
                assertTrue(first.isCancelled)
                try { RoutedBrowserSessions.customize(registered, draft, draft); fail("Retired browser accepted a save") }
                catch (_: IllegalStateException) { }
                assertEquals(1, calls)
            }
        } finally {
            withContext(Dispatchers.Main) {
                entry?.let { RoutedBrowserSessions.abandon(context, it.id); RoutedBrowserSessions.consume(it.id) }
                navigation.clear(); retired.complete(Unit); owner.cancel()
            }
        }
    }
}
