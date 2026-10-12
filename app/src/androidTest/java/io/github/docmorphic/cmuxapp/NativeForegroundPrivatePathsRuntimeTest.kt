package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class NativeForegroundPrivatePathsRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun originalOwnerEditorPersistsOnlyItsMacAndRejectsLateMembershipLoss() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = UUID.randomUUID().toString()
        val original = NativeTeamScope("fixture-$fixture", "user-$fixture", "original-$fixture", 1)
        val selected = original.copy(teamId = "picker-$fixture", generation = 2)
        val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
            "cmux-ios://attach?v=2&r=100.64.0.7:58465", "device-$fixture", "Original Mac", "default"), original)
        val target = checkNotNull(NativeComputerTarget.from(mac, original))
        fun identity(team: NativeTeamScope) = JSONObject().put("environment", "fixture").put("projectId", "fixture")
            .put("userId", team.userId).put("teamId", team.teamId).put("deviceId", fixture)
            .put("appNamespace", context.packageName).put("buildTag", "debug")
        val originalIdentity = identity(original); val pickerIdentity = identity(selected)
        val originalStore = NativePrivatePathStore.create(context, originalIdentity)
        val pickerStore = NativePrivatePathStore.create(context, pickerIdentity)
        val sibling = NativePrivatePath(mac.deviceId, "nightly", "Sibling Mac", listOf("10.0.0.9:58470"), true)
        val pickerPath = sibling.copy(buildTag = "default", name = "Picker Mac")
        originalStore.upsert(sibling); pickerStore.upsert(pickerPath)
        val member = AtomicBoolean(true)
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = selected))
        val wire = object : MobileRpcTransport {
            val input = Channel<ByteArray>(1)
            override suspend fun connect() {}
            override suspend fun read() = input.receiveCatching().getOrNull()
            override suspend fun write(bytes: ByteArray) = error("Local editor must not send RPC")
            override fun close() { input.close() }
        }
        try {
            NativeIrohRuntime(teams, { teams.value.scope == it }, { error("Local editor must not fetch a token") }, { owner, _ ->
                object : IrohAccountBackend {
                    override val state = MutableStateFlow(IrohV2ControlState())
                    override val privatePaths = NativePrivatePathStore.create(context, identity(owner))
                    override suspend fun start() { check(owner == selected) }
                    override suspend fun startManagement() = error("Local editor must not connect account control")
                    override suspend fun refresh() = error("Local editor must not refresh control")
                    override fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport = error("Must not dial")
                    override fun close() {}
                }
            }).use { runtime -> MobileRpcClient(wire, { error("Local editor must not fetch a token") }).use { client ->
                client.connect()
                val management = NativeForegroundComputerManagement(original, mac, { !client.isClosed }, member::get)
                val access = NativeForegroundComputerDetailAccess(original, mac, client, management) { !client.isClosed }
                compose.setContent { CmuxTheme { NativeComputerDetailsScreen(target, true, false,
                    check = { error("Not used") }, paths = { runtime.foregroundPrivatePaths(access) },
                    changePaths = { runtime.foregroundPrivatePaths(access, it) }, share = {}, onBack = {}) } }
                compose.onNodeWithText("Sibling Mac").assertDoesNotExist()
                compose.onNodeWithText("Picker Mac").assertDoesNotExist()
                compose.onNodeWithText("Add addresses for Original Mac").performScrollTo().performClick()
                compose.onNodeWithText("IP Addresses and Ports").performTextInput("10.0.0.7:58470")
                compose.onNodeWithContentDescription("Use These Addresses").performClick()
                compose.onNodeWithText("Save").performClick()
                compose.waitUntil(5000) { originalStore.load().size == 2 }
                val saved = originalStore.load().single { it.buildTag == "default" }
                assertTrue(saved.enabled); assertEquals(listOf("10.0.0.7:58470"), saved.addresses)
                assertEquals(sibling, originalStore.load().single { it.buildTag == "nightly" })
                assertEquals(listOf(pickerPath), pickerStore.load()); assertEquals(selected, teams.value.scope)
                compose.onNodeWithText("Edit Original Mac").performScrollTo().performClick()
                compose.onNodeWithText("IP Addresses and Ports").performTextReplacement("10.0.0.8:58470")
                member.set(false)
                compose.onNodeWithText("Save").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithText("Could not save private addresses. Try again.").fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodesWithText("Could not save private addresses. Try again.").onLast().assertIsDisplayed()
                assertEquals(saved, originalStore.load().single { it.buildTag == "default" })
                assertEquals(listOf(pickerPath), pickerStore.load()); assertFalse(client.isClosed)
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(context.getExternalFilesDir(null), "foreground-private-addresses.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            } }
        } finally {
            // Only these unique fixture identity files; never an actual enrollment or phone credential.
            for (value in listOf(originalIdentity, pickerIdentity)) {
                val file = File(context.noBackupFilesDir, "cmux-iroh-v2/local-paths/" + NativePrivatePathStore.identityFile(value))
                file.delete(); File(file.path + ".bak").delete()
            }
        }
    }
}
