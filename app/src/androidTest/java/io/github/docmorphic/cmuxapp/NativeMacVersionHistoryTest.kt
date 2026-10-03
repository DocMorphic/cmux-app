package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NativeMacVersionHistoryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val team = NativeTeamScope("fixture-login", "fixture-user", "fixture-team", 1)
    private val identity = NativeMacIdentity("fixture-mac", "default")
    private fun setup(store: NativeCredentialStore): NativeCredentialStore.PairedMac {
        val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
            "cmux-ios://attach?v=2&r=100.64.0.1:58465", identity.deviceId, "Studio", identity.buildTag), team)
        store.update { it.put("task_session", team.login).put("refresh_token", "fixture-token")
            .put("pairings", JSONArray(listOf(NativePairingRecords.encode(mac)))) }
        return mac
    }

    @Test fun encryptedReloadRestoresWarningForHiddenComputerWithoutRewritingPairingOrRevivingSignOut() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "mac_version_fixture_${UUID.randomUUID()}"
        val store = NativeCredentialStore(context, name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val mac = setup(store)
            val pairingBytes = store.load()!!.getJSONArray("pairings").toString()
            store.recordMacVersions(team, { true }) { mapOf(identity to "0.64.24") }
            store.setComputerVisible(team.login, mac, false) { true }
            val restarted = NativeCredentialStore(context, name)
            val restoredOwner = team.copy(generation = 2)
            val teams = MutableStateFlow(NativeAccountTeamsState(scope = restoredOwner))
            val isCurrent = { owner: NativeTeamScope -> teams.value.scope == owner && restarted.taskSession() == owner.login }
            val gate = NativeMacCompatibilityGate(isCurrent)
            scope.observeMacVersionHistory(restarted, teams, isCurrent, gate)
            runBlocking { withTimeout(3000) { gate.warnings.first { it.isNotEmpty() } } }
            assertEquals(pairingBytes, restarted.load()!!.getJSONArray("pairings").toString())
            assertTrue(restarted.visiblePairedMacs().isEmpty())
            assertTrue(gate.observations.value.isEmpty())
            val disk = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).getString("state", "")!!
            assertFalse(disk.contains("0.64.24")); assertFalse(disk.contains("fixture-mac"))
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
                val warnings by gate.warnings.collectAsState()
                CompositionLocalProvider(LocalMacCompatibilityWarnings provides warnings.mapKeys { it.key.identity }) {
                    Column(Modifier.statusBarsPadding().padding(22.dp)) {
                        NativeHiddenComputerRows(listOf(mac), NativeMacAppearances(), emptyMap()) { _, _ -> }
                    }
                }
            } } }
            compose.onNodeWithText("Hidden Computers").assertIsDisplayed()
            compose.onNodeWithText("Mac update required").performClick()
            compose.onNode(hasText("Mac reports 0.64.24", substring = true)).assertIsDisplayed()
            capture("restored-hidden-warning")
            compose.onNodeWithText("Done").performClick()
            restarted.forgetMac(mac.code)
            runBlocking { withTimeout(3000) { gate.warnings.first { it.isEmpty() } } }
            compose.onNodeWithText("Mac update required").assertDoesNotExist()
            assertFalse(restarted.load()!!.has(NativeMacVersionHistory.KEY))
            restarted.clear()
            restarted.recordMacVersions(restoredOwner, { true }) { error("Signed out account must not request observations") }
            assertNull(restarted.load())
        } finally { scope.cancel(); store.clear(); context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun delayedSaveReadsLatestObservationInsideAccountTransactionAndDoesNotChurnUnchangedState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "mac_version_race_${UUID.randomUUID()}"; val store = NativeCredentialStore(context, name)
        try {
            setup(store)
            val latest = AtomicReference("0.64.24"); val started = CountDownLatch(1)
            val save = FutureTask {
                started.countDown()
                store.recordMacVersions(team, { true }) { mapOf(identity to latest.get()) }
            }
            synchronized(store.accountStateLock) {
                Thread(save, "version-history-fixture").start()
                assertTrue(started.await(2, TimeUnit.SECONDS))
                latest.set("0.65.0")
            }
            save.get(2, TimeUnit.SECONDS)
            assertEquals("0.65.0", NativeMacVersionHistory.read(store.load(), team)[identity])
            val revision = store.revisions.value
            store.recordMacVersions(team, { true }) { mapOf(identity to "0.65.0") }
            assertEquals(revision, store.revisions.value)
            store.recordMacVersions(team, { false }) { error("Retired owner must not read observations") }
            assertEquals(revision, store.revisions.value)
        } finally { store.clear(); context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun firstPairingSavedAfterHandshakeBackfillsHistoryAndTeamSwitchRetiresWarnings() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "mac_version_binding_${UUID.randomUUID()}"; val store = NativeCredentialStore(context, name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val isCurrent = { owner: NativeTeamScope -> owner == teams.value.scope && store.taskSession() == owner.login }
        val gate = NativeMacCompatibilityGate(isCurrent)
        try {
            store.update { it.put("task_session", team.login).put("refresh_token", "fixture-token") }
            scope.observeMacVersionHistory(store, teams, isCurrent, gate)
            val wire = object : MobileRpcTransport {
                override suspend fun connect() {}
                override suspend fun read(): ByteArray? = awaitCancellation()
                override suspend fun write(bytes: ByteArray) = error("History restoration must never send RPC")
                override fun close() {}
            }
            MobileRpcClient(wire, { error("History restoration must never request a token") }).use { client ->
                client.connect()
                gate.admit(team, client, JSONObject().put("mac_device_id", identity.deviceId)
                    .put("mac_instance_tag", identity.buildTag).put("mac_app_version", "0.64.25"))
            }
            assertTrue(NativeMacVersionHistory.read(store.load(), team).isEmpty())
            setup(store)
            withTimeout(3000) { store.revisions.first { NativeMacVersionHistory.read(store.load(), team)[identity] == "0.64.25" } }
            gate.replace(checkNotNull(NativeMacCompatibilityPolicy.decode(
                """{"entries":[{"minIOSVersion":"1.0.6","stableMinVersion":"0.65.0"}]}""")))
            assertEquals(1, gate.warnings.value.size)
            val other = team.copy(teamId = "another-team", generation = 2)
            teams.value = NativeAccountTeamsState(scope = other)
            withTimeout(3000) { gate.warnings.first { it.isEmpty() } }
            assertTrue(NativeMacVersionHistory.read(store.load(), other).isEmpty())
            assertEquals("0.64.25", NativeMacVersionHistory.read(store.load(), team)[identity])
        } finally {
            scope.cancel(); store.clear()
            context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "mac-version-history").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
