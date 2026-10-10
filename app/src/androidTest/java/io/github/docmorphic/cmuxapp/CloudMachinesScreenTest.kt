package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CloudMachinesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: CloudMachinesController? = null
    private class Service : CloudMachinesService {
        val creates = mutableListOf<CloudMachineCreateOptions>()
        val deletes = mutableListOf<String>()
        var rows = listOf(CloudMachine("vm", "fixture", "running", "My machine", null, CloudMachineResources(4, 8192)))
        override suspend fun catalog() = CloudMachineCatalog(rows, setOf(CloudMachineKind.DESKTOP),
            CloudMachineLimits(5, 1, "pro", listOf(4096, 8192), listOf(65536), "max", null, CloudResourcePool(8, 16384, 4, 8192)))
        override suspend fun create(options: CloudMachineCreateOptions, idempotencyKey: String): CloudMachine { creates += options; return rows.first() }
        override suspend fun pause(id: String) { rows = rows.map { if (it.id == id) it.copy(status = "paused") else it } }
        override suspend fun resume(id: String) { rows = rows.map { if (it.id == id) it.copy(status = "running") else it } }
        override suspend fun delete(id: String) { deletes += id; rows = rows.filterNot { it.id == id } }
        override fun close() {}
    }
    private fun mount(service: Service, machines: Map<String, CloudWorkspaceSnapshot> = emptyMap(),
        onRetry: () -> Unit = {}, connectionFailures: Map<String, CloudSessionFailure> = emptyMap(), onPlans: (String?) -> Unit = {}) {
        compose.runOnUiThread {
            controller = CloudMachinesController(scope, service, object : CloudCreateJournal {
                override suspend fun resolve(options: CloudMachineCreateOptions) = "fixture-create"
                override suspend fun complete(key: String) {}
            }, { true }).also { it.refresh() }
        }
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { NativeCloudScreen(controller, {}, onPlans, machines = machines, onRetryConnections = onRetry, connectionFailures = connectionFailures) } } }
    }
    @After fun stop() { compose.runOnUiThread { controller?.close(); scope.cancel() } }
    private fun openCreate() {
        compose.onNodeWithTag("cloud.new").performClick()
        // Match a user's expansion of the source's medium/large sheet before
        // inspecting controls near its footer. Short layouts may already fit.
        val expands = compose.onAllNodes(SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsActions.Expand), useUnmergedTree = true)
        if (expands.fetchSemanticsNodes().isNotEmpty()) expands.onFirst()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Expand) { it() }
    }
    @Test fun creationUsesSelectedSizeAndShowsServerUsage() {
        val service = Service(); mount(service)
        openCreate()
        compose.onNodeWithText("1 of 5 machines in use").assertExists()
        compose.onNodeWithText("4 of 8 vCPUs · 8 of 16 GB RAM in use").assertExists()
        compose.onNodeWithTag("cloud.create.size").performClick()
        compose.onNodeWithText("4 GB RAM · 16 GB disk").performClick()
        compose.onNodeWithTag("cloud.create.submit").performScrollTo().performClick()
        compose.waitUntil(3000) { service.creates.size == 1 }
        assertEquals(CloudMachineCreateOptions(CloudMachineKind.DESKTOP, memoryMb = 4096), service.creates.single())
    }
    @Test fun deletingRequiresExplicitConfirmationAndReconcilesTheList() {
        val service = Service(); mount(service)
        compose.onNodeWithContentDescription("Actions for My machine").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("This permanently deletes the machine and its disk, including its terminals and files.").assertExists()
        assertTrue(service.deletes.isEmpty())
        compose.onNodeWithTag("cloud.delete.confirm").performClick()
        compose.onNodeWithTag("cloud.machine.vm").assertDoesNotExist()
        assertEquals(listOf("vm"), service.deletes)
    }
    @Test fun cloudTabIsSelectedWithoutWorkspaceSearch() {
        var workspace: Boolean? = null
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface {
            NativePrimaryNavigation(false, 0, NativeSearchState(), { workspace = it }, {}, { _, _ -> }, {}, {}, cloudTab = true, onCloud = {})
        } } }
        compose.onNodeWithText("Cloud").assertIsSelected()
        compose.onNodeWithContentDescription("Search").assertDoesNotExist()
        compose.onNodeWithText("Workspaces").performClick()
        assertEquals(false, workspace)
    }

    @Test fun lockedSizeOpensItsPlanWithoutChangingTheSubmittedMachineSize() {
        val plans = mutableListOf<String?>()
        val service = Service(); mount(service) { plans += it }
        openCreate()
        compose.onNodeWithTag("cloud.create.locked.note").assertTextEquals("64 GB machines need cmux Max.")
        compose.onNodeWithTag("cloud.create.upgrade").performScrollTo().performClick()
        assertEquals(listOf("max"), plans); assertTrue(service.creates.isEmpty())
        compose.onNodeWithTag("cloud.create.size").performClick()
        compose.onNodeWithTag("cloud.create.upgrade.65536").assertIsEnabled().performClick()
        assertEquals(listOf("max", "max"), plans); assertTrue(service.creates.isEmpty())
        compose.onNodeWithTag("cloud.create.size").assertTextContains("8 GB RAM · 32 GB disk")
        compose.onNodeWithTag("cloud.create.submit").performScrollTo().performClick()
        compose.waitUntil(3000) { service.creates.size == 1 }
        assertEquals(8192, service.creates.single().memoryMb)
    }
    @Test fun firstComputerTabsExposeCloudWithoutNotificationsOrSearch() {
        var selectedCloud = false
        compose.setContent { MaterialTheme { Surface {
            NativePrimaryNavigation(false, 0, NativeSearchState(), {}, {}, { _, _ -> }, {}, {},
                onCloud = { selectedCloud = true }, emptyComputers = true)
        } } }
        compose.onNodeWithText("Workspaces").assertIsSelected()
        compose.onNodeWithText("Notifications").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search").assertDoesNotExist()
        compose.onNodeWithText("Cloud").assertIsDisplayed().performClick()
        assertTrue(selectedCloud)
    }

    @Test fun fullSwipeOnlyRevealsDeleteAndDeletionStillRequiresConfirmation() {
        val service = Service(); mount(service)
        compose.onNodeWithTag("cloud.machine.vm").performTouchInput { swipeLeft() }
        assertTrue(service.deletes.isEmpty())
        compose.onNodeWithTag("cloud.swipe.vm.DELETE").assertIsDisplayed().performClick()
        assertTrue(service.deletes.isEmpty())
        compose.onNodeWithTag("cloud.delete.confirm").assertIsDisplayed()
        captureCloudScreen("machine-delete-confirmation")
        compose.onNodeWithTag("cloud.delete.confirm").performClick()
        compose.waitUntil(3000) { service.deletes.size == 1 }
        assertEquals(listOf("vm"), service.deletes)
    }
    @Test fun terminalFailureIsVisibleAndRetryableEvenWithAHealthyCatalog() {
        val service = Service(); var retries = 0
        val healthy = CloudWorkspaceSnapshot(service.rows.single(), availability = NativeFeedAvailability.CONNECTED, authoritative = true)
        mount(service, mapOf("vm" to healthy), onRetry = { retries++ },
            connectionFailures = mapOf("vm" to CloudSessionFailure("private attach diagnostic", kind = CloudFailureKind.LINK)))
        compose.onNodeWithTag("cloud.connection.failure.vm", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Couldn't connect.").assertExists()
        compose.onNodeWithText("Couldn't connect. Retrying automatically.").assertDoesNotExist()
        compose.onNodeWithText("private attach diagnostic").assertDoesNotExist()
        compose.onNodeWithContentDescription("Actions for My machine").performClick()
        compose.onNodeWithText("Try Again Now").performClick()
        assertEquals(1, retries)
    }
    @Test fun failedMachineOffersRetryAndRefreshWithoutShowingPrivateDiagnostic() {
        val service = Service(); var retries = 0
        val snapshot = CloudWorkspaceSnapshot(service.rows.single(), failure =
            CloudSessionFailure("private native diagnostic", kind = CloudFailureKind.LINK))
        mount(service, mapOf("vm" to snapshot), onRetry = { retries++ })
        compose.onNodeWithTag("cloud.connection.failure.vm", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Could not reach this machine's terminal service.").assertExists()
        compose.onNodeWithText("private native diagnostic").assertDoesNotExist()
        captureCloudScreen("machine-connection-recovery")
        compose.onNodeWithContentDescription("Actions for My machine").performClick()
        compose.onNodeWithText("Try Again Now").performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("Refresh").performClick()
        assertEquals(2, retries); assertTrue(service.deletes.isEmpty())
    }
}
