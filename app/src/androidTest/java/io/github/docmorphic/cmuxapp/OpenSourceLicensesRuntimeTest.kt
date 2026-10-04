package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class OpenSourceLicensesRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun packagedLicensesRemainCompleteAndLargeDocumentAndEndAreReachable() {
        val assets = compose.activity.assets
        licenseAssets.forEach { name ->
            val original = assets.open("licenses/$name").bufferedReader().use { it.readText() }
            assertTrue("Empty license: $name", original.isNotEmpty())
            assertEquals(original, licenseSections(original).joinToString(""))
        }
        var visible by mutableStateOf(true)
        compose.setContent { MaterialTheme { if (visible) OpenSourceLicensesDialog { visible = false } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("license-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("NOTICE.txt").assertIsDisplayed()
        compose.onNodeWithTag("license-list").performScrollToNode(hasText("GeckoView.txt"))
        compose.onNodeWithText("GeckoView.txt").assertIsDisplayed()
        compose.onNodeWithTag("license-list").performScrollToNode(hasText("End of licenses"))
        compose.onNodeWithText("End of licenses").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Open-source licenses").assertDoesNotExist()
    }

    @Test fun dismissWhileLoadingCancelsPendingWork() {
        val waiting = CompletableDeferred<List<LicenseDocument>>()
        val started = AtomicBoolean()
        val cancelled = AtomicBoolean()
        var visible by mutableStateOf(true)
        val load: suspend () -> List<LicenseDocument> = {
            started.set(true)
            try { waiting.await() } finally { cancelled.set(true) }
        }
        compose.setContent { MaterialTheme { if (visible) OpenSourceLicensesContent(load) { visible = false } } }
        compose.waitUntil { started.get() }
        compose.onNodeWithText("Loading licenses…").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil { cancelled.get() }
        compose.onNodeWithText("Open-source licenses").assertDoesNotExist()
    }

    @Test fun failedLoadingCanRetryAndStillDismiss() {
        var attempts = 0
        val load: suspend () -> List<LicenseDocument> = {
            if (++attempts == 1) error("fixture failure")
            listOf(LicenseDocument("Recovered license", listOf("Complete text")))
        }
        var visible by mutableStateOf(true)
        compose.setContent { MaterialTheme { if (visible) OpenSourceLicensesContent(load) { visible = false } } }
        compose.onNodeWithText("Licenses could not be loaded.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Complete text").assertIsDisplayed()
        assertEquals(2, attempts)
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Open-source licenses").assertDoesNotExist()
    }
}
