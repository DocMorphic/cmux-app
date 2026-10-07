package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Existing notification-specific fixtures explicitly opt into the legacy destination. */
internal class LegacyNotificationsTestPreference : ExternalResource() {
    private var entered = false
    override fun before() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        preferences().edit().putBoolean(NativeDisplayPreferences.feedReplacesNotificationsKey, false).commit()
        entered = true
    }
    override fun after() {
        if (entered) preferences().edit().remove(NativeDisplayPreferences.feedReplacesNotificationsKey).commit()
    }
    private fun preferences() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("cmux-display", Context.MODE_PRIVATE)
}
