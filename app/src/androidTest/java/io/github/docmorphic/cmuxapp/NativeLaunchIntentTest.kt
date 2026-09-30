package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*

/** Package-manager checks use the installed manifest, without launching or touching account state. */
class NativeLaunchIntentTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun resolve(uri: String): String? = context.packageManager.resolveActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName),
        PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.name
    @Test fun stablePairingSchemesResolveToTheActualEntryActivity() {
        val schemes = listOf("cmux-android", "cmux-ios", "cmux-ios-dev", "cmux-ios-com.cmux.app",
            "cmux-ios-dev.cmux.app.beta", "cmux-ios-dev.cmux.app.internal", "cmux-ios-dev.cmux.app.demo", "cmux-ios-dev.cmux.ios")
        for (scheme in schemes) {
            val uri = "$scheme://attach?v=3&i=endpoint&d=device&ub=user&t=team&b=stable"
            assertTrue(PairingCodeParser.parse(uri).isSuccess)
            assertEquals(scheme, MainActivity::class.java.name, resolve(uri))
        }
    }
    @Test fun unrelatedSchemesAndHostsDoNotResolveToThePairingEntry() {
        for (uri in listOf("cmux-android://wrong-host?v=3", "cmux-ios://wrong-host?v=2", "unknown-cmux://attach?v=2"))
            assertNull(uri, resolve(uri))
    }
    @Test fun notificationRouteUsesItsExplicitActivityWithoutRegisteringAPublicScheme() {
        val route = "11111111-1111-4111-8111-111111111111"
        val intent = NativeNotificationDelivery.launchIntent(context, route)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(route, NativeNotificationDelivery.routeFromIntent(context, intent))
        assertNull(resolve(intent.dataString!!))
    }
}
