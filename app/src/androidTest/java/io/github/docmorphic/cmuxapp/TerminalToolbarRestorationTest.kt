package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalToolbarRestorationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val preferences get() = compose.activity.getSharedPreferences("toolbar_restoration_fixture", Context.MODE_PRIVATE)
    private lateinit var store: TerminalToolbarStore
    @After fun cleanup() { preferences.edit().clear().commit() }
    private fun content(seed: TerminalToolbarLayout = TerminalToolbarLayout.defaults()): StateRestorationTester {
        preferences.edit().putString(TerminalToolbarLayout.PREFERENCE, seed.encode()).commit()
        return StateRestorationTester(compose).also { restore -> restore.setContent {
            store = rememberTerminalToolbar(preferences)
            CmuxTheme { TerminalToolbarSettings(store) {} }
        } }
    }
    private fun edit(title: String) {
        compose.onNodeWithTag("terminal-shortcut-list").performScrollToNode(hasContentDescription("Edit $title"))
        compose.onNodeWithContentDescription("Edit $title").performClick()
    }
    private fun action() = TerminalToolbarAction("da4cd09c-9af8-4e71-b876-061c5c31a6b1", "Status", "pwd\n")
    private fun saved() = TerminalToolbarLayout.decode(preferences.getString(TerminalToolbarLayout.PREFERENCE, null))

    @Test fun newActionDraftAndReturnChoiceSurviveRestorationUntilExplicitSave() {
        val restore = content()
        compose.onNodeWithText("Add Custom Action").performClick()
        compose.onNodeWithTag("shortcut-action-title").performTextInput("Inspect 中")
        compose.onNodeWithTag("shortcut-action-text").performTextInput("pwd\nprintf 'hello'\n")
        compose.onNodeWithContentDescription("Run after typing").performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Add Action").assertExists()
        compose.onNodeWithTag("shortcut-action-title").assertTextContains("Inspect 中")
        compose.onNodeWithTag("shortcut-action-text").assertTextContains("pwd\nprintf 'hello'\n")
        compose.onNodeWithContentDescription("Run after typing").assertIsOff()
        compose.runOnIdle { assertTrue(saved().actions.isEmpty()) }
        compose.onNodeWithText("Save").performClick()
        val persisted = saved().actions.single()
        assertEquals("Inspect 中", persisted.title)
        assertEquals("pwd\nprintf 'hello'\n", persisted.text)
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Add Action").assertDoesNotExist()
        assertEquals(listOf(persisted), saved().actions)
    }

    @Test fun editingSurvivesRestorationAndCancelDoesNotOverwriteTheStoredAction() {
        val action = action()
        val seed = TerminalToolbarLayout.defaults().save(action).move(action.itemId, 0).toggle(action.itemId, false)
        val restore = content(seed)
        edit("Status")
        compose.onNodeWithTag("shortcut-action-title").performTextReplacement("Pending")
        compose.onNodeWithTag("shortcut-action-text").performTextReplacement("echo 你好")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Edit Action").assertExists()
        compose.onNodeWithTag("shortcut-action-title").assertTextContains("Pending")
        compose.onNodeWithTag("shortcut-action-text").assertTextContains("echo 你好")
        compose.onNodeWithContentDescription("Run after typing").assertIsOn()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(seed, saved())
        edit("Status")
        compose.onNodeWithTag("shortcut-action-title").assertTextContains("Status")
        compose.onNodeWithTag("shortcut-action-text").assertTextContains("pwd")
        compose.onNodeWithTag("shortcut-action-text").performTextReplacement("echo final")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Save").performClick()
        assertEquals(action.id, saved().actions.single().id)
        assertEquals("echo final\n", saved().actions.single().text)
        assertEquals(seed.order, saved().order)
        assertEquals(seed.enabled, saved().enabled)
    }

    @Test fun removingAnActionElsewhereRetiresItsOpenEditorAndSavedRoute() {
        val action = action()
        val restore = content(TerminalToolbarLayout.defaults().save(action).move(action.itemId, 0))
        edit("Status")
        compose.onNodeWithTag("shortcut-action-text").performTextReplacement("obsolete draft")
        compose.runOnIdle { store.save(store.layout.remove(action.itemId)) }
        compose.onNodeWithText("Edit Action").assertDoesNotExist()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Edit Action").assertDoesNotExist()
        assertTrue(saved().actions.isEmpty())
    }

    @Test fun unreadableStoragePreventsAnOpenEditorFromOverwritingIt() {
        val action = action()
        content(TerminalToolbarLayout.defaults().save(action).move(action.itemId, 0))
        edit("Status")
        compose.onNodeWithTag("shortcut-action-text").performTextReplacement("unsaved")
        compose.runOnIdle { preferences.edit().putString(TerminalToolbarLayout.PREFERENCE, "{broken").commit() }
        compose.waitUntil(5000) { store.error != null }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        assertEquals("{broken", preferences.getString(TerminalToolbarLayout.PREFERENCE, null))
    }
}
