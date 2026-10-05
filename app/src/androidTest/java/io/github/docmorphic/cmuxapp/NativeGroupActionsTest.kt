package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NativeGroupActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var owner by mutableStateOf(NativeCredentialStore.PairedMac("a", "a", "Mac a"))
    private var pinned by mutableStateOf(false)
    private var edit by mutableStateOf(true)
    private var create by mutableStateOf(true)
    private var creationEnabled by mutableStateOf(true)
    private val actions = mutableListOf<String>()
    private var opens = 0
    private fun show() {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            val group = NativeGroup("g", "Project group", false, pinned)
            val source = NativeFeedSource(owner, groups = listOf(group), capabilities = buildSet {
                add(WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY)
                if (edit) add("workspace.group_actions.v1")
                if (create) add("workspace.create_in_group.v1")
            })
            NativeWorkspaceDragList(listOf(WorkspaceListEntry.Header(source, group)), false,
                Modifier.fillMaxSize(), onMove = { _, _, _ -> false }, empty = {}) {
                NativeGroupHeaderRow(group, true, NativeWorkspaceUnread.Read, { opens++ }, edit,
                    create, creationEnabled, { actions += "create" }, {}, { action, _ -> actions += action })
            }
        } } }
    }
    private fun menu() = compose.onNodeWithContentDescription("Open Project group").performTouchInput { longClick() }
    @Test fun cachedCreateAndUngroupCallbacksCheckCurrentAvailability() {
        show(); menu()
        val createAction = compose.onNodeWithText("New Workspace in Group").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { creationEnabled = false }
        compose.onNodeWithText("New Workspace in Group").assertIsNotEnabled()
        compose.runOnUiThread { createAction() }
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        menu()
        compose.onNodeWithText("Ungroup (Keep Workspaces)").performClick()
        val ungroupAction = compose.onNodeWithText("Ungroup", substring = false).fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { pinned = true }
        compose.onNodeWithText("Ungroup Group?").assertDoesNotExist()
        compose.runOnUiThread { ungroupAction() }
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    private fun screenshot(name: String, vararg labels: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val regions = labels.map { label -> checkNotNull(device.wait(Until.findObject(By.text(label)), 5000)).visibleBounds }
        var accepted: Bitmap? = null
        compose.waitUntil(5000) {
            val image = instrumentation.uiAutomation.takeScreenshot()
            val painted = regions.all { bounds ->
                var bright = 0
                for (y in bounds.top until bounds.bottom step 2) for (x in bounds.left until bounds.right step 2) {
                    val pixel = image.getPixel(x, y)
                    if (Color.red(pixel) > 170 && Color.green(pixel) > 170 && Color.blue(pixel) > 170) bright++
                }
                bright > 20
            }
            if (painted) { accepted = image; true } else { image.recycle(); false }
        }
        val image = checkNotNull(accepted)
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "group-actions").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test fun exactMenuOrderAndDestructiveConfirmationCancelThenConfirm() {
        show(); menu()
        compose.onNodeWithContentDescription("Actions for Project group").assertDoesNotExist()
        val labels = listOf("Pin Group", "Rename Group", "New Workspace in Group", "Ungroup (Keep Workspaces)", "Delete Group (Close Workspaces)")
        val positions = labels.map { compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertEquals(positions.sorted(), positions); screenshot("menu", "Pin Group", "New Workspace in Group")
        compose.onNodeWithText("Delete Group (Close Workspaces)").performClick()
        compose.onNodeWithText("Delete Group?").assertIsDisplayed()
        compose.onNodeWithText("This will delete the group and close its workspaces on your Mac.").assertIsDisplayed()
        screenshot("delete-confirmation", "Delete Group?", "This will delete the group and close its workspaces on your Mac.")
        assertTrue(actions.isEmpty()); compose.onNodeWithText("Cancel").performClick(); assertTrue(actions.isEmpty())
        menu(); compose.onNodeWithText("Delete Group (Close Workspaces)").performClick()
        compose.onNodeWithText("Delete Group").performClick(); assertEquals(listOf("delete"), actions)
        menu(); compose.onNodeWithText("Ungroup (Keep Workspaces)").performClick()
        compose.onNodeWithText("This will dissolve the group on your Mac and keep its workspaces.").assertIsDisplayed()
        compose.onNodeWithText("Ungroup").performClick(); assertEquals(listOf("delete", "ungroup"), actions)
    }
    @Test fun pinnedAndWithdrawnCapabilitiesRetirePendingDestructiveActions() {
        show(); menu(); compose.onNodeWithText("Ungroup (Keep Workspaces)").performClick()
        compose.runOnIdle { pinned = true }
        compose.onNodeWithText("Ungroup Group?").assertDoesNotExist()
        compose.runOnIdle { pinned = false }
        compose.onNodeWithText("Ungroup Group?").assertDoesNotExist()
        compose.runOnIdle { pinned = true }
        menu(); compose.onNodeWithText("Unpin Group").assertIsDisplayed()
        compose.onNodeWithText("Ungroup (Keep Workspaces)").assertDoesNotExist()
        compose.onNodeWithText("Delete Group (Close Workspaces)").performClick()
        compose.runOnIdle { edit = false }
        compose.onNodeWithText("Delete Group?").assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }
    @Test fun creationOnlyMenuAndOwnerReplacementDoNotTrapTheHeader() {
        edit = false; show(); menu()
        compose.onNodeWithText("Pin Group").assertDoesNotExist()
        compose.onNodeWithText("New Workspace in Group").performClick(); assertEquals(listOf("create"), actions)
        compose.runOnIdle { creationEnabled = false }
        menu(); compose.onNodeWithText("New Workspace in Group").assertIsNotEnabled()
        compose.runOnIdle { owner = owner.copy(accountUserId = "replacement") }
        compose.onNodeWithText("New Workspace in Group").assertDoesNotExist()
        compose.runOnIdle { create = false }
        menu(); compose.onNodeWithText("New Workspace in Group").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open Project group").performClick(); assertEquals(1, opens)
    }
    @Test fun replacingTheOwnerDismissesItsDeleteConfirmation() {
        show(); menu(); compose.onNodeWithText("Delete Group (Close Workspaces)").performClick()
        compose.runOnIdle { owner = owner.copy(accountUserId = "replacement") }
        compose.onNodeWithText("Delete Group?").assertDoesNotExist(); assertTrue(actions.isEmpty())
    }
}
