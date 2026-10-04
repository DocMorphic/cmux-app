package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import okhttp3.Cookie
import org.mozilla.geckoview.GeckoView

/** Retained by the notice ViewModel. Rotation and theme changes reuse the same private page. */
internal class NativeNoticeArchiveOwner(private val context: Context, private val scope: CoroutineScope) : AutoCloseable {
    private var owner: String? = null
    private val pages = mutableMapOf<WhatsNewPage, NativeNoticeRenderer>()
    private var policy = WhatsNewWebPolicy(null)
    private var current: (String) -> Boolean = { false }
    private var cookies: suspend (String) -> List<Cookie> = { emptyList() }
    fun configure(login: String?, policy: WhatsNewWebPolicy, isCurrent: (String) -> Boolean,
        sessionCookies: suspend (String) -> List<Cookie>) {
        if (owner != login || this.policy.originKey != policy.originKey) close()
        owner = login; this.policy = policy; current = isCurrent; cookies = sessionCookies
    }
    fun page(page: WhatsNewPage, dark: Boolean, retry: Boolean): NativeNoticeRenderer {
        val login = checkNotNull(owner)
        check(current(login)) { "Notice owner changed" }
        val body = page.body as WhatsNewBody.Web
        // A content replacement may keep its ID; never reuse cookies/document for the old URL.
        pages.keys.filter { it.key == page.key && (retry || it != page) }.toList().forEach { pages.remove(it)?.close() }
        return pages.getOrPut(page) {
            NativeNoticeRenderer(context, scope, policy, body.url, dark, NativeWhatsNewWebLoad.ARCHIVE_DEADLINE_MS,
                currentOwner = { owner == login && current(login) }, cookies = cookies)
        }.also { it.theme(dark) }
    }
    fun dismiss() { pages.values.toList().forEach { it.close() }; pages.clear() }
    override fun close() { owner = null; dismiss() }
}

@Composable
internal fun NativeNoticeArchiveWeb(page: WhatsNewPage, owner: NativeNoticeArchiveOwner?, modifier: Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    var attempt by remember(page) { mutableIntStateOf(0) }
    var renderer by remember(owner, page) { mutableStateOf<NativeNoticeRenderer?>(null) }
    var failed by remember(owner, page, attempt) { mutableStateOf(false) }
    LaunchedEffect(owner, page, attempt) {
        renderer = null
        try { renderer = owner?.page(page, dark, attempt > 0); failed = renderer == null }
        catch (_: IllegalStateException) { failed = true }
    }
    SideEffect { renderer?.theme(dark) }
    val phase = renderer?.load?.phase?.collectAsState()?.value ?: WhatsNewWebPhase.LOADING
    val retired = renderer?.isClosed?.collectAsState()?.value == true
    Box(modifier.fillMaxSize().testTag("whatsnew.web"), contentAlignment = Alignment.Center) {
        val active = renderer
        if (active != null && !failed && !retired && phase != WhatsNewWebPhase.FAILED) {
            key(active) {
                AndroidView(factory = { GeckoView(it) }, modifier = Modifier.fillMaxSize(),
                    onRelease = { active.detach(it) }, update = { active.attach(it) })
            }
        }
        if (failed || retired || phase == WhatsNewWebPhase.FAILED) Column(Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("This page needs an internet connection. Please try again.")
            TextButton(onClick = { attempt++ }, modifier = Modifier.testTag("whatsnew.web.retry")) { Text("Try Again") }
        } else if (phase == WhatsNewWebPhase.LOADING) CircularProgressIndicator()
    }
}
