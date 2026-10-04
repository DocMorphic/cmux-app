package io.github.docmorphic.cmuxapp

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.MailTo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NativeLegalSupportSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun linksUseBrowsersOrEmailDraftAndMissingHandlersStayInSettings() {
        val launched = mutableListOf<Intent>()
        var fail = false
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            NativeLegalSupportSettings { if (fail) throw ActivityNotFoundException() else launched += it }
        } } }
        compose.onNodeWithTag("settings.link.privacy").performClick()
        compose.onNodeWithTag("settings.link.terms").performClick()
        compose.onNodeWithTag("settings.link.support").performClick()
        compose.runOnIdle {
            assertEquals(listOf(Intent.ACTION_VIEW, Intent.ACTION_VIEW, Intent.ACTION_SENDTO), launched.map { it.action })
            assertEquals("https://cmux.com/privacy-policy", launched[0].dataString)
            assertEquals("https://cmux.com/terms-of-service", launched[1].dataString)
            val mail = MailTo.parse(launched[2].dataString)
            assertEquals("feedback@manaflow.com", mail.to)
            assertEquals("cmux Android support", mail.subject)
            assertNull(mail.body)
            assertTrue(launched.all { it.extras == null })
            fail = true
        }
        compose.onNodeWithTag("settings.link.support").performClick()
        compose.onNodeWithTag("settings.link.error").assertTextContains("No email app is available. Contact feedback@manaflow.com.")
        compose.onNodeWithTag("settings.link.privacy").performClick()
        compose.onNodeWithTag("settings.link.error").assertTextContains("No browser is available. Open https://cmux.com/privacy-policy in a browser.")
        compose.runOnIdle { assertEquals(3, launched.size) }
    }

    @Test fun copiedReportUsesCurrentSessionAndSurvivesLargeTextAndClipboardFailure() {
        var session = NativeSupportSession("first-account", "first-team", true, "iroh")
        val copies = mutableListOf<ClipData>()
        var fail = false
        compose.setContent { CmuxTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
                        NativeLegalSupportSettings {}
                        NativeAboutSettings({ session }) { if (fail) throw IllegalStateException() else copies += it }
                    }
                }
            }
        } }
        compose.onNodeWithTag("settings.support.copy").performScrollTo().performClick()
        compose.onNodeWithText("Copied").assertExists()
        compose.runOnIdle {
            val clip = copies.single()
            assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
            val value = clip.getItemAt(0).text.toString()
            assertTrue(value.contains("Account ID: first-account")); assertTrue(value.contains("Team ID: first-team"))
            assertTrue(value.contains("Transport: iroh")); assertTrue(value.contains("Bundle ID: ${compose.activity.packageName}"))
            assertTrue(value.contains("Source Revision: ${BuildConfig.SOURCE_REVISION}"))
            session = NativeSupportSession("second-account", "second-team", false, "iroh")
        }
        compose.onNodeWithTag("settings.support.copy").performClick()
        compose.runOnIdle {
            val value = copies.last().getItemAt(0).text.toString()
            assertFalse(value.contains("first-account")); assertFalse(value.contains("first-team"))
            assertTrue(value.contains("Account ID: second-account")); assertTrue(value.contains("Transport: <unavailable>"))
            fail = true
        }
        compose.onNodeWithTag("settings.support.copy").performClick()
        compose.onNodeWithTag("settings.support.error").assertExists()
        compose.onNodeWithText("Copied").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, copies.size); fail = false }
        compose.onNodeWithTag("settings.support.copy").performClick()
        compose.mainClock.advanceTimeBy(2_100)
        compose.waitForIdle()
        compose.onNodeWithText("Copy Support Information").assertExists()
        compose.onNodeWithTag("settings.version").performScrollTo().assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "support-settings").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(folder, "large-text.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
