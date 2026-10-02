package io.github.docmorphic.cmuxapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class NativeNotificationSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun systemKeepsExistingChannelChoicesAndNewOngoingChannelsExcludeBadges() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val fresh = "badge-fixture-${UUID.randomUUID()}"
        val existing = "badge-fixture-${UUID.randomUUID()}"
        try {
            NativeOngoingNotifications.register(manager, fresh, "Fixture ongoing")
            assertFalse(manager.getNotificationChannel(fresh).canShowBadge())
            assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(fresh).importance)
            // Model an installed channel with a muted, badge-enabled configuration.
            manager.createNotificationChannel(NotificationChannel(existing, "Previous name", NotificationManager.IMPORTANCE_NONE).apply {
                setShowBadge(true); setSound(null, null); enableVibration(false)
            })
            NativeOngoingNotifications.register(manager, existing, "Fixture existing")
            val preserved = manager.getNotificationChannel(existing)
            assertEquals(NotificationManager.IMPORTANCE_NONE, preserved.importance)
            assertTrue(preserved.canShowBadge())
            assertNull(preserved.sound)
            assertFalse(preserved.shouldVibrate())
            assertEquals("Fixture existing", preserved.name.toString())
            assertEquals(setOf(fresh, existing), manager.notificationChannels.map { it.id }.filter { it == fresh || it == existing }.toSet())
        } finally { manager.deleteNotificationChannel(fresh); manager.deleteNotificationChannel(existing) }
    }

    @Test fun settingsButtonOpensThisAppsSystemNotificationPage() {
        compose.setContent { MaterialTheme { NativeNotificationSettings() } }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            compose.waitForIdle()
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "notification-app-settings.png"))
            compose.onNodeWithText("Android notification settings").performClick()
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 5_000))
            val label = context.applicationInfo.loadLabel(context.packageManager).toString()
            // API 37 exposes the collapsing app header as an accessibility
            // description, not a text node.
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings").desc(label)), 5_000))
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "notification-system-settings.png"))
        } finally { device.pressBack() }
        compose.onNodeWithText("Android notification settings").assertIsDisplayed()
    }

    @Test fun unavailableSettingsExplainsRecoveryAndChannelLinksCarryOnlySystemScope() {
        var requested: Intent? = null
        compose.setContent { MaterialTheme { NativeNotificationSettings {
            requested = it
            throw android.content.ActivityNotFoundException()
        } } }
        compose.onNodeWithText("Android notification settings").performClick()
        compose.onNodeWithText("Could not open Android settings. Open Settings › Apps › cmux › Notifications on your phone.").assertIsDisplayed()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, requested!!.action)
        assertEquals(context.packageName, requested!!.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        for (id in listOf(NativeOngoingNotifications.CONNECTION, NativeOngoingNotifications.REPLY)) {
            val channel = nativeNotificationSettingsIntent(context, id)
            assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channel.action)
            assertEquals(context.packageName, channel.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            assertEquals(id, channel.getStringExtra(Settings.EXTRA_CHANNEL_ID))
            assertEquals(setOf(Settings.EXTRA_APP_PACKAGE, Settings.EXTRA_CHANNEL_ID), channel.extras!!.keySet())
            assertNull(channel.data)
        }
    }
}
