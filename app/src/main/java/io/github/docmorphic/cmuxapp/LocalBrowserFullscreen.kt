package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** The renderer supplies its own video controls; this host owns only their window. */
internal class LocalBrowserFullscreen {
    private data class Entry(val dialog: Dialog, val custom: View, val page: View,
        val visibility: Int, val callback: WebChromeClient.CustomViewCallback)
    private var entry: Entry? = null
    val active get() = entry != null

    fun show(page: View, custom: View, callback: WebChromeClient.CustomViewCallback) {
        val activity = page.context.activity()
        if (entry != null || activity == null || activity.isFinishing || activity.isDestroyed ||
            !page.isAttachedToWindow || custom.parent != null) {
            callback.onCustomViewHidden()
            return
        }
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val next = Entry(dialog, custom, page, page.visibility, callback)
        entry = next
        try {
            dialog.setOwnerActivity(activity)
            dialog.setCanceledOnTouchOutside(false)
            dialog.setContentView(custom, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            dialog.setOnDismissListener { if (entry === next) hide() }
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.black)
                addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            page.visibility = View.INVISIBLE
            dialog.show()
            dialog.window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                WindowCompat.setDecorFitsSystemWindows(this, false)
                WindowCompat.getInsetsController(this, decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            custom.setBackgroundColor(Color.BLACK)
            custom.requestFocus()
        } catch (_: RuntimeException) { hide() }
    }

    fun hide(notifyPage: Boolean = true) {
        val old = entry ?: return
        entry = null // Dismissal and the renderer's callback may reenter this method.
        old.dialog.setOnDismissListener(null)
        (old.custom.parent as? ViewGroup)?.removeView(old.custom)
        old.dialog.dismiss()
        old.page.visibility = old.visibility
        if (notifyPage) old.callback.onCustomViewHidden()
    }

    private fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.takeUnless { it === this }?.activity()
        else -> null
    }
}
