package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.abs
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Real focused windows and compositor pixels; no account, network or physical-device operation. */
@OptIn(ExperimentalTestApi::class)
class NativeDialogAppearanceRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private var dark by mutableStateOf(false)
    private var presentation by mutableStateOf("")
    private var child by mutableStateOf(false)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val records = JSONArray()
    @Before fun prepare() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
    }
    @After fun cleanup() { compose.activity.finish() }

    private fun show() = compose.setContent { CmuxTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().padding(20.dp)) {
            Text("Dialog appearance host")
            Text("The parent window follows the selected appearance.")
        } }
        when (presentation) {
            "terminal" -> TerminalTextSheet(TerminalTextSnapshot("Readable terminal document\n".repeat(25), false, 25)) { presentation = "" }
            "pairing" -> NativePairingHelp(NativeMacCompatibilityPolicy(emptyList()), true, { presentation = "" }, {}, {})
            "shortcuts" -> TerminalToolbarSettings(remember { TerminalToolbarStore(compose.activity.getSharedPreferences("dialog-fixture-shortcuts", android.content.Context.MODE_PRIVATE)) }) { presentation = "" }
            "drafts" -> TaskDraftsSheet(emptyList(), false, null, { presentation = "" }, {}, {}, {})
            "alert" -> WorkspaceCloseDialog(WorkspaceCloseConfirmation.mac, onDismiss = { presentation = "" }, onConfirm = { error("No destructive action") })
            "media" -> NativeDialog({ presentation = "" }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false), darkSystemBars = true) {
                Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                    Text("Independent black media surface", color = Color.White)
                    if (child) WorkspaceCloseDialog(WorkspaceCloseConfirmation.mac, onDismiss = { child = false }, onConfirm = { error("No destructive action") })
                }
            }
        }
    } }

    private fun visible(text: String) {
        try {
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithText(text).assertIsDisplayed(); true }.getOrDefault(false)
            }
        } catch (error: Throwable) {
            val dir = File(compose.activity.getExternalFilesDir(null),"screenshots").apply { mkdirs() }
            val frame = instrumentation.uiAutomation.takeScreenshot()
            File(dir,"dialog-not-visible-${text.replace(' ','-')}.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG,100,it) }
            frame.recycle(); throw error
        }
    }

    private fun window(view: View): Window? {
        if (view is DialogWindowProvider) return view.window
        if (view === compose.activity.window.decorView) return compose.activity.window
        if (view is ViewGroup) for (index in 0 until view.childCount) window(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun focused(): Window? {
        var found: Window? = null
        compose.runOnUiThread { found = WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() }?.let(::window) }
        return found
    }
    private fun bars(name: String, statusDark: Boolean, navigationDark: Boolean = statusDark, parent: Boolean = false, excluding: Window? = null, requiredWindow: Window? = null) {
        compose.waitUntil(10_000) {
            val current = focused() ?: return@waitUntil false
            var matches = false
            compose.runOnUiThread {
                val controller = WindowCompat.getInsetsController(current, current.decorView)
                matches = current !== excluding && (requiredWindow == null || current === requiredWindow) && (current === compose.activity.window) == parent &&
                    controller.isAppearanceLightStatusBars == !statusDark && controller.isAppearanceLightNavigationBars == !navigationDark
            }
            matches
        }
        val current = checkNotNull(focused())
        var statusHeight = 0; var navigationHeight = 0; var dim = 0f
        val origin = IntArray(2)
        compose.runOnUiThread {
            current.decorView.getLocationOnScreen(origin); dim = current.attributes.dimAmount
            val insets = WindowCompat.getInsetsController(current, current.decorView)
            insets.hide(WindowInsetsCompat.Type.ime())
            val rootInsets = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
            statusHeight = rootInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top
            navigationHeight = rootInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom
        }
        val base = if (dark) 0xFF0B0C0E.toInt() else 0xFFF2F2F7.toInt()
        fun dimmed(alpha: Float) = ColorUtils.compositeColors(android.graphics.Color.argb((alpha*255).roundToInt(),0,0,0),base)
        val media = name.startsWith("dialog-media")
        val floating = name.startsWith("dialog-alert") && !parent
        val sheet = name.startsWith("dialog-drafts") && !parent
        val expectedStatus = when { media -> android.graphics.Color.BLACK; floating -> dimmed(dim); sheet -> dimmed(.32f); else -> base }
        val expectedNavigation = if (sheet) { if (dark) 0xFF191B1F.toInt() else android.graphics.Color.WHITE } else expectedStatus
        val label = when {
            parent -> "Dialog appearance host"
            name == "dialog-media-alert" || floating -> "Delete Workspace?"
            media -> "Independent black media surface"
            name.startsWith("dialog-terminal") -> "Terminal Text"
            name.startsWith("dialog-shortcuts") -> "Terminal Shortcuts"
            name.startsWith("dialog-pairing") -> "Connect your Mac"
            else -> "Drafts"
        }
        val textColor = if (media && name != "dialog-media-alert") android.graphics.Color.WHITE
            else if (dark) 0xFFF4F5F7.toInt() else 0xFF1C1C1E.toInt()
        val textBounds = compose.onNodeWithText(label).fetchSemanticsNode().boundsInWindow
        var last: Bitmap? = null; var status = JSONObject(); var navigation = JSONObject(); var textInk = 0
        fun evidence(image: Bitmap, top: Int, bottom: Int, white: Boolean, expectedBackground: Int): JSONObject {
            check(bottom > top)
            val colors = (image.width * 45 / 100 until image.width * 55 / 100).flatMap { x ->
                (top until bottom).map { y -> image.getPixel(x,y) }
            }
            fun median(channel: (Int) -> Int) = colors.map(channel).sorted()[colors.size / 2]
            val bg = android.graphics.Color.rgb(median(android.graphics.Color::red),median(android.graphics.Color::green),median(android.graphics.Color::blue))
            // Floating alerts can retain OS-selected light icons over a dimmed parent.
            // Judge the actual painted background, independently of requested window flags.
            val contrastWhite = ColorUtils.calculateContrast(android.graphics.Color.WHITE,bg)
            val contrastBlack = ColorUtils.calculateContrast(android.graphics.Color.BLACK,bg)
            val readableWhite = contrastWhite > contrastBlack
            var count = 0
            for (y in top until bottom) for (x in 0 until image.width) {
                val pixel = image.getPixel(x,y)
                val luma = ColorUtils.calculateLuminance(pixel)
                if ((if (readableWhite) luma > .5 else luma < .2) && ColorUtils.calculateContrast(pixel,bg) >= 3) count++
            }
            val backgroundMatches = listOf(android.graphics.Color::red,android.graphics.Color::green,android.graphics.Color::blue)
                .all { channel -> abs(channel(bg)-channel(expectedBackground)) <= 3 }
            return JSONObject().put("backgroundArgb",bg).put("expectedBackgroundArgb",expectedBackground).put("backgroundMatches",backgroundMatches)
                .put("inkPixels",count).put("requestedWhiteIcons",white).put("contrastDirectionWhite",readableWhite)
        }
        try {
            compose.waitUntil(10_000) {
                last?.recycle(); val image = instrumentation.uiAutomation.takeScreenshot(); last = image
                status = evidence(image,0,statusHeight,statusDark,expectedStatus)
                navigation = evidence(image,image.height-navigationHeight,image.height,navigationDark,expectedNavigation)
                textInk = 0
                for (y in (origin[1]+textBounds.top).roundToInt().coerceAtLeast(0) until (origin[1]+textBounds.bottom).roundToInt().coerceAtMost(image.height))
                    for (x in (origin[0]+textBounds.left).roundToInt().coerceAtLeast(0) until (origin[0]+textBounds.right).roundToInt().coerceAtMost(image.width))
                        if (image.getPixel(x,y) == textColor) textInk++
                status.getBoolean("backgroundMatches") && navigation.getBoolean("backgroundMatches") &&
                    status.getInt("inkPixels") > 50 && navigation.getInt("inkPixels") > 8 && textInk >= 20
            }
        } finally {
            val dir = File(compose.activity.getExternalFilesDir(null),"screenshots").apply { mkdirs() }
            last?.let { image -> File(dir,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle() }
            records.put(JSONObject().put("name",name).put("status",status).put("navigation",navigation).put("parentFocused",parent).put("label",label).put("textArgb",textColor).put("textInkPixels",textInk))
            File(dir,"dialog-bars-${if (name.startsWith("dialog-media") || name.startsWith("dialog-root")) "media" else "production"}.json").writeText(records.toString(2))
        }
    }

    @Test fun productionDialogsSheetsAndAlertsFollowAppearanceAndReturnFocus() {
        show()
        for (kind in listOf("terminal","shortcuts","pairing","drafts","alert")) {
            compose.runOnIdle { dark = false; presentation = kind }
            visible(when(kind) {
                "terminal" -> "Terminal Text"; "shortcuts" -> "Terminal Shortcuts"; "pairing" -> "Connect your Mac";
                "drafts" -> "Drafts"; else -> "Delete Workspace?"
            })
            var shown: Window? = null
            for ((index,value) in listOf(false,true,false).withIndex()) {
                compose.runOnIdle { dark = value }
                bars("dialog-$kind-$index",value,requiredWindow=shown)
                if (shown == null) shown = focused()
            }
            device.pressBack()
            compose.waitUntil(10_000) { presentation.isEmpty() }
            bars("dialog-$kind-dismissed",false,parent=true)
        }
    }

    @Test fun oppositeMediaWindowAndNestedAlertRestoreTheirActualParentBars() {
        show(); bars("dialog-root-light",false,parent=true)
        compose.runOnIdle { presentation = "media" }; bars("dialog-media-dark",true)
        val media = checkNotNull(focused())
        compose.runOnIdle { child = true }
        visible("Delete Workspace?")
        bars("dialog-media-alert",true,excluding=media)
        device.pressBack(); compose.waitUntil(5_000) { !child }; bars("dialog-media-returned",true,requiredWindow=media)
        // The parent can change appearance while another window owns focus.
        compose.runOnIdle { dark = true }; bars("dialog-media-parent-changed",true,requiredWindow=media)
        device.pressBack(); compose.waitUntil(5_000) { presentation.isEmpty() }; bars("dialog-root-dark-returned",true,parent=true)
        compose.runOnIdle { dark = false }; bars("dialog-root-light-returned",false,parent=true)
    }
}
