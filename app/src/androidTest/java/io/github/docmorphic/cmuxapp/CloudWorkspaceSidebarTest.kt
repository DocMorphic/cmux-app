package io.github.docmorphic.cmuxapp

import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Production adaptive shell and Cloud flow, with no account/network mutations. */
class CloudWorkspaceSidebarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: CloudMachinesController? = null
    private class Service : CloudMachinesService {
        override suspend fun catalog() = CloudMachineCatalog(listOf(
            CloudMachine("fixture", "fixture", "running", "Cloud machine", null, CloudMachineResources(4, 8192))),
            availableKinds = null, limits = null)
        override suspend fun create(options: CloudMachineCreateOptions, idempotencyKey: String): CloudMachine = error("Unexpected create")
        override suspend fun pause(id: String) = error("Unexpected pause")
        override suspend fun resume(id: String) = error("Unexpected resume")
        override suspend fun delete(id: String) = error("Unexpected delete")
        override fun close() {}
    }
    @After fun stop() { compose.runOnIdle { controller?.close(); lifetime.cancel() } }

    @Test fun switchingToCloudRetainsWideDetailAndDraftAndCompactBackReturnsToDetail() {
        var cloud by mutableStateOf(false)
        var width by mutableIntStateOf(1000)
        var created = 0; var released = 0
        var view: TextView? = null
        compose.runOnUiThread {
            controller = CloudMachinesController(lifetime, Service(), object : CloudCreateJournal {
                override suspend fun resolve(options: CloudMachineCreateOptions) = error("Unexpected create journal")
                override suspend fun complete(key: String) = error("Unexpected create acknowledgement")
            }, { true }).also { it.refresh() }
        }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            val cloudState = rememberSaveableStateHolder()
            var draft by rememberSaveable { mutableStateOf("") }
            NativeWorkspaceShell("account/team", true, widthDp = width, heightDp = 700,
                showSidebarInCompact = cloud, modifier = Modifier.fillMaxSize(), sidebar = {
                    if (cloud) Column(Modifier.weight(1f)) {
                        cloudState.SaveableStateProvider("cloud") {
                            NativeCloudFlow(controller, {}, {}, { cloud = false },
                                progressStore = remember { CloudOnboardingStore({ true }, { error("Unexpected intro write") }) })
                        }
                    } else {
                        NativeWorkspaceSidebarToggle()
                        Text("Workspace list", Modifier.weight(1f))
                    }
                    NativePrimaryNavigation(false, 0, NativeSearchState(), { cloud = false }, {}, { _, _ -> }, {}, {},
                        sidebar = LocalWorkspaceShellChrome.current.split, cloudTab = cloud, onCloud = { cloud = true })
                }, detail = {
                    NativeWorkspaceBackControl { Text("Workspace Back") }
                    TextField(draft, { draft = it }, Modifier.testTag("workspace.draft"))
                    AndroidView(factory = { context -> TextView(context).also {
                        created++; view = it; it.text = "Mounted terminal"
                    } }, onRelease = { released++ }, modifier = Modifier.fillMaxWidth().height(100.dp))
                })
        } } }
        compose.onNodeWithTag("workspace.draft").performTextInput("Unsent terminal draft")
        val original = view
        compose.onNode(hasText("Cloud") and isSelectable()).performClick()
        compose.onNodeWithTag("cloud.machine.fixture").assertIsDisplayed()
        compose.onNodeWithText("Unsent terminal draft").assertIsDisplayed()
        compose.runOnIdle { assertSame(original, view); assertEquals(1, created); assertEquals(0, released) }
        captureCloudScreen("cloud-wide-sidebar")
        compose.onNodeWithContentDescription("Hide sidebar").performClick()
        compose.onNodeWithTag("cloud.screen").assertDoesNotExist()
        compose.onNodeWithText("Unsent terminal draft").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show sidebar").performClick()
        compose.onNode(hasText("Workspaces") and isSelectable()).performClick()
        compose.onNodeWithText("Workspace list").assertIsDisplayed()
        compose.runOnIdle { assertSame(original, view); assertEquals(1, created); assertEquals(0, released) }
        compose.onNode(hasText("Cloud") and isSelectable()).performClick()
        compose.runOnIdle { width = 412 }
        compose.onNodeWithTag("cloud.screen").assertIsDisplayed()
        compose.onNodeWithTag("workspace.draft").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("cloud.screen").assertDoesNotExist()
        compose.onNodeWithText("Unsent terminal draft").assertIsDisplayed()
        compose.runOnIdle { assertFalse(cloud) }
    }

    @Test fun narrowCloudIntroductionKeepsProgressAcrossReflowAndDoesNotStealDetailBack() {
        var cloud by mutableStateOf(true)
        var width by mutableIntStateOf(1000)
        var detailBacks = 0; var completed = 0
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            NativeWorkspaceShell("account/team", true, widthDp = width, heightDp = 700,
                showSidebarInCompact = cloud, modifier = Modifier.fillMaxSize(), sidebar = {
                    if (cloud) NativeCloudFlow(null, {}, {}, { cloud = false }, Modifier.weight(1f),
                        progressStore = remember { CloudOnboardingStore({ false }, { completed++; true }) })
                    else Text("Workspace list")
                }, detail = {
                    BackHandler { detailBacks++ }
                    NativeWorkspaceBackControl { Text("Workspace Back") }
                    Text("Selected workspace")
                })
        } } }
        compose.onNodeWithTag("cloud.introduction.next").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Cloud introduction, step 2 of 3").assertExists()
        compose.onNodeWithTag("cloud.introduction.vpn.status").performScrollTo().assertIsDisplayed()
        captureCloudScreen("cloud-sidebar-introduction")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Cloud introduction, step 2 of 3").assertExists()
        compose.runOnIdle { assertEquals(1, detailBacks); width = 412 }
        compose.onNodeWithText("Selected workspace").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cloud introduction, step 2 of 3").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Cloud introduction, step 1 of 3").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Selected workspace").assertIsDisplayed()
        compose.runOnIdle { assertFalse(cloud); assertEquals(0, completed) }
    }
}
