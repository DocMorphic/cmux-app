package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real Keystore/SharedPreferences persistence, in a private fixture namespace on the emulator. */
class WorkspaceTabStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var store: NativeCredentialStore
    private val key = NativeWorkspaceTabKey("fixture-account", "fixture-team", "fixture-mac", "workspace")
    private val terminal = NativeWorkspaceTab(NativeWorkspaceTabKind.TERMINAL, "shell")
    @Before fun setup() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only fixture" }
        name = "workspace-tabs-fixture-${UUID.randomUUID()}"
        store = NativeCredentialStore(context, name)
        store.update { it.put("refresh_token", "fixture").put("task_session", "login") }
    }
    @After fun cleanup() { if (::name.isInitialized) context.deleteSharedPreferences(name) }

    @Test fun encryptedRoundTripAcrossStoreInstancesPreservesKindsAndSkipsUnchangedWrites() {
        NativeWorkspaceTabKind.entries.forEach { kind ->
            val tab = if (kind == NativeWorkspaceTabKind.LOCAL_BROWSER) NativeWorkspaceTab.LocalBrowser else NativeWorkspaceTab(kind, "pane-${kind.wire}")
            val scoped = key.copy(workspaceId = kind.wire)
            assertTrue(store.rememberWorkspaceTab("login", scoped, tab))
            val reopened = NativeCredentialStore(context, name)
            assertEquals(tab, reopened.lastWorkspaceTab("login", scoped))
            val revision = reopened.revisions.value
            assertFalse(reopened.rememberWorkspaceTab("login", scoped, tab))
            assertEquals(revision, reopened.revisions.value)
            assertNull(reopened.lastWorkspaceTab("login", scoped.copy(teamId = "other-team")))
        }
        val stored = context.getSharedPreferences(name, 0).getString("state", null)!!
        assertFalse(stored.contains("workspace_last_tabs")); assertFalse(stored.contains("pane-terminal"))
    }

    @Test fun oldLoginCannotReadOrWriteAfterReplacementAndCannotResurrectSignOut() {
        assertTrue(store.rememberWorkspaceTab("login", key, terminal))
        store.update { it.put("task_session", "new-login") }
        val before = store.revisions.value
        assertNull(store.lastWorkspaceTab("login", key))
        assertFalse(store.rememberWorkspaceTab("login", key, NativeWorkspaceTab.LocalBrowser))
        assertEquals(before, store.revisions.value)
        assertEquals(terminal, store.lastWorkspaceTab("new-login", key))
        store.clear()
        val signedOut = store.revisions.value
        assertFalse(store.rememberWorkspaceTab("new-login", key, terminal))
        assertFalse(store.rememberWorkspaceTab("", key, terminal))
        assertNull(store.load()); assertEquals(signedOut, store.revisions.value)
    }

    @Test fun concurrentStoreInstancesMergeTabsWithoutOverwritingAccountUpdates() {
        val pool = Executors.newFixedThreadPool(3)
        try {
            val jobs = (0 until 12).map { index -> pool.submit {
                val independent = NativeCredentialStore(context, name)
                independent.rememberWorkspaceTab("login", key.copy(workspaceId = "w-$index"), terminal)
                independent.update { it.put("unrelated-$index", true) }
            } }
            jobs.forEach { it.get(15, TimeUnit.SECONDS) }
            val reopened = NativeCredentialStore(context, name)
            repeat(12) { index ->
                assertEquals(terminal, reopened.lastWorkspaceTab("login", key.copy(workspaceId = "w-$index")))
                assertTrue(reopened.load()!!.getBoolean("unrelated-$index"))
            }
            assertEquals("fixture", reopened.load()!!.getString("refresh_token"))
        } finally { pool.shutdownNow() }
    }
}
