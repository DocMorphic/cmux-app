package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.Cookie
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.*
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Main-thread, process-owned engine. No activity, account token or persistent session is retained. */
internal class NativeNoticeEngine private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime = GeckoRuntime.create(context.applicationContext, GeckoRuntimeSettings.Builder()
        .configFilePath("").remoteDebuggingEnabled(false).consoleOutput(false).debugLogging(false)
        .loginAutofillEnabled(false).aboutConfigEnabled(false).build())
    private val ready = MutableStateFlow<WebExtension.Port?>(null)
    private var used = false
    private var port: WebExtension.Port? = null
    private var serial = 0
    private var failed = false
    var failureCode: String = ""
        private set
    private val pending = mutableMapOf<Int, CompletableDeferred<JSONObject>>()
    private val pages = mutableSetOf<NativeNoticeRenderer>()
    private val initialized: Deferred<Unit> = scope.async {
        val installed = runtime.webExtensionController.ensureBuiltIn(
            "resource://android/assets/notice-session/", EXTENSION).awaitNotice()
        val extension = runtime.webExtensionController.setAllowedInPrivateBrowsing(installed, true).awaitNotice()
        extension.setMessageDelegate(object : WebExtension.MessageDelegate {
            override fun onConnect(candidate: WebExtension.Port) {
                val sender = candidate.sender
                if (failed || (used && port != null) || sender.session != null ||
                    sender.environmentType != WebExtension.MessageSender.ENV_TYPE_EXTENSION ||
                    sender.webExtension.id != EXTENSION) { candidate.disconnect(); return }
                val previous = port
                port = candidate
                ready.value = null
                previous?.disconnect()
                candidate.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, source: WebExtension.Port) {
                        if (source !== port || failed || message !is JSONObject) return
                        if (message.optBoolean("ready")) ready.value = source
                        else pending.remove(message.optInt("id", -1))?.complete(message)
                    }
                    override fun onDisconnect(source: WebExtension.Port) {
                        if (source === port) {
                            ready.value = null
                            if (used) invalidate() else port = null
                        }
                    }
                })
            }
        }, "cmux_notice_session")
        withTimeout(10_000) { ready.first { it != null } }
        Unit
    }

    private fun invalidate() {
        if (failed) return
        failed = true
        // Extension restart invalidates every lease. Never create/reuse another context in this engine.
        val failure = IllegalStateException("Private notice engine unavailable")
        ready.value = null
        pending.values.toList().forEach { it.completeExceptionally(failure) }; pending.clear()
        pages.toList().forEach { it.engineFailed() }
    }
    suspend fun prepare(page: NativeNoticeRenderer): GeckoRuntime {
        checkMain()
        try { initialized.await() } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { invalidate(); throw e }
        check(!failed) { "Private notice engine unavailable" }
        currentCoroutineContext().ensureActive()
        withTimeout(10_000) { ready.first { it != null } }
        check(!failed)
        used = true
        pages += page
        return runtime
    }
    suspend fun command(op: String, lease: String, url: String? = null, cookies: List<Cookie> = emptyList()) {
        checkMain(); check(!failed)
        initialized.await()
        val channel = checkNotNull(ready.value) { "Private notice connection unavailable" }
        val id = ++serial
        val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        try {
            val data = JSONObject().put("id", id).put("op", op).put("lease", lease)
            if (url != null) data.put("url", url)
            if (op == "seed") data.put("cookies", JSONArray().apply { cookies.forEach { c ->
                put(JSONObject().put("domain", c.domain).put("path", c.path).put("name", c.name)
                    .put("value", c.value).put("secure", c.secure).put("httpOnly", c.httpOnly)
                    .put("hostOnly", c.hostOnly).put("expires", c.expiresAt))
            } })
            channel.postMessage(data)
            val result = withTimeout(5_000) { reply.await() }
            val reported = result.optString("failureCode")
            reported.takeIf { it.matches(Regex("cookie-validation-[0-9]+|cookie-not-stored|cookie-api-threw|cookie-api-no-result|cookie-readback-failed|operation-failed")) }?.let { failureCode = it }
            check(result.optBoolean("done") && !result.optBoolean("failed")) { "Private notice operation failed" }
        } finally { pending.remove(id) }
    }
    fun theme(dark: Boolean) {
        checkMain()
        runtime.settings.preferredColorScheme = if (dark) GeckoRuntimeSettings.COLOR_SCHEME_DARK else GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
    }
    fun retire(page: NativeNoticeRenderer, contextId: String, lease: String, acquired: Boolean): Deferred<Boolean> {
        checkMain(); pages -= page
        // This scope outlives presentation cancellation. Session.close() happens before this call.
        return scope.async {
            var cleared = true
            if (acquired) try { command("clear", lease) } catch (_: Exception) { cleared = false; invalidate() }
            runtime.storageController.clearDataForSessionContext(contextId)
            cleared
        }
    }
    companion object {
        private const val EXTENSION = "notice-session@cmux-app.invalid"
        private var instance: NativeNoticeEngine? = null
        fun get(context: Context): NativeNoticeEngine {
            checkMain()
            return instance ?: NativeNoticeEngine(context).also { instance = it }
        }
        internal fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "Notice engine requires main thread" }
    }
}

/** A single private context; owns exchange, navigation policy, timeout and asynchronous retirement. */
internal class NativeNoticeRenderer(
    context: Context, parent: CoroutineScope, private val policy: WhatsNewWebPolicy, val url: String,
    dark: Boolean, deadlineMillis: Long, private val currentOwner: () -> Boolean,
    cookies: suspend (String) -> List<Cookie>
) : NativeWhatsNewPreloadedPage {
    private val application = context.applicationContext
    private var engine: NativeNoticeEngine? = null
    private val lease = UUID.randomUUID().toString()
    private val contextId = "cmux-notice-$lease"
    private val blank = "about:blank#cmux-notice-$lease"
    private var session: GeckoSession? = null
    private var view: GeckoView? = null
    private var closed = false
    private val mutableClosed = MutableStateFlow(false)
    override val isClosed = mutableClosed.asStateFlow()
    internal val diagnostic: String get() = "$stage/${engine?.failureCode.orEmpty()}"
    internal var stage: String = "starting"
        private set
    private var acquired = false
    private var remoteStarted = false
    private var openingBlank = true
    private var isDark = dark
    private var retired: Deferred<Boolean>? = null
    private val blankReady = CompletableDeferred<Unit>()
    override val load: NativeWhatsNewWebLoad = NativeWhatsNewWebLoad(parent, policy, url, deadlineMillis,
        stopRenderer = { retire() }, closeRenderer = { retire() }) {
        checkCurrent()
        val e = NativeNoticeEngine.get(application).also { engine = it }
        stage = "engine"
        val runtime = e.prepare(this)
        checkCurrent(); e.theme(isDark)
        val s = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).contextId(contextId)
            .suspendMediaWhenInactive(true).build()).also { session = it }
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, uri: String?,
                permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (uri == blank && !remoteStarted) blankReady.complete(Unit)
            }
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
                navigation(request.uri, true, request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW)
            override fun onSubframeLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
                navigation(request.uri, false, false)
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null
            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                load.failedInitialPage(); return null
            }
        }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, uri: String) {
                if (uri == blank) openingBlank = true
                else if (policy.allows(uri)) { openingBlank = false; remoteStarted = true }
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (closed) return
                if (!currentOwner()) { close(); return }
                if (openingBlank && !remoteStarted) {
                    if (!success) load.failedInitialPage()
                } else if (remoteStarted) {
                    if (success) load.finishedInitialPage() else load.failedInitialPage()
                }
            }
        }
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) = engineFailed()
            override fun onKill(session: GeckoSession) = engineFailed()
            override fun onCloseRequest(session: GeckoSession) = close()
        }
        s.open(runtime)
        view?.setSession(s)
        stage = "blank"
        s.loadUri(blank)
        blankReady.await(); checkCurrent()
        // Set before dispatch: canceled/late acquire replies still require scoped cleanup.
        acquired = true
        stage = "acquire"
        e.command("acquire", lease)
        checkCurrent()
        stage = "exchange"
        val sessionCookies = cookies(url)
        checkCurrent()
        stage = "seed"
        e.command("seed", lease, url, sessionCookies)
        checkCurrent()
        openingBlank = false
        stage = "navigation"
        s.loadUri(url)
    }
    private fun checkCurrent() {
        NativeNoticeEngine.checkMain()
        check(!closed && currentOwner()) { "Notice owner changed" }
    }
    private fun navigation(uri: String, main: Boolean, newWindow: Boolean): GeckoResult<AllowOrDeny> {
        val allowed = if (closed || !currentOwner()) { close(); false }
            else if (newWindow) false
            else if (!remoteStarted && uri == blank) true
            else load.allowsNavigation(uri, main)
        return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
    }
    fun attach(target: GeckoView) {
        NativeNoticeEngine.checkMain()
        if (closed || !currentOwner()) { close(); return }
        if (view !== target) { detach(); view = target; session?.let(target::setSession) }
    }
    fun detach(target: GeckoView) { if (view === target) detach() }
    fun detach() { NativeNoticeEngine.checkMain(); view?.releaseSession(); view = null }
    override fun theme(dark: Boolean) { isDark = dark; if (!closed) engine?.theme(dark) }
    internal fun engineFailed() { load.failedInitialPage(); retire() }
    private fun retire() {
        NativeNoticeEngine.checkMain()
        if (closed) return
        closed = true
        mutableClosed.value = true
        detach()
        session?.let { it.stop(); it.close() }; session = null
        retired = engine?.retire(this, contextId, lease, acquired)
    }
    suspend fun awaitRetired(): Boolean = retired?.await() ?: closed
    override fun close() { NativeNoticeEngine.checkMain(); load.close() }
}

private suspend fun <T : Any> GeckoResult<T>.awaitNotice(): T = suspendCancellableCoroutine { continuation ->
    accept({ if (it != null) continuation.resume(it) else continuation.resumeWithException(IllegalStateException("Missing engine result")) },
        { continuation.resumeWithException(it ?: IllegalStateException("Engine operation failed")) })
}
