package io.github.docmorphic.cmuxapp

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.KeyStore

/** Explicit emulator-only, two-process acceptance. Run with check-local-reset.py. */
class NativeLocalResetPlatformTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun requireEmulatorPhase(phase: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cmuxResetPhase") == phase)
        check(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone")) {
            "Local reset acceptance is restricted to the disposable Google emulator"
        }
        check(context.packageName == "io.github.docmorphic.cmuxapp.debug")
    }

    @Test fun eraseSeededDataThroughRealConfirmation() {
        requireEmulatorPhase("erase")
        NativeCredentialStore(context).update { it.put("refresh_token", "emulator-reset-fixture-only") }
        context.getSharedPreferences("native_notification_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("background_enabled", true).commit()
        File(context.filesDir, "reset-acceptance-seed").writeText("private fixture")
        File(context.cacheDir, "reset-acceptance-seed").writeText("cache fixture")
        File(requireNotNull(context.getExternalFilesDir(null)), "reset-acceptance-seed").writeText("external fixture")
        assertNotNull(NativeCredentialStore(context).load())
        assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("cmux_native_v1"))
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        compose.setContent { CmuxTheme { Surface { NativeLocalResetSection() } } }
        compose.onNodeWithText("Erase All Data on This Device").performClick()
        compose.onNodeWithText("Erase all cmux data on this device?").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendStatus(1002, Bundle().apply {
            putString("cmuxResetCheckpoint", "seeded-and-confirmation-visible")
        })
        compose.onNodeWithText("Erase").performClick()
        // Android terminates this process. A host runner must independently verify the
        // restarted app; process termination alone is never accepted as a successful reset.
        Thread.sleep(10_000)
        fail("Android did not terminate the reset process")
    }

    @Test fun freshProcessHasNoSeededDataOrKeys() {
        requireEmulatorPhase("verify")
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("cmux_native_v1"))
        assertNull(NativeCredentialStore(context).load())
        assertFalse(NativeNotificationService.isEnabled(context))
        assertFalse(File(context.filesDir, "reset-acceptance-seed").exists())
        assertFalse(File(context.cacheDir, "reset-acceptance-seed").exists())
        assertFalse(File(requireNotNull(context.getExternalFilesDir(null)), "reset-acceptance-seed").exists())
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }
}
