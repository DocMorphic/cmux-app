package io.github.docmorphic.cmuxapp

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.CoreMatchers.`is`
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class NativeViewHapticsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun detachedViewsReleaseSubscriptionAndReattachUsingCurrentPreference() {
        val name = "view-haptics-${UUID.randomUUID()}"
        val preferences = compose.activity.getSharedPreferences(name, Context.MODE_PRIVATE)
        lateinit var parent: LinearLayout
        lateinit var view: View
        lateinit var intrinsicallySilent: View
        compose.setContent { AndroidView(factory = { context ->
            LinearLayout(context).also { root ->
                parent = root
                view = View(context).also { NativeViewHaptics(it, preferences); root.addView(it) }
                intrinsicallySilent = View(context).also {
                    it.isHapticFeedbackEnabled = false; NativeViewHaptics(it, preferences); root.addView(it)
                }
            }
        }) }
        try {
            compose.runOnIdle {
                assertTrue(view.isAttachedToWindow); assertTrue(view.isHapticFeedbackEnabled)
                assertFalse(intrinsicallySilent.isHapticFeedbackEnabled)
                preferences.edit().putBoolean(NativeDisplayPreferences.hapticsKey, false).commit()
                assertFalse(view.isHapticFeedbackEnabled)
                assertFalse(view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS))
                parent.removeView(view)
                preferences.edit().putBoolean(NativeDisplayPreferences.hapticsKey, true).commit()
                assertFalse("Detached views must not be retained by preference listeners", view.isHapticFeedbackEnabled)
                parent.addView(view)
                assertTrue(view.isHapticFeedbackEnabled)
                preferences.edit().putString(NativeDisplayPreferences.hapticsKey, "invalid").commit()
                assertTrue(view.isHapticFeedbackEnabled)
                preferences.edit().clear().commit()
                assertTrue(view.isHapticFeedbackEnabled); assertFalse(intrinsicallySilent.isHapticFeedbackEnabled)
            }
        } finally { compose.activity.deleteSharedPreferences(name) }
    }

    @Test fun productionTextAndWebViewsFollowTheLiveSwitchAndSelectionStillCopies() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val context = compose.activity
        val preferences = context.getSharedPreferences("cmux-display", Context.MODE_PRIVATE)
        val key = NativeDisplayPreferences.hapticsKey
        val previous = preferences.all[key]
        var sheet by mutableStateOf(false)
        val views = mutableListOf<View>()
        lateinit var browser: LocalBrowserWebHost
        lateinit var markdown: MarkdownWebController
        var closeBrowser: LocalBrowserWebHost? = null
        var closeMarkdown: MarkdownWebController? = null
        try {
            compose.runOnUiThread { preferences.edit().putBoolean(key, false).commit() }
            compose.setContent { CmuxTheme { Surface {
                AndroidView(factory = { viewContext -> LinearLayout(viewContext).apply {
                    orientation = LinearLayout.VERTICAL
                    fun add(view: View) { views += view; addView(view, LinearLayout.LayoutParams(-1, 150)) }
                    add(TerminalKeyboardView(viewContext))
                    add(ArtifactNumberedTextView(viewContext).apply { text = "Native artifact text" })
                    browser = LocalBrowserWebHost(viewContext, LocalBrowserSurface("haptic-fixture"), { _, _, _ -> false }, {})
                    closeBrowser = browser
                    browser.applyPendingWork(); add(browser)
                    views += browser.getChildAt(0) as WebView
                    markdown = MarkdownWebController(viewContext, "<html><body>Native Markdown</body></html>", "", 1f, {}, {})
                    closeMarkdown = markdown
                    add(markdown.create())
                } })
                if (sheet) TerminalTextSheet(TerminalTextSnapshot("Selection remains usable with haptics off", false, 10)) { sheet = false }
            } } }
            compose.runOnIdle {
                val targets = views.filterNot { it === browser }
                assertEquals(4, targets.size)
                assertTrue(targets.all { it.isAttachedToWindow && !it.isHapticFeedbackEnabled })
                assertTrue(targets.none { it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) })
                preferences.edit().putBoolean(key, true).commit()
                assertTrue(targets.all { it.isHapticFeedbackEnabled })
                preferences.edit().putBoolean(key, false).commit()
                assertTrue(targets.none { it.isHapticFeedbackEnabled })
                sheet = true
            }
            val snapshot = onView(withTagValue(`is`("terminal-text-snapshot" as Any)))
            snapshot.check { view, failure ->
                if (failure != null) throw failure
                assertFalse(view.isHapticFeedbackEnabled)
            }.perform(longClick()).check { view, failure ->
                if (failure != null) throw failure
                val text = view as TextView
                assertTrue(text.hasSelection())
                val selected = text.text.substring(text.selectionStart, text.selectionEnd)
                assertTrue(text.onTextContextMenuItem(android.R.id.copy))
                assertEquals(selected, context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
            }
            compose.runOnIdle { preferences.edit().putBoolean(key, true).commit() }
            snapshot.check { view, failure -> if (failure != null) throw failure; assertTrue(view.isHapticFeedbackEnabled) }
            compose.runOnIdle { preferences.edit().putBoolean(key, false).commit() }
            snapshot.check { view, failure -> if (failure != null) throw failure; assertFalse(view.isHapticFeedbackEnabled) }
            compose.onNodeWithText("Copy All").performClick()
            compose.onNodeWithText("Copied").assertExists()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "native-view-haptics").apply { mkdirs() }
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(folder, "selection-copy.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            compose.onNodeWithText("Done").performClick()
        } finally {
            compose.runOnUiThread {
                closeBrowser?.release()
                closeMarkdown?.close()
                preferences.edit().apply { when (previous) {
                    is Boolean -> putBoolean(key, previous)
                    is String -> putString(key, previous)
                    is Int -> putInt(key, previous)
                    is Long -> putLong(key, previous)
                    is Float -> putFloat(key, previous)
                    is Set<*> -> putStringSet(key, previous.filterIsInstance<String>().toSet())
                    else -> remove(key)
                } }.commit()
            }
        }
    }
}
