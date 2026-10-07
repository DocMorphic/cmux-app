package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AccountPlanSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val painted = java.util.concurrent.CountDownLatch(1)
        compose.runOnUiThread {
            val window = android.view.inspector.WindowInspector.getGlobalWindowViews().first { it.hasWindowFocus() }
            window.postOnAnimation { window.postOnAnimation { painted.countDown() } }
        }
        assertTrue("Plan window did not draw", painted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        i.waitForIdleSync()
        val folder = java.io.File(i.targetContext.getExternalFilesDir(null), "account-plan").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun webPlanControlsOpenFixedDestinationsAndBrowserReturnRefreshesStatus() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var plan = AccountPlan("pro", "stripe", "stripe", true)
        var reads = 0
        val links = mutableListOf<String>()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        AccountPlanController(scope, { reads++; plan }, { true }).use { controller ->
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.setContent { CmuxTheme { Surface {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) { AccountPlanSettings(controller, links::add) }
                }
            } } }
            compose.onNodeWithTag("settings.plan").performClick()
            compose.onNodeWithTag("plan.current").assertTextEquals("Pro")
            compose.onNodeWithTag("plan.manage").performClick()
            compose.runOnIdle { assertEquals(listOf("https://cmux.com/dashboard/billing"), links) }
            val before = reads
            compose.runOnUiThread {
                owner.registry.currentState = Lifecycle.State.CREATED
                plan = AccountPlan("max", "stripe", "stripe", true)
                owner.registry.currentState = Lifecycle.State.RESUMED
            }
            compose.onNodeWithTag("plan.current").assertTextEquals("Max")
            compose.runOnIdle { assertTrue(reads > before) }
            compose.onNodeWithTag("plan.offers").performClick()
            compose.runOnIdle { assertEquals(AccountPlan.PRICING, links.last()) }
            capture("web-plan")
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithTag("settings.plan").assertTextContains("Max")
        }
        scope.cancel()
    }

    @Test fun failureRetriesAndAppleManagementDoesNotOfferWebCheckout() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var failing = true
        val links = mutableListOf<String>()
        AccountPlanController(scope, {
            if (failing) throw java.io.IOException("fixture")
            AccountPlan("go", "apple", "external", true)
        }, { true }).use { controller ->
            compose.setContent { CmuxTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                AccountPlanSettings(controller, links::add)
            } } } }
            compose.onNodeWithTag("settings.plan").performClick()
            compose.onNodeWithText("Couldn’t load your plan. Check your connection and try again.").assertIsDisplayed()
            compose.runOnIdle { failing = false }
            compose.onNodeWithText("Try Again").performClick()
            compose.onNodeWithTag("plan.current").assertTextEquals("Go")
            compose.onNodeWithTag("plan.offers").assertDoesNotExist()
            compose.onNodeWithText("Billed through the App Store.").assertIsDisplayed()
            compose.onNodeWithTag("plan.manage").performClick()
            compose.runOnIdle { assertEquals(listOf("https://apps.apple.com/account/subscriptions"), links) }
            capture("apple-plan")
        }
        scope.cancel()
    }

    @Test fun teamPlanExplainsAdminManagementWithoutPersonalPurchaseControls() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        AccountPlanController(scope, { AccountPlan("free", "none", "none", true, teamBilled = true) }, { true }).use { controller ->
            compose.setContent { CmuxTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                AccountPlanSettings(controller) { error("No billing link should open automatically") }
            } } } }
            compose.onNodeWithTag("settings.plan").performClick()
            compose.onNodeWithText("Your team admin manages billing for this account.").assertIsDisplayed()
            compose.onNodeWithTag("plan.offers").assertDoesNotExist()
            compose.onNodeWithTag("plan.manage").assertDoesNotExist()
            capture("team-plan")
        }
        scope.cancel()
    }
}
