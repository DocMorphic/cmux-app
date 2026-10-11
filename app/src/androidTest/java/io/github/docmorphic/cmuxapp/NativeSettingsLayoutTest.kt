package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeSettingsLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsHeaderStaysVisibleWhileBodyScrollsAndDoneDispatchesOnce() {
        var dismissals = 0
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeSettingsLayout({ dismissals++ }, account = { Text("Account first", Modifier.testTag("order.account")) },
                computers = { Text("Connections second", Modifier.testTag("order.computers")) },
                connections = { Text("Networking third", Modifier.testTag("order.networking")) },
                preferences = { Text("Preferences fourth", Modifier.testTag("order.preferences")) },
                notifications = { Text("Push fifth", Modifier.testTag("order.push")) },
                footer = { repeat(40) { Text("Footer row $it", Modifier.fillMaxWidth().height(48.dp)) }
                    Text("Footer last", Modifier.testTag("order.footer")) })
        } } }
        val tags = listOf("order.account", "order.computers", "order.networking", "order.preferences", "order.push")
        val positions = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(positions.sorted(), positions)
        val before = compose.onNodeWithTag("settings.header").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("order.footer").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings.done").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
        assertEquals(before, compose.onNodeWithTag("settings.header").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Account first").assertIsNotDisplayed()
    }

    @Test fun headerGrowsForLargeTextAndAccountActionsPrecedeInlineTeams() {
        compose.setContent { CmuxTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Surface { Column(Modifier.width(260.dp).height(720.dp)) {
                    NativeSettingsLayout({}, account = {
                        NativeAccountTeamSection(NativeAccountTeamsState(userId = "fixture-user", displayName = "Profile first",
                            teams = listOf(NativeTeam("one", "First team"), NativeTeam("two", "Second team")), selectedTeamId = "one"),
                            {}, {}, afterAccount = { Text("Plan before teams", Modifier.testTag("plan")) }, onCreate = { false })
                    }, computers = {}, connections = {}, preferences = {}, notifications = {})
                } }
            }
        } }
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Settings").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertFalse(layout.single().hasVisualOverflow)
        compose.onNodeWithTag("settings.done").assertIsDisplayed()
        val plan = compose.onNodeWithTag("plan").fetchSemanticsNode().boundsInRoot.top
        val teams = compose.onNodeWithTag("settings.team:one").fetchSemanticsNode().boundsInRoot.top
        assertTrue(plan < teams)
    }
}
