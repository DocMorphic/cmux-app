package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeComputerForgetSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun cancelDoesNothingAndBusyConfirmationRejectsDuplicateAndDismissal() {
        val gate = CompletableDeferred<Unit>(); var revokes = 0; var finished = 0
        val flow = NativeComputerForgetFlow({ true }, { emptyList() }, {}, { revokes++; gate.await() }, {})
        compose.setContent { CmuxTheme { Surface {
            NativeComputerForgetSection("Fixture Mac", "default", flow, true) { finished++ }
        } } }
        compose.onNodeWithText("Forget This Computer").performClick()
        compose.onNodeWithText("Forget Fixture Mac?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, revokes) }
        compose.onNodeWithText("Forget This Computer").performClick()
        compose.onNodeWithText("Forget Computer").performClick()
        compose.onNodeWithText("Forgetting…").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Cancel").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, revokes); assertEquals(0, finished) }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent KEYCODE_BACK").close()
        compose.onNodeWithText("Forget Fixture Mac?").assertIsDisplayed()
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitUntil(5000) { finished == 1 }
        compose.onNodeWithText("Forget Fixture Mac?").assertDoesNotExist()
    }

    @Test fun localFailureCanCancelReopenAndRetryWithoutAnotherRevocation() {
        var revokes = 0; var cleanups = 0; var finished = 0
        val flow = NativeComputerForgetFlow({ true }, { emptyList() }, {}, { revokes++ }, {
            if (++cleanups == 1) error("fixture disk failure")
        })
        compose.setContent { CmuxTheme { Surface {
            NativeComputerForgetSection("Fixture Mac", "default", flow, true) { finished++ }
        } } }
        compose.onNodeWithText("Forget This Computer").performClick()
        compose.onNodeWithText("Forget Computer").performClick()
        compose.onNodeWithText(NativeComputerForgetFlow.LOCAL_ERROR).assertIsDisplayed()
        capture("computer-forget-local-retry")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Finish forgetting this computer").performClick()
        compose.onNodeWithText("Retry local cleanup").performClick()
        compose.waitUntil(5000) { finished == 1 }
        compose.runOnIdle { assertEquals(1, revokes); assertEquals(2, cleanups) }
    }

    @Test fun unconfirmedRemovalKeepsDialogAndOffersExplicitRetry() {
        var revokes = 0; var finished = 0
        val flow = NativeComputerForgetFlow({ true }, { emptyList() }, {}, {
            if (++revokes == 1) error("fixture lost acknowledgement")
        }, {})
        compose.setContent { CmuxTheme { Surface {
            NativeComputerForgetSection("Fixture Mac", "default", flow, true) { finished++ }
        } } }
        compose.onNodeWithText("Forget This Computer").performClick()
        compose.onNodeWithText("Forget Computer").performClick()
        compose.onNodeWithText(NativeComputerForgetFlow.REMOTE_ERROR).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, finished) }
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(5000) { finished == 1 }
        assertEquals(2, revokes)
    }

    @Test fun discoveryRemovalCannotDisposePendingDetailsAndCapturedPairingCleanup() = runBlocking<Unit> {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val store = NativeCredentialStore(instrumentation.targetContext, "forget-fixture-${java.util.UUID.randomUUID()}")
        val team = NativeTeamScope("forget-fixture-login", "fixture-user", "forget-fixture-team", 1)
        val mac = IrohV2Computer("fixture-record", "ab".repeat(32), "forget-fixture-mac", "default", "Fixture Mac", emptyList())
        val target = NativeComputerTarget.from(mac)
        val code = PairingCodeParser.computer(mac, team)
        val sibling = mac.copy(buildTag = "debug")
        val siblingCode = PairingCodeParser.computer(sibling, team)
        store.update { it.put("task_session", team.login).put("refresh_token", "fixture-token").put("draft", "Keep this work") }
        store.rememberMac(siblingCode, mac.deviceId, "Sibling", "debug")
        store.rememberMac(code, mac.deviceId, mac.name, "default")
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val gate = CompletableDeferred<Unit>()
        var revokes = 0; var started = 0; var finished = 0
        var pathJson: String? = null
        val backend = object : IrohAccountBackend {
            override val privatePaths = NativePrivatePathStore({ pathJson }, { pathJson = it })
            override val state = MutableStateFlow(IrohV2ControlState(ready = true, computers = listOf(mac), permissionExpiresAt = 2000))
            override suspend fun start() {}
            override suspend fun refresh() {}
            override suspend fun revokeComputer(target: NativeComputerTarget) {
                revokes++
                state.value = state.value.copy(computers = emptyList())
                gate.await()
            }
            override fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport = error("Forget must not dial a Mac")
            override fun close() {}
        }
        try {
            NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { _, _ -> backend }, { 1000 }).use { runtime ->
                withTimeout(5000) { runtime.state.first { it.ready } }
                compose.setContent {
                    val state by runtime.state.collectAsState()
                    var presentation by remember { mutableStateOf<NativeComputerDetailsPresentation?>(null) }
                    CmuxTheme { Surface {
                        state.computers.forEach { computer ->
                            NativeComputerDetailsButton(runtime, state, NativeComputerTarget.from(computer), present = { presentation = it })
                        }
                        NativeComputerDetailsPresentationHost(runtime, state, presentation, NativeComputerConnection(),
                            NativeComputerForgetCallbacks(started = { owner, capturedTarget, rows ->
                                assertEquals(team, owner); assertEquals(target, capturedTarget)
                                assertEquals(listOf(code), rows.map { it.code }); started++
                            }, finished = {
                                assertEquals(listOf(siblingCode), store.pairedMacs().map { it.code }); finished++
                            }), credentialStore = store) { presentation = null }
                    } }
                }
                compose.onNodeWithContentDescription("Details for Fixture Mac (default)").performClick()
                compose.onNodeWithText("Forget This Computer").performScrollTo().performClick()
                capture("computer-forget-confirm")
                compose.onNodeWithText("Forget Computer").performClick()
                compose.waitUntil(5000) { runtime.state.value.computers.isEmpty() }
                compose.onNodeWithText("Forgetting…").assertIsNotEnabled()
                compose.onNodeWithText("Cancel").assertIsNotEnabled()
                compose.runOnIdle { assertEquals(1, started); assertEquals(1, revokes); assertEquals(2, store.pairedMacs().size) }
                compose.runOnIdle { gate.complete(Unit) }
                compose.waitUntil(5000) { finished == 1 }
                compose.onNodeWithText("Forget This Computer").assertDoesNotExist()
                assertEquals(listOf(siblingCode), store.pairedMacs().map { it.code })
                assertEquals("", store.load()!!.optString("pairing_code"))
                assertEquals("Keep this work", store.load()!!.optString("draft"))
                assertEquals(1, revokes)
            }
        } finally { store.clear() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Compose idleness does not include Android's window fade animation.
        android.os.SystemClock.sleep(400)
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val root = instrumentation.targetContext.getExternalFilesDir(null)!!
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(root, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
