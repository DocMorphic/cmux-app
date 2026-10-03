package io.github.docmorphic.cmuxapp

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in API experiment. Synthetic cookies only; never opens a URL or changes default-profile state. */
class NativeNoticeProfileProbe {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val reportFile get() = File(instrumentation.targetContext.noBackupFilesDir, "notice-profile-probe.json")
    private val origin = "https://cmux-notice-probe.invalid/"
    private fun <T> main(action: () -> T): T {
        var value: T? = null; var failure: Throwable? = null
        instrumentation.runOnMainSync { try { value = action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun enabled() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cmux_notice_profile_probe") == "true")
        assertTrue(main { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) })
    }
    private fun view(name: String) = main { WebView(compose.activity).apply { WebViewCompat.setProfile(this, name) } }
    private fun cookies(view: WebView) = main { WebViewCompat.getProfile(view).cookieManager }
    private fun set(manager: CookieManager, value: String) {
        val done = CountDownLatch(1); var accepted = false
        main { manager.setCookie(origin, "notice_probe=$value; Max-Age=3600; Secure; HttpOnly; Path=/") { accepted = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertTrue(accepted)
        main { manager.flush() }
    }
    private fun cookie(manager: CookieManager) = main { manager.getCookie(origin) }.orEmpty()
    private fun delete(name: String): JSONObject = main {
        try { JSONObject().put("deleted", ProfileStore.getInstance().deleteProfile(name)) }
        catch (failure: IllegalStateException) { JSONObject().put("deleted", false).put("exception", failure.javaClass.simpleName) }
    }
    private fun read() = JSONObject(reportFile.readText())
    private fun write(report: JSONObject) { reportFile.writeText(report.toString(2)) }

    @Test fun inspectIsolationAndDestruction() {
        enabled()
        check(!reportFile.exists()) { "Finish the previous owned-profile cleanup before probing again" }
        val prefix = "cmux-notice-probe-${UUID.randomUUID()}-"
        val a = prefix + "a"; val b = prefix + "b"; val c = prefix + "crash"
        val report = JSONObject().put("profiles", JSONArray(listOf(a, b, c)))
        write(report) // Record ownership before creating anything so interrupted probes remain recoverable.
        val defaultBefore = cookie(main { CookieManager.getInstance() })
        val first = view(a); val second = view(b)
        set(cookies(first), "a"); set(cookies(second), "b")
        assertEquals("notice_probe=a", cookie(cookies(first)))
        assertEquals("notice_probe=b", cookie(cookies(second)))
        assertEquals(defaultBefore, cookie(main { CookieManager.getInstance() }))
        main { first.destroy() }
        report.put("deleteAfterDestroy", delete(a))
        assertEquals("notice_probe=b", cookie(cookies(second)))
        main { second.destroy() }
        // Leave only an owned synthetic profile to observe process-death persistence next invocation.
        val crash = view(c); set(cookies(crash), "crash")
        retained = crash
        report.put("isolation", true).put("defaultUnchanged", true)
            .put("provider", main { WebViewCompat.getCurrentWebViewPackage(compose.activity)?.versionName })
        write(report)
    }
    @Test fun inspectAfterProcessRestartAndTargetedClear() {
        enabled()
        val report = read(); val names = report.getJSONArray("profiles"); val b = names.getString(1); val c = names.getString(2)
        val current = main { ProfileStore.getInstance().allProfileNames }
        // Registration persistence is an observation, not a prerequisite. A provider can
        // leave profile directories on disk without retaining its name registry.
        report.put("registeredAfterRestart", JSONObject().put("b", b in current).put("crash", c in current))
        write(report)
        val defaultBefore = cookie(main { CookieManager.getInstance() })
        val untouched = view(b); val otherManager = cookies(untouched)
        report.put("otherCookieSurvivedProcessRestart", cookie(otherManager) == "notice_probe=b")
        set(otherManager, "b") // Establish the control even if the registry was not persisted.
        val crash = view(c); val manager = cookies(crash)
        val persisted = cookie(manager) == "notice_probe=crash"
        report.put("cookieSurvivedProcessRestart", persisted)
        write(report)
        // Observe rather than require persistence: this probe determines the platform behavior.
        set(manager, "crash") // Ensure the targeted clear is never a vacuous empty-store test.
        val done = CountDownLatch(1)
        main { manager.removeAllCookies { done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        main { WebViewCompat.getProfile(crash).webStorage.deleteAllData(); manager.flush() }
        assertEquals("", cookie(manager))
        main { crash.destroy() }
        report.put("deleteAfterCookieClearAndDestroy", delete(c))
        assertEquals("notice_probe=b", cookie(otherManager))
        main { untouched.destroy() }
        assertEquals(defaultBefore, cookie(main { CookieManager.getInstance() }))
        report.put("targetedClear", true).put("otherProfilePreserved", true)
        write(report)
    }
    @Test fun cleanupOwnedProfilesBeforeTheyAreLoaded() {
        enabled()
        val report = read(); val names = report.getJSONArray("profiles")
        for (i in 0 until names.length()) {
            val name = names.getString(i); check(name.startsWith("cmux-notice-probe-"))
            val result = delete(name)
            assertFalse(result.has("exception"))
        }
        val remaining = main { ProfileStore.getInstance().allProfileNames }
        for (i in 0 until names.length()) assertFalse(names.getString(i) in remaining)
        // The public API only proves registry removal, not synchronous disk erasure.
        report.put("cleanupOnFreshProcess", true).put("diskErasureVerified", false)
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "notice-profile-probe.json")
        output.writeText(report.toString(2))
        assertTrue(reportFile.delete())
    }
    companion object { private var retained: WebView? = null }
}
