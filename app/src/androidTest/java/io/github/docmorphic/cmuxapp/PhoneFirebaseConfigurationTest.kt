package io.github.docmorphic.cmuxapp

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit emulator-only fixture; never requests a provider token or changes account consent. */
class PhoneFirebaseConfigurationTest {
    @Test fun sdkInitializationMatchesExplicitBuildConfigurationWithoutEnablingPush() {
        val expected = InstrumentationRegistry.getArguments().getString("firebase_configuration_fixture")
        assumeTrue(expected in setOf("configured", "unconfigured"))
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic Firebase configuration is emulator-only" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val metadata = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        for (key in listOf("firebase_data_collection_default_enabled", "firebase_messaging_auto_init_enabled",
            "firebase_analytics_collection_enabled", "firebase_messaging_notification_delegation_enabled")) {
            assertTrue("Missing opt-out metadata: $key", metadata.containsKey(key))
            assertFalse(metadata.getBoolean(key))
        }
        val app = FirebaseApp.getApps(context).singleOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        val setup = PhoneFcmTokens.setup(context)
        if (expected == "configured") {
            assertNotNull(app)
            assertEquals("cmux-app-sdk-fixture", app!!.options.projectId)
            assertEquals("1:000000000000:android:0011223344556677", app.options.applicationId)
            assertEquals("000000000000", app.options.gcmSenderId)
            assertFalse(app.isDataCollectionDefaultEnabled)
            assertFalse(FirebaseMessaging.getInstance().isAutoInitEnabled)
            assertTrue(setup.configured)
        } else {
            assertNull(app)
            assertFalse(setup.configured)
            assertEquals(0, context.resources.getIdentifier("google_app_id", "string", context.packageName))
        }
        // A configured SDK is not an enrolled phone; no helper receipt/grant is created.
        assertNull(setup.grant)
        assertNull(setup.token)
    }
}
