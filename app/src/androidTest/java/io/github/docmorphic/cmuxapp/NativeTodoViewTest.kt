package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeTodoViewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun disconnectedChecklistKeepsSnapshotAndDisablesEveryMutationPath() {
        var online by mutableStateOf(true)
        var sends = 0
        val initial = TodoSnapshot(TodoStatus.REVIEW, false, listOf(TodoItem("a", "Review changes", TodoItemState.PENDING, "agent")))
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeTodoView(initial, online) { sends++; initial }
        } } }
        compose.onNodeWithText("Review changes").assertIsDisplayed()
        compose.runOnIdle { online = false }
        compose.onNodeWithText("Review changes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose status").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Mark Review changes as in progress").assertIsNotEnabled()
        compose.onNodeWithTag("todo-new-item").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Add checklist item").assertIsNotEnabled()
        compose.onNodeWithTag("todo-row-a").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Review changes").performTouchInput { click() }
        compose.onNodeWithTag("todo-edit-a").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, sends); online = true }
        compose.onNodeWithContentDescription("Mark Review changes as in progress").assertIsEnabled()
    }

    @Test fun itemTextLimitPreservesExtendedEmojiAndCombiningClusters() {
        val family = "👨‍👩‍👧‍👦"
        val accent = "e\u0301"
        assertEquals(family.repeat(500), normalizedTodoText("  " + family.repeat(501) + "  "))
        assertEquals(accent.repeat(500), normalizedTodoText(accent.repeat(501)))
        assertEquals("🇮🇳".repeat(500), normalizedTodoText("🇮🇳".repeat(501)))
        assertEquals("👩🏽‍💻", normalizedTodoText("👩🏽‍💻"))
    }
}
