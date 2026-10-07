package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AgentFeedInlinePreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun textNode() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
    private fun layout(): TextLayoutResult {
        val values = mutableListOf<TextLayoutResult>()
        textNode().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(values) }
        return values.single()
    }
    private fun capture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "agent-feed-inline").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder, "inline-preview.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun expansionOccupiesLastVisibleLinePreservesLinksAndDoesNotOpenRow() {
        var expanded = 0; var opened = 0
        compose.setContent { CmuxTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
            Box(Modifier.width(280.dp).clickable { opened++ }) {
                AgentFeedInlinePreview("**Report** [source](https://cmux.com) " + "visible body text ".repeat(100),
                    false, 3, true, { expanded++ })
            }
        } } } }
        val result = layout()
        val value = result.layoutInput.text
        assertTrue(value.text.endsWith("… See more"))
        assertEquals(3, result.lineCount)
        assertFalse(result.hasVisualOverflow)
        assertTrue(value.spanStyles.any { it.item.fontWeight == FontWeight.Bold && value.text.substring(it.start, it.end) == "Report" })
        assertEquals("https://cmux.com", (value.getLinkAnnotations(0, value.length).first().item as LinkAnnotation.Url).url)
        val more = value.text.indexOf("See more")
        assertEquals(2, result.getLineForOffset(more))
        textNode().performTouchInput { click(result.getBoundingBox(more + 2).center) }
        compose.runOnIdle { assertEquals(1, expanded); assertEquals(0, opened) }
        capture()
    }

    @Test fun shortenedRowsAndLargeFontsKeepExpansionReachableAndUseCurrentCallback() {
        var width by mutableStateOf(240.dp)
        var scale by mutableStateOf(1f)
        var enabled by mutableStateOf(true)
        var generation by mutableStateOf(1)
        val opened = mutableListOf<Int>()
        compose.setContent { CmuxTheme { Surface {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                    val captured = generation
                    AgentFeedInlinePreview("Short answer", true, 1, enabled, { opened += captured }, modifier = Modifier.width(width))
                }
            }
        } } }
        assertTrue(layout().layoutInput.text.text.endsWith("… See more"))
        compose.runOnIdle { width = 90.dp; scale = 2f; generation = 2 }
        val result = layout()
        assertFalse(result.hasVisualOverflow)
        assertTrue(result.layoutInput.text.text.contains("See more"))
        textNode().performTouchInput { click(result.getBoundingBox(result.layoutInput.text.text.indexOf("more")).center) }
        compose.runOnIdle { assertEquals(listOf(2), opened); enabled = false }
        assertFalse(layout().layoutInput.text.getLinkAnnotations(0, layout().layoutInput.text.length).any { it.item is LinkAnnotation.Clickable })
    }

    @Test fun truncationNeverSplitsEmojiFlagsCombiningMarksOrMarkdownAnnotations() {
        val samples = listOf("👩🏽‍💻", "🇮🇳", "e\u0301", "👨‍👩‍👧‍👦")
        samples.forEach { grapheme ->
            val value = buildAnnotatedString {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append("A" + grapheme + " B"); pop()
            }
            // Every UTF-16 index within a grapheme must cut before its first code point.
            for (end in 2 until 1 + grapheme.length) {
                val collapsed = agentFeedCollapsedText(value, end, AnnotatedString("… See more")) { true }
                assertEquals("A… See more", collapsed.text)
                assertEquals(1, collapsed.spanStyles.single().end)
            }
            val complete = agentFeedCollapsedText(value, 1 + grapheme.length, AnnotatedString("… See more")) { true }
            assertEquals("A" + grapheme + "… See more", complete.text)
        }
    }
    @Test fun expansionHasAnIndependentAccessibleActionInRtlAndDisappearsWhenDisabled() {
        var enabled by mutableStateOf(true)
        var opened = 0; var expanded = 0
        compose.setContent { CmuxTheme { Surface {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.width(240.dp).safeDrawingPadding().clickable { opened++ }) {
                    AgentFeedInlinePreview("تقرير قصير", true, 2, enabled, { expanded++ })
                }
            }
        } } }
        compose.onNodeWithContentDescription("See more").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, expanded); assertEquals(0, opened); enabled = false }
        compose.onNodeWithContentDescription("See more").assertDoesNotExist()
        compose.runOnIdle { enabled = true }
        val link = compose.onNodeWithContentDescription("See more")
        link.performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, expanded); assertEquals(0, opened) }
    }

    @Test fun englishMarkdownKeepsTrailingPunctuationInAnRtlInterface() {
        compose.setContent { CmuxTheme { Surface {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AgentFeedMarkdownText("**Thanks**, continue.", Modifier.width(240.dp).safeDrawingPadding())
            }
        } } }
        val result = layout()
        assertEquals("Thanks, continue.", result.layoutInput.text.text)
        assertTrue(result.getBoundingBox(0).left < result.getBoundingBox(result.layoutInput.text.length - 1).left)
    }

}
