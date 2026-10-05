package io.github.docmorphic.cmuxapp

import android.content.Context
import android.content.MutableContextWrapper
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/** Main-thread owner retained by the browser ViewModel, not by an Activity. */
internal class LocalBrowserWebOwner(context: Context) : AutoCloseable {
    private val application = context.applicationContext
    private var surface: LocalBrowserSurface? = null
    private var wrapper: MutableContextWrapper? = null
    private var host: LocalBrowserWebHost? = null
    private var active: Lease? = null
    private var closed = false
    internal class Lease(val host: LocalBrowserWebHost)

    fun attach(context: Context, surface: LocalBrowserSurface,
        chooseFiles: (Any, WebChromeClient.FileChooserParams, ValueCallback<Array<Uri>>) -> Boolean,
        cancelFiles: (Any) -> Unit, beforeNavigation: (suspend (String?) -> Unit)?): Lease {
        check(!closed) { "Browser view owner is closed" }
        if (this.surface !== surface) {
            retire()
            val wrapped = MutableContextWrapper(application)
            wrapper = wrapped; this.surface = surface
            host = LocalBrowserWebHost(wrapped, surface, chooseFiles, cancelFiles, beforeNavigation)
        }
        val view = checkNotNull(host)
        // A replacement composition can mount before the old release callback.
        (view.parent as? ViewGroup)?.removeView(view)
        view.detachUi()
        wrapper?.baseContext = context
        view.bindUi(chooseFiles, cancelFiles, beforeNavigation)
        return Lease(view).also { active = it }
    }

    fun detach(lease: Lease) {
        if (active !== lease) return
        active = null
        lease.host.foreground(false)
        lease.host.detachUi()
        wrapper?.baseContext = application
    }

    private fun retire() {
        active = null
        host?.let { (it.parent as? ViewGroup)?.removeView(it); it.release() }
        wrapper?.baseContext = application
        host = null; wrapper = null; surface = null
    }
    override fun close() { closed = true; retire() }
}
