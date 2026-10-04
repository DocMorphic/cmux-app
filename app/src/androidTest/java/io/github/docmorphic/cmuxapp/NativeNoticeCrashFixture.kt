package io.github.docmorphic.cmuxapp

import org.mozilla.geckoview.GeckoSession

/** Instrumentation-only fault injection; the production navigation policy stays unchanged. */
internal object NativeNoticeCrashFixture {
    fun crashContent(page: NativeNoticeRenderer, onTermination: (String) -> Unit) {
        NativeNoticeEngine.checkMain()
        val session = page.javaClass.getDeclaredField("session").apply { isAccessible = true }
            .get(page) as GeckoSession
        val delegate = checkNotNull(session.contentDelegate)
        session.contentDelegate = object : GeckoSession.ContentDelegate by delegate {
            override fun onCrash(session: GeckoSession) {
                delegate.onCrash(session)
                onTermination("onCrash")
            }
            override fun onKill(session: GeckoSession) {
                delegate.onKill(session)
                onTermination("onKill")
            }
        }
        // Mozilla's content-crash test URI. Only this test bypasses the navigation
        // delegate to reach it; no production allowlist or engine setting is relaxed.
        session.load(GeckoSession.Loader().uri("about:crashcontent")
            .flags(GeckoSession.LOAD_FLAGS_BYPASS_LOAD_URI_DELEGATE))
    }
}
