package io.github.docmorphic.cmuxapp

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Layout
import android.text.Spanned
import androidx.core.text.PrecomputedTextCompat
import androidx.core.widget.TextViewCompat
import android.text.method.LinkMovementMethod
import android.text.style.*
import android.view.View
import android.widget.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.core.widget.NestedScrollView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView

internal fun agentFeedSpanned(runs: List<AgentMarkdownRun>): CharSequence = SpannableStringBuilder().apply {
    runs.forEach { run ->
        val start = length
        append(run.text)
        fun span(value: Any) { setSpan(value, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val style = run.style
        if (style.bold || style.italic) span(StyleSpan((if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0)))
        if (style.code) { span(TypefaceSpan("monospace")); span(BackgroundColorSpan(0x222C7ABB)) }
        if (style.strike) span(StrikethroughSpan())
        style.link?.takeIf(MarkdownPreviewPolicy::external)?.let { url ->
            span(object : URLSpan(url) {
                override fun onClick(widget: View) {
                    if (MarkdownPreviewPolicy.external(url)) runCatching {
                        widget.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
            })
        }
    }
}

internal fun agentFeedQuoteTextView(context: Context) = TextView(context).apply {
    setTextColor(0xFF9CA3AF.toInt()); setLinkTextColor(0xFF76B9FF.toInt())
    textSize = 14f; includeFontPadding = false
    // Android 35+ defaults to glyph bounds for each break candidate. With a
    // huge styled paragraph this can repeatedly scan the same style run.
    // Advance-based wrapping matches classic TextView; leave drawing room
    // at both edges for italic overhangs instead.
    if (android.os.Build.VERSION.SDK_INT >= 35) setUseBoundsForWidth(false)
    val edge = (2 * resources.displayMetrics.density).toInt()
    setPadding(edge, 0, edge, 0)
    breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
    movementMethod = LinkMovementMethod.getInstance()
    textDirection = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        View.TEXT_DIRECTION_FIRST_STRONG_RTL else View.TEXT_DIRECTION_FIRST_STRONG_LTR
}

internal class ComposerScrollPosition(var y: Int = 0) {
    companion object {
        val Saver = Saver<ComposerScrollPosition, Int>({ it.y }, { ComposerScrollPosition(it) })
    }
}
private class ComposerScrollView(context: Context) : NestedScrollView(context) {
    var displayedBody: CharSequence? = null
    var restoreY: Int? = null
    var saveScroll: ((Int) -> Unit)? = null
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        restoreY?.let { y -> scrollTo(0, y); restoreY = null; saveScroll?.invoke(scrollY) }
    }
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (restoreY == null) saveScroll?.invoke(t)
    }
}

/** One native scroll container avoids Compose's finite text-height constraints.
 * Only the full body is a TextView; the existing controls retain their Compose state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentFeedComposerScroll(modifier: Modifier, fullText: CharSequence?, loadingExpanded: Boolean,
    position: ComposerScrollPosition,
    avatar: @Composable () -> Unit, heading: @Composable () -> Unit, preview: @Composable () -> Unit,
    replying: @Composable () -> Unit, draft: @Composable () -> Unit, status: @Composable () -> Unit) {
    val parent = rememberCompositionContext()
    val currentAvatar by rememberUpdatedState(avatar)
    val currentHeading by rememberUpdatedState(heading)
    val currentPreview by rememberUpdatedState(preview)
    val currentReplying by rememberUpdatedState(replying)
    val currentDraft by rememberUpdatedState(draft)
    val currentStatus by rememberUpdatedState(status)
    val density = LocalDensity.current
    // NestedScrollView dispatches child scroll deltas through AndroidView to
    // Compose's native IME animation controller, including a held partial drag.
    AndroidView(modifier = modifier.imeNestedScroll().clipToBounds(), factory = { context ->
        fun dp(value: Int) = (value * density.density).toInt()
        fun part(viewId: Int, content: @Composable () -> Unit) = ComposeView(context).apply {
            id = viewId
            setParentCompositionContext(parent)
            setContent(content)
        }
        ComposerScrollView(context).apply {
            tag = "AgentFeedComposeScroll"
            isFillViewport = false
            // Focus/IME layout must not leave a smooth-scroll animation running
            // after an explicit saved-position restore. Touch flings still work.
            isSmoothScrollingEnabled = false
            isNestedScrollingEnabled = true
            clipToPadding = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(16))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isBaselineAligned = false
                    addView(FrameLayout(context).apply {
                        addView(View(context).apply { setBackgroundColor(0xFF45494F.toInt()) },
                            FrameLayout.LayoutParams(dp(2), -1).apply { leftMargin = dp(19); topMargin = dp(40) })
                        addView(part(R.id.feed_compose_avatar) { currentAvatar() }, FrameLayout.LayoutParams(dp(40), dp(40)))
                    }, LinearLayout.LayoutParams(dp(40), -1))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        minimumHeight = dp(58)
                        addView(part(R.id.feed_compose_heading) { currentHeading() })
                        addView(part(R.id.feed_compose_preview) { Box(Modifier.testTag("AgentFeedComposerPreviewContent")) { currentPreview() } }.apply { tag = "AgentFeedComposePreview" })
                        addView(agentFeedQuoteTextView(context).apply {
                            tag = "AgentFeedExpandedQuote"
                            visibility = View.GONE
                        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                        addView(part(R.id.feed_compose_replying) { currentReplying() })
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                }, LinearLayout.LayoutParams(-1, -2))
                addView(part(R.id.feed_compose_draft) { currentDraft() })
                addView(part(R.id.feed_compose_status) { currentStatus() })
            }, FrameLayout.LayoutParams(-1, -2))
            restoreY = position.y
        }
    }, update = { scroll ->
        val body = scroll.findViewWithTag<TextView>("AgentFeedExpandedQuote")
        // Do not replace text on draft keystrokes or reset selection/focus.
        if (scroll.displayedBody !== fullText) {
            scroll.restoreY = position.y
            scroll.displayedBody = fullText
            if (fullText is PrecomputedTextCompat) TextViewCompat.setPrecomputedText(body, fullText)
            else body.text = fullText ?: ""
        }
        body.visibility = if (fullText == null) View.GONE else View.VISIBLE
        scroll.findViewWithTag<View>("AgentFeedComposePreview").visibility = if (fullText == null) View.VISIBLE else View.GONE
        // A restored expanded body is not present until its authenticated read
        // finishes. Its temporary loading layout must not overwrite saved scroll.
        scroll.saveScroll = if (loadingExpanded) null else { y -> position.y = y }
        if (loadingExpanded) scroll.restoreY = null
    }, onRelease = { scroll ->
        // Native layout/focus callbacks may finish after the sheet leaves composition.
        // A retired view must not overwrite the position retained for reconnect.
        scroll.saveScroll = null
        scroll.restoreY = null
    })
}
