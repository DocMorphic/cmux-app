package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalTestApi::class)
class NativeBrowserLensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val sent = CopyOnWriteArrayList<BrowserInput>()
    private fun show() {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 200) bitmap.setPixel(x, y, when {
            y < 50 && x < 100 -> android.graphics.Color.RED
            y < 50 -> android.graphics.Color.BLUE
            x < 100 -> android.graphics.Color.GREEN
            else -> android.graphics.Color.YELLOW
        })
        val frame = BrowserFrame(1, bitmap.asImageBitmap(), 400.0, 200.0)
        compose.setContent { CmuxTheme {
            val scope = rememberCoroutineScope()
            val queue = remember { BrowserInputQueue(scope) { sent += it } }
            DisposableEffect(queue) { onDispose { queue.close() } }
            val motion = rememberBrowserScrollMotion(queue)
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                BrowserPageSurface(frame, queue, motion, 1, true, {}, Modifier.size(300.dp, 500.dp))
            }
        } }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(64); compose.waitForIdle()
    }
    private fun page() = compose.onNodeWithContentDescription("Mac browser page")
    private fun clicks() = sent.filterIsInstance<BrowserInput.Click>()
    private fun pinch(from: Float, to: Float) {
        page().performTouchInput {
            pinch(start0 = Offset(centerX - width * from, centerY), end0 = Offset(centerX - width * to, centerY),
                start1 = Offset(centerX + width * from, centerY), end1 = Offset(centerX + width * to, centerY), durationMillis = 240)
        }
        compose.waitForIdle()
    }
    private fun clickAtQuarter() {
        page().performTouchInput { click(Offset(width * .25f, centerY)) }
        compose.waitForIdle()
    }

    @Test fun widthFitRendersWithoutDistortionAndLetterboxDoesNotReceiveClicks() {
        show()
        val image = page().captureToImage(); val pixels = image.toPixelMap()
        val x = image.width / 4; val y = image.height / 2 - image.width / 8
        assertEquals(Color.Black, pixels[x, 5])
        assertEquals(Color.Red, pixels[x, y]); assertEquals(Color.Blue, pixels[x * 3, y])
        assertEquals(Color.Green, pixels[x, image.height / 2 + image.width / 8])
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots/browser-width-fit.png")
        file.parentFile!!.mkdirs(); file.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        page().performTouchInput { click(Offset(centerX, 10f)) }
        compose.waitForIdle(); assertTrue(sent.isEmpty())
        page().performTouchInput {
            repeat(3) { click(Offset(width * .25f, centerY - width * .125f)); advanceEventTime(80) }
        }
        compose.waitUntil(5_000) { clicks().size == 3 }
        assertEquals(listOf(1, 2, 3), clicks().map { it.count })
        clicks().forEach { assertEquals(100.0, it.x, 1.0); assertEquals(50.0, it.y, 1.0) }
        assertTrue(sent.all { it is BrowserInput.Click })
    }

    @Test fun pinchAndLocalPanKeepClicksAlignedWithoutSendingMacScroll() {
        show(); pinch(.1f, .2f)
        assertTrue("Pinching must not click or scroll the Mac", sent.isEmpty())
        clickAtQuarter()
        compose.waitUntil(5_000) { clicks().size == 1 }
        assertEquals(150.0, clicks().single().x, 1.0)
        assertEquals(100.0, clicks().single().y, 1.0)
        page().performTouchInput { swipe(center, Offset(centerX + width * .2f, centerY), durationMillis = 240) }
        compose.waitForIdle()
        assertEquals(1, sent.size)
        val image = page().captureToImage(); val pixels = image.toPixelMap()
        val row = image.height / 2 - 20
        val boundary = (0 until image.width).first { pixels[it, row] == Color.Blue }
        assertTrue("Panning moves the rendered red/blue boundary", boundary > image.width / 2)
        page().performTouchInput { click(center) }
        compose.waitUntil(5_000) { clicks().size == 2 }
        val expected = 200.0 + (image.width / 2.0 - boundary) * 200.0 / image.width
        assertEquals("Tap coordinates follow the visible image", expected, clicks().last().x, 1.5)
        assertEquals(100.0, clicks().last().y, 1.0)
        assertTrue(sent.all { it is BrowserInput.Click })
    }

    @Test fun zoomLimitsAndPinchResetRestoreRemoteScrolling() {
        show(); pinch(.04f, .4f); clickAtQuarter()
        compose.waitUntil(5_000) { clicks().size == 1 }
        assertEquals("Zoom is capped at four", 175.0, clicks().last().x, 1.0)
        pinch(.4f, .04f); clickAtQuarter()
        compose.waitUntil(5_000) { clicks().size == 2 }
        assertEquals("Zooming back to one resets the local lens", 100.0, clicks().last().x, 1.0)
        assertEquals(2, sent.size)
        page().performTouchInput { swipe(Offset(centerX, centerY - width * .15f), Offset(centerX, centerY + width * .15f), durationMillis = 100) }
        compose.waitUntil(5_000) { sent.filterIsInstance<BrowserInput.Scroll>().any { it.phase == "momentum_ended" } }
        val scrolls = sent.filterIsInstance<BrowserInput.Scroll>()
        assertTrue(scrolls.any { it.dy > 0 }); assertTrue(scrolls.all { it.dy >= 0 })
        assertEquals(2, clicks().size)
    }
}
