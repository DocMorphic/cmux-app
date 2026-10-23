package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Production page allocation, including content that fits only after visual contraction. */
class CloudOnboardingFlexibleLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() { check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) }
    @After fun finish() { compose.activity.finish() }

    private fun scroll(page: Int) = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
        hasAnyAncestor(hasTestTag("cloud.introduction.page.$page")), useUnmergedTree = true)

    private fun capture(name: String) {
        val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val folder = File(compose.activity.getExternalFilesDir(null), "cloud-onboarding-flexible").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }

    @Test fun visualContractsBeforeScrollingAndExpandsAgainAfterReflow() {
        var height by mutableStateOf(760.dp)
        compose.setContent { CmuxTheme(darkTheme = true) { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding(), Alignment.Center) {
                NativeCloudIntroduction(false, null, {}, {}, Modifier.width(402.dp).height(height))
            }
        } } }
        val density = compose.activity.resources.displayMetrics.density
        val records = JSONArray(); var roomyGap = 0f
        for ((name, h) in listOf("roomy" to 760, "contracted" to 600, "overflow" to 480, "expanded" to 760)) {
            compose.runOnIdle { height = h.dp }; compose.waitForIdle()
            val pager = compose.onNodeWithTag("cloud.introduction.pager").fetchSemanticsNode().boundsInRoot
            val copy = compose.onNodeWithTag("cloud.introduction.copy.0").fetchSemanticsNode().boundsInRoot
            val graphicNode = compose.onNodeWithTag("cloud.introduction.graphic.0")
            val graphic = graphicNode.fetchSemanticsNode().boundsInRoot
            val range = scroll(0).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            val maximum = compose.runOnIdle { range.maxValue() }
            val minimum = copy.height + (18 + 152 + 20) * density
            val fullVisual = copy.height + (18 + 280 + 20) * density
            val gap = (graphic.top - copy.bottom) / density
            when (name) {
                "roomy", "expanded" -> {
                    assertTrue(pager.height >= fullVisual); assertEquals(0f, maximum, 0f)
                    if (name == "roomy") roomyGap = gap else assertEquals(roomyGap, gap, 1f / density)
                }
                "contracted" -> {
                    assertTrue("Fixture must fit a complete card but need contraction", pager.height >= minimum && pager.height < fullVisual)
                    assertEquals("Avoidable scrolling when copy and full card fit", 0f, maximum, 0f)
                    assertTrue(gap >= 18f - 1f / density && gap < roomyGap)
                }
                else -> {
                    assertTrue(pager.height < minimum); assertTrue(maximum > 0)
                    val footer = compose.onNodeWithTag("cloud.introduction.next").fetchSemanticsNode().boundsInRoot
                    graphicNode.performScrollTo()
                    assertEquals(footer, compose.onNodeWithTag("cloud.introduction.next").fetchSemanticsNode().boundsInRoot)
                }
            }
            val visible = graphicNode.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(visible.top >= pager.top - 1 && visible.bottom <= pager.bottom + 1)
            records.put(JSONObject().put("case", name).put("rootHeightDp", h).put("pagerPx", pager.height)
                .put("copyPx", copy.height).put("scrollMaximumPx", maximum).put("gapDpBeforeScroll", gap))
            capture(name)
        }
        File(compose.activity.getExternalFilesDir(null), "cloud-onboarding-flexible/profiles.json").writeText(records.toString(2))
    }

    @Test fun tallAndroidPermissionContentStaysReachableWithoutMovingFooter() {
        var actions = 0
        compose.setContent { CmuxTheme(darkTheme = true) { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding(), Alignment.Center) {
                NativeCloudIntroduction(false, null, {}, {}, Modifier.width(402.dp).height(600.dp), vpnControl = {
                    Column(Modifier.fillMaxWidth().height(320.dp).testTag("FixtureTallVpn")) {
                        Text("Controlled Android permission and cleanup content")
                        Spacer(Modifier.weight(1f))
                        Button({ actions++ }, Modifier.testTag("FixtureVpnAction")) { Text("Controlled action") }
                    }
                })
            }
        } } }
        compose.onNodeWithTag("cloud.introduction.next").performClick()
        val footer = compose.onNodeWithTag("cloud.introduction.next").fetchSemanticsNode().boundsInRoot
        val maximum = scroll(1).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.runOnIdle { assertTrue(maximum.maxValue() > 0) }
        compose.onNodeWithTag("FixtureVpnAction").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, actions) }
        assertEquals(footer, compose.onNodeWithTag("cloud.introduction.next").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        capture("tall-android-control")
    }
}
