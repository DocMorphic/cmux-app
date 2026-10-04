package io.github.docmorphic.cmuxapp

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NativeWorkspaceContextMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun source(mac: String = "a") = NativeFeedSource(NativeCredentialStore.PairedMac(mac, mac, "Mac $mac"),
        workspaces = (0..2).map { NativeWorkspace("w$it", "Workspace $it", emptyList(), null, false, null, null,
            false, emptyList(), null, "Preview", null) })

    @Test fun menuAppearsWhileHeldAndRealWindowPointerCanContinueIntoReordering() {
        val source = source()
        val moves = mutableListOf<String>()
        val actions = mutableListOf<String>()
        var opens = 0
        compose.setContent { CmuxTheme { Surface {
            NativeWorkspaceDragList(workspaceHierarchy(source), true, Modifier.width(360.dp).height(420.dp).testTag("list"),
                onMove = { _, id, _ -> moves += id; true }, empty = {}) { entry ->
                NativeWorkspaceRow((entry as WorkspaceListEntry.Workspace).workspace,
                    canWorkspaceActions = true, canReadState = true, canClose = true,
                    onOpen = { opens++ }, onAction = { action, _ -> actions += action })
            }
        } } }
        val rootLocation = IntArray(2)
        compose.runOnUiThread {
            compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).getLocationOnScreen(rootLocation)
        }
        fun screenPoint(id: String) = compose.onNodeWithTag("workspace.row:$id").fetchSemanticsNode().boundsInRoot.center +
            Offset(rootLocation[0].toFloat(), rootLocation[1].toFloat())
        val first = screenPoint("w0"); val third = screenPoint("w2")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int, point: Offset) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, first)
        try {
            SystemClock.sleep(700)
            compose.waitForIdle()
            screenshot("held-before-assertion")
            val diagnostics = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "workspace-context-menu/held-bounds.txt")
            diagnostics.writeText(compose.onAllNodesWithText("Pin").fetchSemanticsNodes().joinToString { "${it.boundsInRoot} ${it.boundsInWindow}" })
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Pin").assertIsDisplayed() }.isSuccess }
            compose.onNodeWithText("Pin").assertIsDisplayed()
            assertTrue(moves.isEmpty()); assertTrue(actions.isEmpty()); assertEquals(0, opens)
            screenshot("held-menu")
            // Continue the original OS-level pointer after the popup window exists.
            inject(MotionEvent.ACTION_MOVE, third)
            SystemClock.sleep(100)
        } finally { inject(MotionEvent.ACTION_UP, third) }
        compose.waitForIdle()
        compose.onNodeWithText("Pin").assertDoesNotExist()
        assertEquals(listOf("w0"), moves)
        assertTrue(actions.isEmpty()); assertEquals(0, opens)
    }

    @Test fun longPressWorksWithoutReorderAndDisappearsWhenOwningRowIsReplaced() {
        var source by mutableStateOf(source())
        val actions = mutableListOf<String>()
        compose.setContent { CmuxTheme { Surface {
            NativeWorkspaceDragList(workspaceHierarchy(source), false, Modifier.width(360.dp).height(420.dp),
                onMove = { _, _, _ -> fail("Reordering disabled"); false }, empty = {}) { entry ->
                val workspace = (entry as WorkspaceListEntry.Workspace).workspace
                NativeWorkspaceRow(workspace, canWorkspaceActions = true,
                    onOpen = { fail("Long press opened workspace") }, onAction = { action, _ -> actions += "${entry.source.mac.accountUserId ?: entry.source.mac.code}:$action" })
            }
        } } }
        compose.onNodeWithTag("workspace.row:w0").performTouchInput { longClick() }
        compose.onNodeWithText("Pin").performClick()
        assertEquals(listOf("a:pin"), actions)
        compose.onNodeWithTag("workspace.row:w0").performTouchInput { longClick() }
        compose.onNodeWithText("Pin").assertIsDisplayed()
        compose.runOnIdle { source = source.copy(mac = source.mac.copy(accountUserId = "b")) }
        compose.onNodeWithText("Pin").assertDoesNotExist()
        compose.onNodeWithTag("workspace.row:w0").performTouchInput { longClick() }
        compose.onNodeWithText("Pin").performClick()
        assertEquals(listOf("a:pin", "b:pin"), actions)
        compose.onNodeWithTag("workspace.row:w0").performTouchInput { longClick() }
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Rename workspace").assertIsDisplayed()
        compose.runOnIdle { source = source.copy(mac = source.mac.copy(accountUserId = "c")) }
        compose.onNodeWithText("Rename workspace").assertDoesNotExist()
        assertEquals(listOf("a:pin", "b:pin"), actions)
    }

    @Test fun rowAccessibilityIncludesMovesAndMenuAndRejectsRemovedOwner() {
        var source by mutableStateOf(source())
        val moves = mutableListOf<String>()
        compose.setContent { CmuxTheme { Surface {
            NativeWorkspaceDragList(workspaceHierarchy(source), true, Modifier.width(360.dp).height(420.dp),
                onMove = { _, id, _ -> moves += id; true }, empty = {}) { entry ->
                NativeWorkspaceRow((entry as WorkspaceListEntry.Workspace).workspace,
                    canReadState = true, canClose = true, canWorkspaceActions = true,
                    onOpen = {}, onAction = { _, _ -> })
            }
        } } }
        val row = compose.onNodeWithTag("workspace.row:w0")
        val available = row.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertTrue(available.map { it.label }.containsAll(listOf("Move down", "Show workspace actions", "Mark as Unread", "Delete workspace")))
        val move = available.single { it.label == "Move down" }
        compose.runOnUiThread { assertTrue(move.action()) }
        assertEquals(listOf("w0"), moves)
        row.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithText("Pin").assertIsDisplayed()
        compose.onNodeWithContentDescription("Actions for Workspace 0").assertDoesNotExist()
        compose.runOnIdle { source = source.copy(mac = source.mac.copy(accountUserId = "b")) }
        compose.waitForIdle()
        compose.runOnUiThread { assertFalse(move.action()) }
        assertEquals(listOf("w0"), moves)
    }

    @Test fun groupPickerChecksCurrentGroupRoutesRemovalAndReturnsToParent() {
        val base = source().copy(availability = NativeFeedAvailability.CONNECTED,
            capabilities = setOf("workspace.move.v1"),
            groups = listOf(NativeGroup("one", "Current destination", false, false),
                NativeGroup("two", "Collapsed destination", true, false, iconSymbol = "terminal")))
        var owner by mutableStateOf(base.copy(workspaces = base.workspaces.map { it.copy(windowId = "window", groupId = "one") }))
        val actions = mutableListOf<String>()
        compose.setContent { CmuxTheme { Surface {
            NativeWorkspaceDragList(listOf(WorkspaceListEntry.Workspace(owner, owner.workspaces.first())), false,
                Modifier.width(360.dp).height(420.dp), onMove = { _, _, _ -> false }, empty = {}) { entry ->
                NativeWorkspaceRow((entry as WorkspaceListEntry.Workspace).workspace,
                    groupMoveMenu = NativeWorkspaceGroupMoveMenu.forWorkspace(owner, "w0"), canWorkspaceActions = true,
                    onOpen = { fail("Opened a workspace") }, onAction = { action, _ -> actions += action })
            }
        } } }
        fun picker() {
            compose.onNodeWithTag("workspace.row:w0").performTouchInput { longClick() }
            compose.onNodeWithText("Move to Group").performClick()
        }
        picker()
        compose.onNodeWithText("Current destination").assertIsNotEnabled().assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Current group"))
        compose.onNodeWithText("Collapsed destination").assertIsEnabled()
        screenshot("move-to-group")
        compose.onNodeWithContentDescription("Back to workspace actions").performClick()
        compose.onNodeWithText("Pin").assertIsDisplayed()
        compose.onNodeWithText("Move to Group").performClick()
        compose.onNodeWithText("Collapsed destination").performClick()
        assertEquals(listOf("move:two"), actions)
        picker()
        compose.onNodeWithText("Remove from Group").performClick()
        assertEquals(listOf("move:two", "move:"), actions)
        picker()
        compose.runOnIdle { owner = owner.copy(mac = owner.mac.copy(accountUserId = "replacement")) }
        compose.onNodeWithText("Current destination").assertDoesNotExist()
        compose.onNodeWithTag("workspace.row:w0").performTouchInput { longClick() }
        compose.onNodeWithText("Pin").assertIsDisplayed()
        compose.onNodeWithText("Move to Group").performClick()
        compose.runOnIdle { owner = owner.copy(capabilities = emptySet()) }
        compose.onNodeWithText("Move to Group").assertDoesNotExist()
        compose.onNodeWithText("Collapsed destination").assertDoesNotExist()
        compose.onNodeWithText("Pin").assertIsDisplayed()
        assertEquals(listOf("move:two", "move:"), actions)
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "workspace-context-menu").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
