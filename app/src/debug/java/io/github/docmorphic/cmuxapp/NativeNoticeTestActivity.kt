package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.os.Bundle
import org.mozilla.geckoview.GeckoView

/** Synthetic instrumentation host; absent from release. */
class NativeNoticeTestActivity : Activity() {
    lateinit var pageView: GeckoView
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        pageView = GeckoView(this)
        setContentView(pageView)
    }
}
