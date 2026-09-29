package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeComputerDetailsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val mac = IrohV2Computer("record", "ab".repeat(32), "fixture-mac", "default", "Test Mac", emptyList())
    private val target = NativeComputerTarget.from(mac)

    @Test fun checksUndiscoveredMacWithoutSelectingItAndSharesSafeFailure() {
        val answer = CompletableDeferred<NativeConnectionReport>()
        var checks = 0
        var shared = ""
        compose.setContent { CmuxTheme { NativeComputerDetailsScreen(target, false, true,
            check = { checks++; answer.await() }, paths = { emptyList() }, changePaths = { emptyList() },
            share = { shared = it }, onBack = {}) } }
        compose.onNodeWithText("Not currently discovered").assertIsDisplayed()
        compose.onNodeWithText("Check Connection").performClick()
        compose.onNodeWithText("Checking…").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, checks); answer.complete(NativeConnectionReport(failure = NativeConnectionReport.Failure.DISCOVERY)) }
        compose.onNodeWithText(NativeConnectionReport.Failure.DISCOVERY.advice).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Share Connection Report").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(shared.contains("fixture-mac")); assertFalse(shared.contains("Test Mac")); assertTrue(shared.contains("Suggested Action")) }
        compose.onNodeWithText("Add addresses for Test Mac").assertDoesNotExist()
    }

    @Test fun editsOnlyThisMacBuildAndOffersNoGlobalReset() {
        var json: String? = null
        val store = NativePrivatePathStore({ json }, { json = it })
        val other = NativePrivatePath(mac.deviceId, "debug", "Other Build", listOf("10.1.0.9:58470"), true)
        store.upsert(other)
        compose.setContent { CmuxTheme { NativeComputerDetailsScreen(target, true, true,
            check = { NativeConnectionReport() }, paths = { store.load().filter { target.matches(it) } },
            changePaths = { action -> action(store); store.load().filter { target.matches(it) } }, share = {}, onBack = {}) } }
        compose.onNodeWithText("Other Build").assertDoesNotExist()
        compose.onNodeWithText("Reset Private Addresses").assertDoesNotExist()
        compose.onNodeWithText("Add addresses for Test Mac").performScrollTo().performClick()
        compose.onNodeWithText("IP Addresses and Ports").performTextInput("10.1.0.8:58470")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5000) { store.load().size == 2 }
        compose.onNodeWithText("Edit Test Mac").performScrollTo().assertIsDisplayed()
        assertEquals(other, store.load().single { it.buildTag == "debug" })
        assertEquals(listOf("10.1.0.8:58470"), store.load().single { it.buildTag == "default" }.addresses)
        compose.onNodeWithText("Reset Private Addresses").assertDoesNotExist()
        capture("computer-private-addresses")
    }

    @Test fun detailsButtonDoesNotTriggerParentComputerSelection() = runBlocking {
        val team = NativeTeamScope("fixture-login", "fixture-user", "fixture-team", 1)
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        var json: String? = null
        val backend = object : IrohAccountBackend {
            override val state = MutableStateFlow(IrohV2ControlState(ready = true, computers = listOf(mac), permissionExpiresAt = 2000))
            override val privatePaths = NativePrivatePathStore({ json }, { json = it })
            override suspend fun start() { }
            override suspend fun refresh() { }
            override fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport = error("Opening details must not dial")
            override fun close() { }
        }
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            withTimeout(5000) { runtime.state.first { it.ready } }
            var selections = 0
            compose.setContent { CmuxTheme { Surface { Row(Modifier.fillMaxWidth().testTag("computer-row").clickable { selections++ }) {
                Text(mac.name, Modifier.weight(1f))
                NativeComputerDetailsButton(runtime, runtime.state.value, target)
            } } } }
            compose.onNodeWithContentDescription("Details for Test Mac (default)").performClick()
            compose.onNodeWithText("Available in this team").assertIsDisplayed()
            compose.onNodeWithText("App Build: default").assertIsDisplayed()
            capture("computer-details")
            compose.onNodeWithText("‹  Back").performClick()
            compose.runOnIdle { assertEquals(0, selections) }
            compose.onNodeWithTag("computer-row").performClick()
            compose.runOnIdle { assertEquals(1, selections) }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val root = instrumentation.targetContext.getExternalFilesDir(null)!!
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(root, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
