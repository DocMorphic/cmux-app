package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeWorkspaceSwipeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val workspace = NativeWorkspace("swipe", "Swipe workspace", emptyList(), null, true, null, null,
        false, emptyList(), null, "Latest activity", null)

    @Test fun fullSwipesReadAndUnreadWithoutOpeningAndDeleteStillConfirms() {
        var row by mutableStateOf(workspace)
        var opens = 0
        val actions = mutableListOf<String>()
        compose.setContent { CmuxTheme { Column(Modifier.width(360.dp)) {
            NativeWorkspaceRow(row, canReadState = true, canClose = true,
                onOpen = { opens++ }, onAction = { action, _ ->
                    actions += action
                    if (action == "mark_read") row = row.copy(hasUnread = false)
                    if (action == "mark_unread") row = row.copy(hasUnread = true)
                })
        } } }
        val swipe = compose.onNodeWithTag("workspace.swipe:swipe")
        swipe.performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(listOf("mark_read"), actions); assertEquals(0, opens) }
        swipe.performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(listOf("mark_read", "mark_unread"), actions) }
        swipe.performTouchInput { swipeLeft() }
        compose.onNodeWithText("Delete Workspace?").assertIsDisplayed()
        assertFalse("Swiping must not delete before confirmation", "close" in actions)
        screenshot("delete-confirmation")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("workspace.row:swipe").assertExists()
        swipe.performTouchInput { swipeLeft() }
        compose.onNodeWithTag("workspace.close.confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("mark_read", "mark_unread", "close"), actions); assertEquals(0, opens) }
    }

    @Test fun partialSwipeRevealsButtonAndRowTapDismissesBeforeOpening() {
        var opens = 0
        val actions = mutableListOf<String>()
        compose.setContent { CmuxTheme { Column(Modifier.width(360.dp)) {
            NativeWorkspaceRow(workspace, canReadState = true, canClose = true,
                onOpen = { opens++ }, onAction = { action, _ -> actions += action })
        } } }
        val swipe = compose.onNodeWithTag("workspace.swipe:swipe")
        fun reveal() = swipe.performTouchInput { swipe(center, center + Offset(width * .30f, 0f), 400) }
        reveal()
        compose.onNodeWithText("Mark as Read").assertIsDisplayed()
        assertTrue(actions.isEmpty())
        screenshot("partial-read")
        compose.onNodeWithTag("workspace.row:swipe").performClick()
        compose.onNodeWithText("Mark as Read").assertDoesNotExist()
        assertEquals(0, opens)
        reveal()
        compose.onNodeWithText("Mark as Read").performClick()
        assertEquals(listOf("mark_read"), actions)
        compose.onNodeWithTag("workspace.row:swipe").performClick()
        assertEquals(1, opens)
    }

    @Test fun unsupportedGesturesStayClosedAndAccessibilityActionsTrackCapabilities() {
        var enabled by mutableStateOf(false)
        val actions = mutableListOf<String>()
        compose.setContent { CmuxTheme { Column(Modifier.width(360.dp)) {
            NativeWorkspaceRow(workspace, canReadState = enabled, canClose = enabled,
                onOpen = {}, onAction = { action, _ -> actions += action })
        } } }
        val swipe = compose.onNodeWithTag("workspace.swipe:swipe")
        swipe.performTouchInput { swipeRight() }; swipe.performTouchInput { swipeLeft() }
        compose.onNodeWithTag("workspace.swipe.action:swipe").assertDoesNotExist()
        assertTrue(actions.isEmpty())
        fun labels() = compose.onNodeWithTag("workspace.row:swipe").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(emptyList<String>(), labels())
        compose.runOnIdle { enabled = true }
        assertTrue("Mark as Read" in labels()); assertTrue("Delete workspace" in labels())
        val accessibleRead = compose.onNodeWithTag("workspace.row:swipe").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "Mark as Read" }
        compose.runOnUiThread { assertTrue(accessibleRead.action()) }
        assertEquals(listOf("mark_read"), actions)
        swipe.performTouchInput { swipe(center, center + Offset(width * .3f, 0f), 400) }
        compose.runOnIdle { enabled = false }
        compose.onNodeWithTag("workspace.swipe.action:swipe").assertDoesNotExist()
        assertEquals(emptyList<String>(), labels())
    }

    @Test fun rtlLeadingSwipeUsesReadAndOnlyOneOwningRowCanRemainRevealed() {
        val coordinator = WorkspaceSwipeCoordinator()
        val actions = mutableListOf<String>()
        compose.setContent { CmuxTheme {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalWorkspaceSwipeCoordinator provides coordinator) {
                Column(Modifier.width(360.dp)) {
                    for (owner in listOf("a", "b")) {
                        CompositionLocalProvider(LocalWorkspaceSwipeKey provides owner) {
                            Box(Modifier.testTag("owner:$owner")) {
                                NativeWorkspaceRow(workspace, canReadState = true, canClose = true,
                                    onOpen = {}, onAction = { action, _ -> actions += "$owner:$action" })
                            }
                        }
                    }
                }
            }
        } }
        compose.onNodeWithTag("owner:a").performTouchInput { swipe(center, center - Offset(width * .3f, 0f), 400) }
        compose.onAllNodesWithText("Mark as Read").assertCountEquals(1)
        compose.onNodeWithTag("owner:b").performTouchInput { swipe(center, center - Offset(width * .3f, 0f), 400) }
        compose.onAllNodesWithText("Mark as Read").assertCountEquals(1)
        compose.onNodeWithText("Mark as Read").performClick()
        assertEquals(listOf("b:mark_read"), actions)
    }

    @Test fun revealedReadIntentSurvivesUnreadRefreshAndRevokedCachedActionsAreInert() {
        var row by mutableStateOf(workspace)
        var enabled by mutableStateOf(true)
        val actions = mutableListOf<String>()
        compose.setContent { CmuxTheme { Column(Modifier.width(360.dp)) {
            NativeWorkspaceRow(row, canReadState = enabled, canClose = enabled,
                onOpen = {}, onAction = { action, _ -> actions += action })
        } } }
        val swipe = compose.onNodeWithTag("workspace.swipe:swipe")
        swipe.performTouchInput { swipe(center, center + Offset(width * .3f, 0f), 400) }
        compose.onNodeWithText("Mark as Read").assertIsDisplayed()
        compose.runOnIdle { row = row.copy(hasUnread = false) }
        compose.onNodeWithText("Mark as Read").assertIsDisplayed()
        compose.onNodeWithText("Mark as Read").performClick()
        compose.runOnIdle { assertEquals(listOf("mark_read"), actions) }
        val cached = compose.onNodeWithTag("workspace.row:swipe").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        val cachedRead = cached.single { it.label == "Mark as Unread" }
        val cachedClose = cached.single { it.label == "Delete workspace" }
        compose.runOnIdle { enabled = false }
        compose.waitForIdle()
        compose.runOnUiThread {
            assertFalse(cachedRead.action())
            assertFalse(cachedClose.action())
        }
        compose.onNodeWithText("Delete Workspace?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("mark_read"), actions) }
    }

    @Test fun capabilityLossDismissesPendingCloseWithoutDispatching() {
        var enabled by mutableStateOf(true)
        val actions = mutableListOf<String>()
        compose.setContent { CmuxTheme { Column(Modifier.width(360.dp)) {
            NativeWorkspaceRow(workspace, canClose = enabled,
                onOpen = {}, onAction = { action, _ -> actions += action })
        } } }
        compose.onNodeWithTag("workspace.swipe:swipe").performTouchInput { swipeLeft() }
        val confirm = compose.onNodeWithTag("workspace.close.confirm").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { enabled = false }
        compose.onNodeWithText("Delete Workspace?").assertDoesNotExist()
        compose.runOnUiThread { confirm() }
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "workspace-swipe").apply { mkdirs() }
        compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun realRowSwipesDoNotReorderAndLongHeldVerticalDragStillMoves() {
        val mac = NativeCredentialStore.PairedMac("test", "test", "Test Mac")
        val source = NativeFeedSource(mac, workspaces = (0..2).map { workspace.copy(id = "w$it", title = "Workspace $it") })
        val moves = mutableListOf<String>()
        val actions = mutableListOf<String>()
        compose.setContent { CmuxTheme {
            NativeWorkspaceDragList(workspaceHierarchy(source), true, Modifier.width(360.dp).height(420.dp).testTag("list"),
                onMove = { _, id, _ -> moves += id; true }, empty = {}) { entry ->
                val row = (entry as WorkspaceListEntry.Workspace).workspace
                NativeWorkspaceRow(row, canReadState = true, canClose = true,
                    onOpen = {}, onAction = { action, _ -> actions += action })
            }
        } }
        compose.onNodeWithTag("workspace.swipe:w0").performTouchInput { swipeRight() }
        assertEquals(listOf("mark_read"), actions); assertTrue(moves.isEmpty())
        val list = compose.onNodeWithTag("list")
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("workspace.row:w0").fetchSemanticsNode().boundsInRoot.center - listBounds.topLeft
        val third = compose.onNodeWithTag("workspace.row:w2").fetchSemanticsNode().boundsInRoot.center - listBounds.topLeft
        list.performTouchInput { down(first); advanceEventTime(650); moveTo(third, delayMillis = 150); up() }
        compose.runOnIdle { assertEquals(listOf("w0"), moves); assertEquals(listOf("mark_read"), actions) }
    }
}
