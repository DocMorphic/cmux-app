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
    private fun mount(service: Service) {
        compose.runOnUiThread {
            controller = CloudMachinesController(scope, service, object : CloudCreateJournal {
                override suspend fun resolve(options: CloudMachineCreateOptions) = "fixture-create"
                override suspend fun complete(key: String) {}
            }, { true }).also { it.refresh() }
        }
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { NativeCloudScreen(controller, {}, {}) } } }
    }
    @After fun stop() { compose.runOnUiThread { controller?.close(); scope.cancel() } }
    @Test fun creationUsesSelectedSizeAndShowsServerUsage() {
        val service = Service(); mount(service)
        compose.onNodeWithTag("cloud.new").performClick()
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
}
