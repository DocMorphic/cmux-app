package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NativeForegroundComputerManagementTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val original = NativeTeamScope("fixture-login", "user", "original", 1)
    private val selected = original.copy(teamId = "picker", generation = 2)
    private val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
        "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user", "device", "Original", "default"), original)

    private fun runRemoval(failLocalOnce: Boolean) {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only fixture" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "foreground-management-fixture-" + UUID.randomUUID()
        val store = NativeCredentialStore(context, preferenceName)
        val picker = NativePairingRecords.scoped(mac.copy(name = "Picker"), selected)
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = selected))
        val revokes = AtomicInteger(); val starts = AtomicInteger(); val closes = AtomicInteger()
        var live = true; var completed = false; var appearanceJson: String? = null; var settingsJson: String? = null
        val fail = AtomicBoolean(failLocalOnce)
        val appearance = NativeMacAppearanceStore({ appearanceJson }, { if (fail.getAndSet(false)) error("Fixture local save failed"); appearanceJson = it })
        val settings = NativeMacConnectionStore({ settingsJson }, { settingsJson = it })
        val runtime = NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { owner, current ->
            object : IrohAccountBackend {
                override val state = MutableStateFlow(IrohV2ControlState())
                override suspend fun start() {}
                override suspend fun startManagement() { assertEquals(original, owner); check(current()); starts.incrementAndGet() }
                override suspend fun refresh() {}
                override suspend fun revokeComputer(target: NativeComputerTarget) {
                    check(current()); assertEquals("device", target.deviceId); assertFalse(live); revokes.incrementAndGet()
                }
                override fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport = error("Must never dial a Mac")
                override fun close() { if (owner == original) closes.incrementAndGet() }
            }
        })
        try {
            store.update { it.put("task_session", original.login).put("refresh_token", "fixture").put("draft", "Keep work")
                .put("pairing_code", mac.code).put("computer_selection", picker.origin)
                .put("pairings", JSONArray().put(NativePairingRecords.encode(mac)).put(NativePairingRecords.encode(picker))) }
            val management = NativeForegroundComputerManagement(original, mac, { live }, {
                store.taskSession() == original.login && NativeComputerMenuPairing.isCurrent(mac, store.pairedMacs())
            })
            val flow = nativeForegroundComputerForgetFlow(runtime, management, store, appearance, settings) {
                assertEquals(listOf(mac), it); live = false
            }
            compose.setContent { CmuxTheme { Column {
                NativeComputerForgetSection("Original", "default", flow, enabled = management.available()) { completed = true }
            } } }
            compose.onNodeWithText("Forget This Computer").performClick()
            compose.onNodeWithText("Forget Computer").performClick()
            if (failLocalOnce) {
                compose.waitUntil(10_000) { flow.state.value.remoteConfirmed && !flow.state.value.busy && flow.state.value.error != null }
                assertEquals(listOf(mac, picker), store.pairedMacs()); assertEquals(1, revokes.get())
                compose.onNodeWithText("Retry local cleanup").performClick()
            }
            compose.waitUntil(10_000) { completed }
            assertEquals(listOf(picker), NativeCredentialStore(context, preferenceName).pairedMacs())
            assertEquals("Keep work", store.load()!!.getString("draft")); assertEquals(original.login, store.taskSession())
            assertEquals(picker.origin, store.load()!!.getString("computer_selection"))
            assertEquals(1, starts.get()); assertEquals(1, revokes.get()); assertEquals(1, closes.get())
            assertEquals(selected, teams.value.scope)
        } finally { compose.activity.finish(); runtime.close(); store.clear() }
    }
    @Test fun originalOwnerRevokeAfterRootStopKeepsOtherTeamBindingAndSelection() = runRemoval(false)
    @Test fun confirmedRevokeLocalRetryDoesNotRevokeAgainOrRequireStoppedRoot() = runRemoval(true)
}
