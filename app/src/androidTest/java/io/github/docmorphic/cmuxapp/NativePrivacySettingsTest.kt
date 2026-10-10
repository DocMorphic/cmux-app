package io.github.docmorphic.cmuxapp

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class NativePrivacySettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun storedConsentDefaultsPersistsAndOffMainRevocationCancelsWithoutWaitingForListener() {
        val name = "privacy-test-${UUID.randomUUID()}"
        val prefs = compose.activity.getSharedPreferences(name, Context.MODE_PRIVATE)
        var consent = NativePrivacyConsent(prefs)
        try {
            assertTrue(consent.capture().enabled)
            prefs.edit().putString(NativePrivacyConsent.telemetryKey, "invalid").commit()
            assertTrue(consent.capture().enabled)
            consent.setEnabled(false)
            consent.close()
            consent = NativePrivacyConsent(prefs)
            assertFalse(consent.capture().enabled)
            consent.setEnabled(true)
            val old = consent.capture()
            val pending = Job()
            assertTrue(consent.register(pending, old))
            // Run on the main thread so the off-main SharedPreferences listener
            // cannot execute before the direct provider read below.
            compose.runOnIdle {
                val writer = Thread { prefs.edit().putBoolean(NativePrivacyConsent.telemetryKey, false).apply() }
                writer.start(); writer.join(5_000)
                assertFalse(writer.isAlive)
                assertFalse(consent.allows(old))
                assertTrue(pending.isCancelled)
            }
            consent.setEnabled(true)
            assertFalse(consent.allows(old))
            consent.setEnabled(false)
            consent.close()
            assertEquals(false, prefs.all[NativePrivacyConsent.telemetryKey])
        } finally { consent.close(); compose.activity.deleteSharedPreferences(name) }
    }

    @Test fun settingsHasOneSwitchActionWrapsAtLargeFontAndRestoresOptOut() {
        val name = "privacy-ui-test-${UUID.randomUUID()}"
        val prefs = compose.activity.getSharedPreferences(name, Context.MODE_PRIVATE)
        var consent by mutableStateOf(NativePrivacyConsent(prefs))
        try {
            compose.setContent { CmuxTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Surface { Column(Modifier.width(260.dp)) { NativePrivacySettings(consent) } }
                }
            } }
            val row = compose.onNodeWithTag("settings.telemetry")
            row.assertIsOn().performClick().assertIsOff()
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
                .assertCountEquals(1)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Share Anonymous Analytics", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.single().lineCount > 1)
            assertFalse(layouts.single().hasVisualOverflow)
            compose.onNodeWithText("This version does not upload product analytics.").assertIsDisplayed()
            compose.runOnIdle { consent.close(); consent = NativePrivacyConsent(prefs) }
            row.assertIsOff()
            assertFalse(consent.capture().enabled)
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                try { compose.activity.openFileOutput("privacy-large-font.png", Context.MODE_PRIVATE).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                } } finally { bitmap.recycle() }
            }
        } finally { consent.close(); compose.activity.deleteSharedPreferences(name) }
    }
}
