package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Only coordinates are saved; never the document, JavaScript state or an Activity. */
internal class MarkdownViewportState {
    var x = 0f
    var y = 0f
    var zoom = 1f
    var child = -1
    var childTop = 0f
    var capture: (() -> Unit)? = null
    fun save(): List<Any> = listOf(x, y, zoom, child, childTop)
    fun restore(values: List<Any>) {
        if (values.size != 5) return
        fun coordinate(index: Int) = (values[index] as? Number)?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(0f, 100_000_000f) ?: 0f
        x = coordinate(0); y = coordinate(1); zoom = coordinate(2).coerceIn(.1f, 10f)
        child = (values[3] as? Number)?.toInt()?.coerceIn(-1, 1_500_000) ?: -1
        childTop = coordinate(4)
    }
}

internal class MarkdownViewportWebView(context: Context) : WebView(context) {
    var onReaderInput: (() -> Unit)? = null
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onReaderInput?.invoke()
        return super.onTouchEvent(event)
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        onReaderInput?.invoke(); return super.onGenericMotionEvent(event)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        onReaderInput?.invoke(); return super.onKeyDown(keyCode, event)
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        // Accessibility focus is also restored automatically when a new WebView mounts.
        // Only explicit reader actions should cancel pending layout restoration.
        if (action == 16 || action == 32 || action == 4096 || action == 8192 ||
            action in 16908344..16908347 || action in 16908358..16908361) onReaderInput?.invoke()
        return super.performAccessibilityAction(action, arguments)
    }
}

/** Synchronous native capture makes onSaveInstanceState independent of JS callback timing. */
@Suppress("DEPRECATION")
internal class MarkdownViewportBinding(private val web: MarkdownViewportWebView, private val state: MarkdownViewportState) : AutoCloseable {
    private var restoring = state.y > 0 || state.x > 0 || abs(state.zoom - 1f) > .01f
    private var closed = false
    private var ready = false
    private var restoreTop = state.childTop
    private val capture: () -> Unit = { capture() }
    init {
        state.capture = capture
        web.onReaderInput = { restoring = false; capture() }
        web.setOnScrollChangeListener { _, _, _, _, _ -> capture() }
    }
    private fun capture() {
        if (closed || !ready || restoring || web.scale <= 0) return
        state.x = web.scrollX / web.scale
        state.y = web.scrollY / web.scale
        state.zoom = (web.scale / web.resources.displayMetrics.density).coerceIn(.1f, 10f)
    }
    fun rendered() {
        if (closed) return
        // The adapter lives outside the hash-pinned upstream assets. ResizeObserver also
        // restores the anchor after asynchronous diagrams change preceding block heights.
        web.evaluateJavascript("""
            (() => {
              const content = document.getElementById('content');
              if (!content || window.__cmuxAndroidViewport) return;
              window.__cmuxAndroidViewport = true;
              const send = () => {
                const children = content.children;
                const y = window.visualViewport ? visualViewport.pageTop : scrollY;
                let index = -1, top = 0;
                let low = 0, high = children.length - 1;
                while (low <= high) {
                  const i = (low + high) >>> 1;
                  const value = children[i].getBoundingClientRect().top + scrollY;
                  if (value <= y) { index = i; top = value; low = i + 1; }
                  else high = i - 1;
                }
                const target = children[${state.child}];
                CmuxMarkdownBridge.postMessage(JSON.stringify({action:'markdownViewport', child:index, top:top,
                  restoreTop:target ? target.getBoundingClientRect().top + scrollY : null}));
              };
              new ResizeObserver(send).observe(content);
              addEventListener('scroll', send, {passive:true});
              if (window.visualViewport) { visualViewport.addEventListener('scroll', send); visualViewport.addEventListener('resize', send); }
              send();
            })();
        """.trimIndent(), null)
        web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (closed) return
                ready = true
                if (restoring) restore(restoreTop) else capture()
            }
        })
    }
    fun geometry(message: JSONObject) {
        if (closed) return
        if (restoring) {
            restoreTop = message.optDouble("restoreTop", state.childTop.toDouble()).toFloat().takeIf { it.isFinite() } ?: state.childTop
            if (ready) restore(restoreTop)
        } else {
            state.child = message.optInt("child", -1)
            state.childTop = message.optDouble("top", 0.0).toFloat().takeIf { it.isFinite() } ?: 0f
            capture()
        }
    }
    private fun restore(top: Float) {
        if (!top.isFinite()) return
        val targetScale = state.zoom * web.resources.displayMetrics.density
        if (web.scale > 0 && abs(targetScale / web.scale - 1f) > .01f) web.zoomBy(targetScale / web.scale)
        val y = (top + state.y - state.childTop).coerceAtLeast(0f)
        web.scrollTo((state.x * web.scale).roundToInt(), (y * web.scale).roundToInt())
        state.y = y; state.childTop = top
    }
    override fun close() {
        if (closed) return
        capture(); closed = true
        if (state.capture === capture) state.capture = null
        web.onReaderInput = null
        web.setOnScrollChangeListener(null)
    }
}
