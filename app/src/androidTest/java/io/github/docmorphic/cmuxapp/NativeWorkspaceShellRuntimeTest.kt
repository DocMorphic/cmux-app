package io.github.docmorphic.cmuxapp

import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeWorkspaceShellRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sidebarViewportAndDraftSurviveHiddenRestorationCompactDetailAndResetForNewOwner() {
        var owner by mutableStateOf("account-a")
        var width by mutableIntStateOf(1000)
        var selected by mutableStateOf(true)
        var disposed = 0
        val restore = StateRestorationTester(compose)
        restore.setContent { CmuxTheme {
            NativeWorkspaceShell(owner, selected, modifier = Modifier.fillMaxSize(), widthDp = width, heightDp = 700,
                sidebar = {
                    DisposableEffect(Unit) { onDispose { disposed++ } }
                    NativeWorkspaceSidebarToggle()
                    LazyColumn(Modifier.weight(1f).testTag("retained.sidebar.list"), state = rememberLazyListState()) {
                        items(20) { Text("Workspace $it", Modifier.fillMaxWidth().height(80.dp)) }
                        item {
                            var draft by rememberSaveable { mutableStateOf("") }
                            TextField(draft, { draft = it }, label = { Text("Sidebar draft") })
                        }
                    }
                }, detail = { NativeWorkspaceBackControl { Text("Back") }; Text("Detail") })
        } }
        compose.onNodeWithTag("retained.sidebar.list").performScrollToIndex(20)
        compose.onNode(hasSetTextAction()).performTextInput("Unsubmitted answer")
        compose.onNodeWithContentDescription("Hide sidebar").performClick()
        compose.onNodeWithText("Unsubmitted answer").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, disposed) }
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("workspace.shell.sidebar").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show sidebar").performClick()
        compose.onNodeWithText("Unsubmitted answer").assertIsDisplayed()
        compose.runOnIdle { width = 412 }
        compose.onNodeWithText("Unsubmitted answer").assertDoesNotExist()
        compose.runOnIdle { selected = false }
        compose.onNodeWithText("Unsubmitted answer").assertIsDisplayed()
        compose.runOnIdle { owner = "account-b" }
        compose.onNodeWithText("Unsubmitted answer").assertDoesNotExist()
        compose.onNodeWithText("Workspace 0").assertIsDisplayed()
        compose.onNodeWithTag("retained.sidebar.list").performScrollToIndex(20)
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }

    @Test fun rendererAndDraftSurviveReflowSidebarToggleAndTabSearch() {
        var width by mutableIntStateOf(1000); var height by mutableIntStateOf(700)
        var tab by mutableStateOf(false); var search by mutableStateOf(NativeSearchState())
        var created = 0; var released = 0; var mounted = 0; var disposed = 0
        var firstView: TextView? = null
        compose.setContent { CmuxTheme {
            NativeWorkspaceShell("account/team", true, modifier = Modifier.fillMaxSize(), widthDp = width, heightDp = height,
                sidebar = {
                    NativeWorkspaceSidebarToggle()
                    Text(if (tab) "Notification list" else "Workspace list", Modifier.weight(1f))
                    NativePrimaryNavigation(tab, 2, search, { tab = it }, {
                        search = search.begin(if (tab) NativeSearchScope.NOTIFICATIONS else NativeSearchScope.WORKSPACES)
                    }, { value, generation -> search = search.edit(value,
                        if (tab) NativeSearchScope.NOTIFICATIONS else NativeSearchScope.WORKSPACES, generation) },
                        { search = search.commit() }, { search = search.clear(if (tab) NativeSearchScope.NOTIFICATIONS else NativeSearchScope.WORKSPACES) }, sidebar = true)
                }, detail = {
                    DisposableEffect(Unit) { mounted++; onDispose { disposed++ } }
                    NativeWorkspaceBackControl { TextButton(onClick = {}) { Text("Back") } }
                    var draft by rememberSaveable { mutableStateOf("") }
                    TextField(draft, { draft = it }, label = { Text("Draft") })
                    AndroidView(factory = { context -> TextView(context).also { created++; firstView = it; it.text = "Terminal renderer" } },
                        modifier = Modifier.fillMaxWidth().height(100.dp), onRelease = { released++ })
                })
        } }
        compose.onNode(hasSetTextAction()).performTextInput("unsent command")
        compose.onNodeWithContentDescription("Hide sidebar").performClick()
        compose.onNodeWithTag("workspace.shell.sidebar").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show sidebar").performClick()
        val renderer = firstView
        compose.runOnIdle { width = 412 }
        compose.onNodeWithTag("workspace.shell.stack").assertExists()
        compose.onNodeWithText("Back").assertIsDisplayed()
        compose.onNodeWithText("unsent command").assertExists()
        compose.runOnIdle { width = 1000; height = 420 }
        compose.onNodeWithTag("workspace.shell.stack").assertExists()
        compose.runOnIdle { height = 700 }
        compose.onNodeWithText("Notifications (2)").performClick()
        compose.onNodeWithText("Notification list").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("workspace.shell.sidebar"))).performTextInput("build")
        compose.onNodeWithContentDescription("Cancel search").performClick()
        compose.onNodeWithText("unsent command").assertExists()
        compose.runOnIdle {
            assertSame(renderer, firstView); assertEquals(1, created); assertEquals(0, released)
            assertEquals(1, mounted); assertEquals(0, disposed)
        }
    }

    @Test fun visibilityRestoresForSameOwnerAndResetsAcrossAccountsWithEmptySelectionEscape() {
        var owner by mutableStateOf("account-a"); var selected by mutableStateOf(false)
        val restore = StateRestorationTester(compose)
        restore.setContent { CmuxTheme {
            NativeWorkspaceShell(owner, selected, modifier = Modifier.fillMaxSize(), widthDp = 1000, heightDp = 700,
                sidebar = { NativeWorkspaceSidebarToggle(); Button(onClick = { selected = true }) { Text("Open workspace") } },
                detail = { NativeWorkspaceBackControl { Text("Back") }; Text("Selected workspace") })
        } }
        compose.onNodeWithTag("workspace.shell.placeholder").assertExists()
        compose.onNodeWithContentDescription("Hide sidebar").performClick()
        compose.onNodeWithContentDescription("Show sidebar").performClick()
        compose.onNodeWithText("Open workspace").performClick()
        compose.onNodeWithContentDescription("Hide sidebar").performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("workspace.shell.sidebar").assertDoesNotExist()
        compose.onNodeWithText("Selected workspace").assertIsDisplayed()
        compose.runOnIdle { owner = "account-b" }
        compose.onNodeWithTag("workspace.shell.sidebar").assertIsDisplayed()
    }
}
