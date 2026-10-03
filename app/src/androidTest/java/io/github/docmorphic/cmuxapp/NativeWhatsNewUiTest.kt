package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeWhatsNewUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Store : WhatsNewStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(updates: Map<String, String?>): Boolean {
            updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }; return true
        }
    }
    private fun capture(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val directory = java.io.File(i.targetContext.getExternalFilesDir(null), "whats-new").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun visibleLaunchAcknowledgesAllPagesThenSwipeContinueDismissWithoutLosingArchive() {
        val store = Store()
        val center = NativeWhatsNewCenter(NativeWhatsNewCatalog.pages, "0.2.0", WhatsNewChannel.DEV, store)
        runBlocking { center.refresh() }
        val presentation = NativeWhatsNewPresentation(center)
        var blocked by mutableStateOf(true)
        compose.setContent { CmuxTheme {
            NativeWhatsNewHost(center, presentation, "account", !blocked, false, {}, NativeMacCompatibilityPolicy.baked)
        } }
        compose.onNodeWithTag("whatsnew.sheet").assertDoesNotExist()
        compose.runOnIdle { assertNull(store.values[NativeWhatsNewCenter.MARKER]); blocked = false }
        compose.onNodeWithTag("whatsnew.sheet").assertIsDisplayed()
        compose.waitUntil(5000) { presentation.state.value?.appeared == true }
        compose.runOnIdle { assertTrue(center.state.value.unseen.isEmpty()); assertEquals(2, center.state.value.archive.size) }
        capture("launch")
        compose.onNodeWithTag("whatsnew.pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("whatsnew.title.entry:android.pairing.iroh-v2").assertIsDisplayed()
        compose.onNodeWithTag("whatsnew.pairing.image").performScrollTo().assertIsDisplayed()
        capture("pairing")
        compose.onNodeWithTag("whatsnew.continue").assertIsDisplayed().performClick()
        compose.onNodeWithTag("whatsnew.sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, center.state.value.archive.size) }
    }
    @Test fun archiveBackAndRestorationKeepDetailWithoutAcknowledgement() {
        var close = 0
        val restore = StateRestorationTester(compose)
        restore.setContent { CmuxTheme {
            NativeWhatsNewArchive(NativeWhatsNewCatalog.pages, NativeMacCompatibilityPolicy.baked) { close++ }
        } }
        compose.onNodeWithTag("whatsnew.archive.entry:android.introduction.0.2.0").performClick()
        compose.onNodeWithTag("whatsnew.title.entry:android.introduction.0.2.0").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("whatsnew.title.entry:android.introduction.0.2.0").assertIsDisplayed()
        capture("archive-detail")
        compose.onNodeWithTag("whatsnew.archive.back").performClick()
        compose.onNodeWithTag("whatsnew.archive.entry:android.pairing.iroh-v2").assertIsDisplayed()
        capture("archive")
        compose.onNodeWithTag("whatsnew.archive.back").performClick()
        compose.runOnIdle { assertEquals(1, close) }
    }
    @Test fun continueAdvancesAndOwnerChangeRetiresOldPresentation() {
        val store = Store()
        val center = NativeWhatsNewCenter(NativeWhatsNewCatalog.pages, "0.2.0", WhatsNewChannel.DEV, store)
        runBlocking { center.refresh() }
        val presentation = NativeWhatsNewPresentation(center)
        var owner by mutableStateOf<String?>("account")
        compose.setContent { CmuxTheme {
            NativeWhatsNewHost(center, presentation, owner, true, false, {}, NativeMacCompatibilityPolicy.baked)
        } }
        compose.waitUntil(5000) { presentation.state.value?.appeared == true }
        compose.onNodeWithTag("whatsnew.continue").performClick()
        compose.onNodeWithTag("whatsnew.title.entry:android.pairing.iroh-v2").assertIsDisplayed()
        compose.runOnIdle { owner = null }
        compose.onNodeWithTag("whatsnew.sheet").assertDoesNotExist()
        compose.runOnIdle { assertNull(presentation.state.value) }
    }
}
