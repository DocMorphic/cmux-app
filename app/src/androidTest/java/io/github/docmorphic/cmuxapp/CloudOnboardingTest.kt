package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CloudOnboardingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun failedCompletionStaysInlineAndRetryPersistsAcrossRecreation() {
        var saved = false
        var allowWrite = false
        val store = CloudOnboardingStore({ saved }, { if (allowWrite) { saved = true; true } else false })
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CloudOnboardingFrame { NativeCloudFlow(null, {}, {}, {}, progressStore = store) } }
        compose.onNodeWithTag("cloud.introduction.inline").assertIsDisplayed()
        compose.onNodeWithTag("cloud.introduction.skip").performClick()
        compose.onNodeWithText("Could not save Cloud introduction progress. Try again.").assertIsDisplayed()
        assertFalse(saved)
        allowWrite = true
        compose.onNodeWithTag("cloud.introduction.skip").performClick()
        compose.onNodeWithTag("cloud.screen").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("cloud.screen").assertIsDisplayed()
        compose.onNodeWithTag("cloud.introduction.inline").assertDoesNotExist()
        assertTrue(saved)
    }

    @Test fun pagingRestoresAndOnlyGetStartedCompletes() {
        var saved = false
        var writes = 0
        val store = CloudOnboardingStore({ saved }, { writes++; saved = true; true })
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CloudOnboardingFrame { NativeCloudFlow(null, {}, {}, {}, progressStore = store) } }
        compose.onNodeWithTag("cloud.introduction.next").performClick()
        compose.onNodeWithTag("cloud.introduction.progress").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Step 2 of 3"))
        compose.onNodeWithText("System VPN controls are unavailable in this session.").assertDoesNotExist()
        compose.onNodeWithTag("cloud.introduction.graphic.1").assertIsDisplayed()
        captureCloudScreen("cloud-basics-vpn")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("cloud.introduction.progress").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Step 2 of 3"))
        compose.onNodeWithTag("cloud.introduction.next").performClick()
        compose.onNodeWithText("Get started").assertIsDisplayed()
        assertEquals(0, writes)
        compose.onNodeWithTag("cloud.introduction.back").performClick()
        compose.onNodeWithTag("cloud.introduction.progress").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Step 2 of 3"))
        compose.onNodeWithTag("cloud.introduction.next").performClick()
        compose.onNodeWithTag("cloud.introduction.next").performClick()
        assertTrue(saved); assertEquals(1, writes)
        compose.onNodeWithTag("cloud.screen").assertIsDisplayed()
    }

    @Test fun replayDismissesWithoutWritingOrMutatingMachines() {
        var writes = 0
        val store = CloudOnboardingStore({ true }, { writes++; true })
        compose.setContent { CloudOnboardingFrame { NativeCloudFlow(null, {}, {}, {}, progressStore = store) } }
        compose.onNodeWithTag("cloud.basics").performClick()
        compose.onNodeWithTag("cloud.introduction.replay").assertIsDisplayed()
        compose.onNodeWithTag("cloud.introduction.next").performClick()
        compose.onNodeWithTag("cloud.introduction.next").performClick()
        compose.onNodeWithTag("cloud.introduction.next").performClick()
        compose.onNodeWithTag("cloud.introduction.replay").assertDoesNotExist()
        compose.onNodeWithTag("cloud.screen").assertIsDisplayed()
        assertEquals(0, writes)
    }
}

@Composable private fun CloudOnboardingFrame(content: @Composable () -> Unit) {
    CmuxTheme(darkTheme = true) { Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() }
    } }
}
