package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NativeAccountTeamSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun createTrimsNameAndDisablesDuplicateSubmissionUntilConfirmed() {
        val complete = CompletableDeferred<Boolean>()
        val names = mutableListOf<String>()
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) { Column {
            NativeAccountTeamSection(NativeAccountTeamsState(userId = "fixture-user"), {}, {},
                onCreate = { names += it; complete.await() })
        } } } }
        compose.onNodeWithText("Create Team").performClick()
        compose.onNodeWithText("Create").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("  Team Android  ")
        compose.onNodeWithText("Create").performClick()
        compose.onNodeWithText("Creating…").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        capture("team-create-pending")
        compose.runOnIdle { assertEquals(listOf("Team Android"), names); complete.complete(true) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Create Team").assertIsDisplayed()
    }

    @Test fun unconfirmedCreationRetainsNameAndWarningWithoutAutomaticRetry() {
        var state by mutableStateOf(NativeAccountTeamsState(userId = "fixture-user"))
        var attempts = 0
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) { Column {
            NativeAccountTeamSection(state, {}, {}, onCreate = {
                attempts++
                state = state.copy(error = "Team creation was not confirmed. Refresh your account before trying again.")
                false
            })
        } } } }
        compose.onNodeWithText("Create Team").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Keep this name")
        compose.onNodeWithText("Create").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Keep this name")
        compose.onAllNodesWithText(state.error!!).filterToOne(hasAnyAncestor(isDialog())).assertIsDisplayed()
        capture("team-create-unconfirmed")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, attempts) }
    }

    @Test fun accountMustBeVerifiedBeforeCreationAndExistingTeamSelectionStillWorks() {
        var state by mutableStateOf(NativeAccountTeamsState())
        var selected: String? = null
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) { Column {
            NativeAccountTeamSection(state, {}, { selected = it }, onCreate = { error("Not submitted") })
        } } } }
        compose.onNodeWithText("Create Team").assertIsNotEnabled()
        compose.runOnIdle { state = NativeAccountTeamsState(userId = "fixture-user",
            teams = listOf(NativeTeam("one", "First team"), NativeTeam("two", "Second team")), selectedTeamId = "one") }
        compose.onNodeWithText("Create Team").assertIsEnabled()
        compose.onNodeWithText("First team").performClick()
        compose.onNodeWithText("Second team").performClick()
        compose.runOnIdle { assertEquals("two", selected) }
    }
    private fun capture(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        try { java.io.File(directory, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }

}
